package gitops.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gitops.Json;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thin wrapper around {@link HttpClient}: JSON in, JSON out, and an exception on any non-2xx status.
 * One client is shared by every provider; it is thread-safe, so the engine can serve requests in parallel.
 */
final class Http {

    enum Method { GET, POST, PUT, PATCH, DELETE }

    /** A response with a 2xx status. */
    record Response(int status, String body) {
        JsonNode json() {
            return body == null || body.isBlank() ? Json.MAPPER.nullNode() : Json.parse(body);
        }
    }

    private static final Pattern PARAM = Pattern.compile("\\{[^}]+}");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .proxy(ProxySelector.getDefault())
            .build();

    private final String baseUrl;
    private final Map<String, String> headers;

    Http(String baseUrl, Map<String, String> headers) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.headers = Map.copyOf(headers);
    }

    Response send(Method method, String path, JsonNode body, Object... pathParams) {
        return send(method, path, Map.of(), body, pathParams);
    }

    Response send(Method method, String path, Map<String, ?> query, JsonNode body, Object... pathParams) {
        String url = baseUrl + expand(path, true, pathParams) + queryString(query);
        // Errors name the request as a person wrote it, so a label like "cost$1" reads as itself.
        String shown = baseUrl + expand(path, false, pathParams);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", headers.getOrDefault("Accept", "application/json"));
        headers.forEach((name, value) -> {
            if (!name.equals("Accept")) {
                request.header(name, value);
            }
        });
        if (body != null) {
            request.header("Content-Type", "application/json")
                    .method(method.name(), HttpRequest.BodyPublishers.ofString(Json.compact(body), StandardCharsets.UTF_8));
        } else {
            request.method(method.name(), HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> response;
        try {
            response = CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UnreachableException(method.name(), shown, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UnreachableException(method.name(), shown, e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ApiException(method.name(), shown, response.statusCode(), response.body());
        }
        return new Response(response.statusCode(), response.body());
    }

    static List<ObjectNode> objects(Response response) {
        JsonNode node = response.json();
        if (!node.isArray()) {
            throw new IllegalStateException("Expected a JSON array but got: " + node);
        }
        List<ObjectNode> objects = new ArrayList<>();
        node.forEach(n -> objects.add((ObjectNode) n));
        return objects;
    }

    /** Fills {name} placeholders in order, percent-encoding each value as one path segment when asked. */
    static String expand(String path, boolean encode, Object... params) {
        Matcher matcher = PARAM.matcher(path);
        StringBuilder result = new StringBuilder();
        int i = 0;
        while (matcher.find()) {
            if (i >= params.length) {
                throw new IllegalArgumentException("Missing path parameter for " + matcher.group() + " in " + path);
            }
            String value = String.valueOf(params[i++]);
            matcher.appendReplacement(result, Matcher.quoteReplacement(encode ? encode(value) : value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String queryString(Map<String, ?> query) {
        if (query.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        new LinkedHashMap<>(query).forEach((k, v) -> parts.add(encode(k) + "=" + encode(String.valueOf(v))));
        return "?" + String.join("&", parts);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
