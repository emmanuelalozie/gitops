package gitops;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The mocks the engine server started for the desktop app, by port. A target opts in with {@code "mock": "<seed>"}
 * in target.json (a path in the repository, such as mock/db.json) and a local baseUrl; targets sharing a port share
 * one mock. Everything started here is stopped when the server exits; a mock started some other way is reported
 * as running but never stopped from here.
 */
final class Mocks {

    private final Path repo;
    private final Map<Integer, MockServer.Handle> running = new ConcurrentHashMap<>();

    Mocks(Path repo) {
        this.repo = repo.toAbsolutePath().normalize();
    }

    /** Where a target's mock would run, or null when the target has no mock. */
    record Spec(Path seed, String host, int port) {
    }

    Spec spec(Target target) {
        String seed = target.settings().path("mock").asText("");
        if (seed.isBlank()) {
            return null;
        }
        Path file = repo.resolve(seed).normalize();
        if (!file.startsWith(repo)) {
            throw new IllegalArgumentException(target.name() + ": \"mock\" must be a file inside the repository, got '" + seed + "'");
        }
        URI url = URI.create(target.endpoint());
        String host = url.getHost();
        if (!"127.0.0.1".equals(host) && !"localhost".equals(host)) {
            throw new IllegalArgumentException(target.name() + ": a mock only runs on this computer, but the target points at " + target.endpoint());
        }
        // Not "localhost": on Windows Node binds that to IPv6 while Java connects over IPv4.
        return new Spec(file, "127.0.0.1", url.getPort() > 0 ? url.getPort() : 80);
    }

    ObjectNode status(Target target) {
        Spec spec = spec(target);
        ObjectNode node = Json.MAPPER.createObjectNode().put("target", target.name()).put("configured", spec != null);
        if (spec == null) {
            return node;
        }
        MockServer.Handle handle = running.get(spec.port());
        boolean managed = handle != null && handle.process().isAlive();
        return node.put("seed", repo.relativize(spec.seed()).toString().replace('\\', '/'))
                .put("url", "http://" + spec.host() + ":" + spec.port())
                .put("running", MockServer.portInUse(spec.host(), spec.port()))
                .put("startedHere", managed)
                .put("via", managed ? handle.via() : null);
    }

    /** Starts the target's mock unless something already answers on its port. Waits until it serves. */
    ObjectNode start(Target target) {
        Spec spec = spec(target);
        if (spec == null) {
            throw new EngineError.NotAllowed("'" + target.name() + "' has no mock: add \"mock\": \"mock/db.json\" to its target.json");
        }
        synchronized (this) {
            if (!MockServer.portInUse(spec.host(), spec.port())) {
                try {
                    MockServer.Handle handle = MockServer.start(spec.seed(), spec.host(), spec.port());
                    if (handle == null) {
                        throw new EngineError.NotAllowed("Starting a mock needs Node.js (https://nodejs.org) or Docker, and neither was found.");
                    }
                    running.put(spec.port(), handle);
                    if (!MockServer.waitUntilUp(spec.host(), spec.port(), Duration.ofMinutes(3))) {
                        handle.stop();
                        running.remove(spec.port());
                        throw new IllegalStateException("The mock didn't answer within 3 minutes. Its output:\n" + Files.readString(handle.log()));
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while starting the mock", e);
                }
            }
        }
        return status(target);
    }

    /** Stops the target's mock if this server started it. Its data resets to the seed next time. */
    ObjectNode stop(Target target) {
        Spec spec = spec(target);
        if (spec == null) {
            throw new EngineError.NotAllowed("'" + target.name() + "' has no mock");
        }
        MockServer.Handle handle = running.remove(spec.port());
        if (handle == null) {
            if (MockServer.portInUse(spec.host(), spec.port())) {
                throw new EngineError.NotAllowed("The mock on port " + spec.port() + " was started outside the app (a terminal,"
                        + " docker compose or ./gradlew demo), so the app leaves it alone. Stop it where it was started.");
            }
            return status(target);
        }
        handle.stop();
        for (int i = 0; i < 50 && MockServer.portInUse(spec.host(), spec.port()); i++) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return status(target);
    }

    void stopAll() {
        running.values().forEach(MockServer.Handle::stop);
        running.clear();
    }
}
