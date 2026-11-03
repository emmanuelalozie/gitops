package gitops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gitops.Plan.Change;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * {@code gitops serve}: the engine as a long-running process that answers JSON-RPC 2.0, one message per line
 * on stdin and stdout. The desktop app starts it once, so it doesn't pay JVM startup on every click.
 *
 * <p>Requests run in parallel, except that anything that changes a target (apply, writing or deleting a
 * resource file) holds that target's lock. Applies are only allowed for targets with {@code localApply}, and
 * only with the id of the plan the person approved. Progress of an apply is sent as {@code apply.progress}
 * notifications. Methods and their results are described in docs/engine-protocol.md.
 */
public final class EngineServer {

    static final int PARSE_ERROR = -32700;
    static final int INVALID_REQUEST = -32600;
    static final int METHOD_NOT_FOUND = -32601;
    static final int ENGINE_ERROR = -32000;

    private final Engine engine;
    private final BufferedReader in;
    private final PrintStream out;
    private final ExecutorService workers = Executors.newFixedThreadPool(4, r -> {
        Thread thread = new Thread(r, "engine-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, ReentrantLock> targetLocks = new ConcurrentHashMap<>();

    EngineServer(Engine engine, InputStream in, OutputStream out) {
        this.engine = engine;
        this.in = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.out = new PrintStream(out, false, StandardCharsets.UTF_8);
    }

    static int main(String[] args) {
        Path configRoot = Path.of("config");
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--config-dir") && i + 1 < args.length) {
                configRoot = Path.of(args[++i]);
            } else if (!args[i].equals("--stdio")) {
                System.err.println("error: unexpected argument '" + args[i] + "' (serve takes --config-dir DIR)");
                return Main.ERROR;
            }
        }
        // stdout belongs to the protocol: anything else printed by mistake goes to stderr instead.
        PrintStream protocol = System.out;
        System.setOut(System.err);
        return new EngineServer(new Engine(configRoot), System.in, protocol).serve();
    }

    /** Reads requests until stdin closes, then waits for running requests (an apply finishes) and returns. */
    int serve() {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode message;
                try {
                    message = Json.parse(line);
                } catch (IllegalArgumentException e) {
                    send(error(null, PARSE_ERROR, e.getMessage(), null));
                    continue;
                }
                JsonNode id = message.get("id");
                if (!message.isObject() || !message.path("method").isTextual()) {
                    send(error(id, INVALID_REQUEST, "A request needs a method", null));
                    continue;
                }
                workers.submit(() -> handle(id, message.path("method").asText(),
                        message.path("params").isObject() ? (ObjectNode) message.get("params") : Json.MAPPER.createObjectNode()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            workers.shutdown();
            try {
                workers.awaitTermination(10, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return Main.OK;
    }

    private void handle(JsonNode id, String method, ObjectNode params) {
        try {
            JsonNode result = call(id, method, params);
            if (id != null) {
                ObjectNode response = Json.MAPPER.createObjectNode().put("jsonrpc", "2.0");
                response.set("id", id);
                response.set("result", result);
                send(response);
            }
        } catch (MethodNotFound e) {
            send(error(id, METHOD_NOT_FOUND, e.getMessage(), null));
        } catch (Exception e) {
            send(error(id, ENGINE_ERROR, EngineError.message(e), EngineError.toJson(e)));
        }
    }

    JsonNode call(JsonNode id, String method, ObjectNode params) {
        return switch (method) {
            case "engine.version" -> Engine.version();
            case "targets.list" -> engine.targetsJson();
            case "config.validate" -> engine.validateJson(optional(params, "target"));
            case "plan.compute" -> plan(engine.target(required(params, "target")));
            case "plan.apply" -> apply(id, params);
            case "live.fetch" -> Json.MAPPER.createObjectNode().set("resources", engine.fetch(engine.target(required(params, "target"))));
            case "targets.compare" -> engine.compare(required(params, "from"), required(params, "to"));
            case "kind.schema" -> engine.schema(required(params, "kind"));
            case "resource.check" -> check(params);
            case "resource.write" -> locked(required(params, "target"), () -> engine.writeResource(
                    engine.target(required(params, "target")), object(params, "resource"), optional(params, "previousKey")));
            case "resource.delete" -> locked(required(params, "target"), () -> engine.deleteResource(
                    engine.target(required(params, "target")), required(params, "key")));
            case "repo.lint" -> Lint.run(engine);
            default -> throw new MethodNotFound(method);
        };
    }

    /** A plan, or a skipped plan when the target's required token isn't set (the dashboard shows both). */
    private JsonNode plan(Target target) {
        if (target.missingRequiredToken()) {
            return PlanJson.skipped(target, "'" + target.name() + "' needs a token in " + target.tokenVariable()
                    + ", which isn't set.");
        }
        return engine.planJson(target, engine.plan(target));
    }

    private JsonNode check(ObjectNode params) {
        Target target = engine.target(required(params, "target"));
        List<ConfigValidator.Problem> problems = engine.checkResource(target, object(params, "resource"),
                optional(params, "previousKey"));
        ObjectNode result = Json.MAPPER.createObjectNode().put("ok", problems.isEmpty());
        ArrayNode array = result.putArray("problems");
        problems.forEach(p -> array.add(p.toJson()));
        return result;
    }

    private JsonNode apply(JsonNode id, ObjectNode params) {
        Target target = engine.target(required(params, "target"));
        String planId = required(params, "planId");
        if (!target.localApply()) {
            throw new EngineError.NotAllowed("'" + target.name() + "' can't be applied from the desktop app: it changes"
                    + " through a merged pull request and the Apply workflow. Set \"localApply\": true in its target.json"
                    + " only for a throwaway system such as the dev mock.");
        }
        engine.requireToken(target);
        return locked(target.name(), () -> {
            Engine.ApplyResult result = engine.apply(target, planId, params.path("allowDeleteAll").asBoolean(false),
                    plan -> { }, progress(id, target));
            ObjectNode node = Json.MAPPER.createObjectNode()
                    .put("schemaVersion", PlanJson.SCHEMA_VERSION)
                    .put("target", target.name())
                    .put("planId", result.planId())
                    .put("applied", result.applied())
                    .put("verified", result.verified())
                    .put("verifyAfterApply", target.verifyAfterApply());
            node.set("after", result.after() == null ? Json.MAPPER.nullNode() : engine.planJson(target, result.after()));
            return node;
        });
    }

    private Applier.Listener progress(JsonNode requestId, Target target) {
        return new Applier.Listener() {
            @Override
            public void starting(Change change, Applier.Step step) {
                notify(change, step, "starting", null);
            }

            @Override
            public void finished(Change change, Applier.Step step) {
                notify(change, step, "finished", null);
            }

            @Override
            public void failed(Change change, Applier.Step step, RuntimeException error) {
                notify(change, step, "failed", error);
            }

            private void notify(Change change, Applier.Step step, String state, RuntimeException error) {
                ObjectNode params = Json.MAPPER.createObjectNode();
                params.set("requestId", requestId);
                params.put("target", target.name()).put("key", change.key()).put("step", step.id()).put("state", state);
                if (error != null) {
                    params.set("error", EngineError.toJson(error));
                }
                send(Json.MAPPER.createObjectNode().put("jsonrpc", "2.0").put("method", "apply.progress").set("params", params));
            }
        };
    }

    private <T> T locked(String target, java.util.function.Supplier<T> action) {
        ReentrantLock lock = targetLocks.computeIfAbsent(target, t -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    private synchronized void send(ObjectNode message) {
        out.print(Json.compact(message));
        out.print('\n');
        out.flush();
    }

    private static ObjectNode error(JsonNode id, int code, String message, ObjectNode data) {
        ObjectNode response = Json.MAPPER.createObjectNode().put("jsonrpc", "2.0");
        response.set("id", id == null ? Json.MAPPER.nullNode() : id);
        ObjectNode error = response.putObject("error").put("code", code).put("message", message);
        if (data != null) {
            error.set("data", data);
        }
        return response;
    }

    private static String required(ObjectNode params, String name) {
        String value = optional(params, name);
        if (value == null) {
            throw new IllegalArgumentException("params." + name + " is required");
        }
        return value;
    }

    private static String optional(ObjectNode params, String name) {
        JsonNode value = params.get(name);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    private static ObjectNode object(ObjectNode params, String name) {
        JsonNode value = params.get(name);
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException("params." + name + " must be an object");
        }
        return (ObjectNode) value;
    }

    private static final class MethodNotFound extends RuntimeException {
        MethodNotFound(String method) {
            super("Unknown method '" + method + "'");
        }
    }
}
