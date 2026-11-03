package gitops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * What "it worked" means beyond the API accepting the change: after an apply, the engine requests a URL until it
 * answers as expected or the time runs out. Configured as "healthCheck" in target.json: {@code url} (may use
 * {@code {baseUrl}}), {@code expectStatus} (default 200), {@code contains} (text the body must include) and
 * {@code timeoutSeconds} (default 60). A failed check fails the apply.
 */
public final class HealthCheck {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .proxy(ProxySelector.getDefault())
            .build();

    private HealthCheck() {
    }

    /** The target's check, or null when it has none. Throws for a check that isn't set up right. */
    public static JsonNode spec(Target target) {
        JsonNode spec = target.settings().get("healthCheck");
        if (spec == null || spec.isNull()) {
            return null;
        }
        if (!spec.isObject() || !spec.path("url").isTextual()) {
            throw new IllegalArgumentException(target.dir().resolve(Target.FILE) + ": \"healthCheck\" needs a \"url\"");
        }
        return spec;
    }

    public static String url(Target target, JsonNode spec) {
        String url = spec.path("url").asText();
        if (url.contains("{baseUrl}")) {
            url = url.replace("{baseUrl}", target.endpoint().replaceAll("/+$", ""));
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalArgumentException(target.dir().resolve(Target.FILE) + ": the health check URL must be http:// or https://");
        }
        return url;
    }

    /** Runs the target's check: {ok, url, status, message, seconds}. */
    public static ObjectNode run(Target target) {
        JsonNode spec = spec(target);
        if (spec == null) {
            return null;
        }
        String url = url(target, spec);
        int expect = spec.path("expectStatus").asInt(200);
        String contains = spec.path("contains").asText("");
        Duration timeout = Duration.ofSeconds(Math.max(1, Math.min(600, spec.path("timeoutSeconds").asInt(60))));
        long start = System.nanoTime();
        long deadline = start + timeout.toNanos();
        String last = "no answer";
        int status = 0;
        while (true) {
            try {
                HttpResponse<String> response = CLIENT.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).build(),
                        HttpResponse.BodyHandlers.ofString());
                status = response.statusCode();
                if (status != expect) {
                    last = "answered " + status + ", expected " + expect;
                } else if (!contains.isEmpty() && !response.body().contains(contains)) {
                    last = "answered " + status + " but the body doesn't contain \"" + contains + "\"";
                } else {
                    return result(true, url, status, "answered " + status + (contains.isEmpty() ? "" : " with \"" + contains + "\""), start);
                }
            } catch (IOException e) {
                last = "couldn't connect (" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + ")";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return result(false, url, status, "interrupted", start);
            }
            if (System.nanoTime() >= deadline) {
                return result(false, url, status, last + " after " + timeout.toSeconds() + "s", start);
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return result(false, url, status, "interrupted", start);
            }
        }
    }

    private static ObjectNode result(boolean ok, String url, int status, String message, long start) {
        return Json.MAPPER.createObjectNode().put("ok", ok).put("url", url).put("status", status).put("message", message)
                .put("seconds", Math.round((System.nanoTime() - start) / 1e9 * 10) / 10.0);
    }
}
