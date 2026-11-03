package gitops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Command-line entry point. Usually run through the Gradle tasks in build.gradle.
 *
 * <p>Exit codes: 0 success, 1 error or invalid config, 2 drift found with --fail-on-drift,
 * 3 the plan changed since it was approved (apply --plan-id).
 */
public final class Main {

    static final int OK = 0;
    static final int ERROR = 1;
    static final int DRIFT = 2;
    static final int PLAN_CHANGED = 3;

    private static final Set<String> FLAGS = Set.of("--fail-on-drift", "--allow-delete-all", "--skip-without-token", "--respect-window");
    private static final Set<String> FORMATS = Set.of("text", "markdown", "json");
    private static final String USAGE = """
            Usage: gitops <command> [options]
              validate [--target NAME]                      validate config against the schemas
              targets [--format json --out FILE]            list the targets and their settings
              lint                                          checks across files, such as required status
                                                            checks that no workflow job reports (warnings)
              plan --target NAME [--fail-on-drift]          show what apply would change
                   [--format text|markdown|json] [--out FILE] [--json-out FILE]
              apply --target NAME [--allow-delete-all]      make the live system match Git
                    [--plan-id ID]                          only if the plan is still the one with this id
                    [--respect-window]                      defer targets outside their applyWindow
            plan and apply also take --skip-without-token: succeed without doing anything when the
            target requires a token (tokenRequired in target.json) and its variable isn't set
              snapshot --target NAME                        write live state into Git
              fetch --target NAME                           print live state
              search --query TEXT                           find resources in Git across every target
              codeowners                                    write target owners into .github/CODEOWNERS
              version                                       print the engine and JSON schema versions
              serve                                         answer JSON-RPC on stdin/stdout (desktop app)
            Common: --config-dir DIR (default: config), --format json for machine-readable output and errors""";

    private final PrintStream out;
    private final PrintStream err;

    Main(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("serve")) {
            System.exit(EngineServer.main(args));
        }
        System.exit(new Main(System.out, System.err).run(args));
    }

    int run(String[] args) {
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("help")) {
            out.println(USAGE);
            return args.length == 0 ? ERROR : OK;
        }
        Map<String, String> options = Map.of();
        try {
            options = parse(args);
            String format = options.getOrDefault("--format", "text");
            if (!FORMATS.contains(format)) {
                throw new IllegalArgumentException("Unknown format '" + format + "', expected text, markdown or json");
            }
            Engine engine = new Engine(Path.of(options.getOrDefault("--config-dir", "config")));
            return switch (args[0]) {
                case "validate" -> validate(engine, options.get("--target"), format, options.get("--out"));
                case "targets" -> targets(engine, format, options.get("--out"));
                case "lint" -> lint(engine, format, options.get("--out"));
                case "plan" -> plan(engine, engine.target(options.get("--target")), options, format);
                case "apply" -> apply(engine, engine.target(options.get("--target")), options, format);
                case "snapshot" -> snapshot(engine.target(options.get("--target")));
                case "fetch" -> fetch(engine, engine.target(options.get("--target")));
                case "version" -> version(format);
                case "search" -> search(engine, options.get("--query"), format);
                case "codeowners" -> codeowners(engine);
                default -> throw new IllegalArgumentException("Unknown command '" + args[0] + "'\n" + USAGE);
            };
        } catch (Exception e) {
            if ("json".equals(options.get("--format"))) {
                err.println(Json.compact(Json.MAPPER.createObjectNode().set("error", EngineError.toJson(e))));
            } else {
                err.println("error: " + EngineError.message(e));
            }
            return e instanceof EngineError.PlanChanged ? PLAN_CHANGED : ERROR;
        }
    }

    private int validate(Engine engine, String only, String format, String outFile) {
        ObjectNode result = engine.validateJson(only);
        // Warnings don't fail validation: a required check can come from outside this repository.
        JsonNode warnings = only == null ? Lint.run(engine).path("findings") : Json.MAPPER.createArrayNode();
        if (format.equals("json")) {
            result.set("warnings", warnings);
            json(result, outFile);
            return result.path("ok").asBoolean() ? OK : ERROR;
        }
        for (JsonNode target : result.path("targets")) {
            out.println((target.path("ok").asBoolean() ? "ok    " : "FAIL  ") + target.path("name").asText() + " ("
                    + target.path("files").asInt() + " " + target.path("kind").asText() + ")");
        }
        for (JsonNode target : result.path("targets")) {
            for (JsonNode problem : target.path("problems")) {
                String file = problem.path("file").isNull() ? null : problem.path("file").asText();
                err.println("  " + (file == null ? "" : file + ": ") + problem.path("message").asText());
            }
        }
        warnings.forEach(w -> err.println("warning: " + w.path("file").asText() + ": " + w.path("message").asText()));
        return result.path("ok").asBoolean() ? OK : ERROR;
    }

    private int lint(Engine engine, String format, String outFile) {
        ObjectNode result = Lint.run(engine);
        if (format.equals("json")) {
            json(result, outFile);
            return OK;
        }
        for (JsonNode requirement : result.path("requirements")) {
            List<String> by = new java.util.ArrayList<>();
            requirement.path("satisfiedBy").forEach(s -> by.add(s.path("file").asText() + " > " + s.path("job").asText()));
            out.println((by.isEmpty() ? "MISSING " : "ok      ") + requirement.path("target").asText() + " "
                    + requirement.path("branch").asText() + " requires '" + requirement.path("check").asText() + "'"
                    + (by.isEmpty() ? "" : " <- " + String.join(", ", by)));
        }
        result.path("findings").forEach(w -> err.println("warning: " + w.path("file").asText() + ": " + w.path("message").asText()));
        return OK;
    }

    private int targets(Engine engine, String format, String outFile) {
        ObjectNode result = engine.targetsJson();
        if (format.equals("json")) {
            json(result, outFile);
            return OK;
        }
        for (JsonNode t : result.path("targets")) {
            out.printf("%-18s %-18s %-40s prune=%s localApply=%s workflows=%s%n", t.path("name").asText(),
                    t.path("kind").asText(), t.path("endpoint").asText(""), t.path("prune").asBoolean(),
                    t.path("localApply").asBoolean(), t.path("workflows").asBoolean());
        }
        return OK;
    }

    /**
     * A target whose required token is missing can't be planned. With --skip-without-token that is
     * reported and skipped, so CI without the secret still passes; without it, it's an error.
     */
    private boolean skipped(Target target, Map<String, String> options, String format) {
        if (!target.missingRequiredToken()) {
            return false;
        }
        String reason = "'" + target.name() + "' needs a token in " + target.tokenVariable();
        if (!options.containsKey("--skip-without-token")) {
            throw new EngineError.TokenMissing(target.name(), target.tokenVariable());
        }
        String note = "Skipped " + reason + ", which isn't set.";
        ObjectNode json = PlanJson.skipped(target, note);
        out.println(format.equals("json") ? json.toPrettyString() : note);
        if ("true".equals(System.getenv("GITHUB_ACTIONS"))) {
            (format.equals("json") ? err : out).println("::notice::" + note);
        }
        if (options.containsKey("--out")) {
            write(Path.of(options.get("--out")), format.equals("json") ? json.toPrettyString() + "\n"
                    : "### Plan for `" + target.name() + "` (" + target.describe() + ")\n\n" + note + "\n");
        }
        if (options.containsKey("--json-out")) {
            write(Path.of(options.get("--json-out")), json.toPrettyString() + "\n");
        }
        return true;
    }

    private int plan(Engine engine, Target target, Map<String, String> options, String format) {
        if (skipped(target, options, format)) {
            return OK;
        }
        Plan plan = engine.plan(target);
        String rendered = format.equals("json") ? engine.planJson(target, plan).toPrettyString() + "\n"
                : PlanRenderer.render(plan, format);
        out.print(rendered);
        if (options.containsKey("--out")) {
            write(Path.of(options.get("--out")), rendered);
        }
        if (options.containsKey("--json-out")) {
            write(Path.of(options.get("--json-out")), engine.planJson(target, plan).toPrettyString() + "\n");
        }
        return plan.hasChanges() && options.containsKey("--fail-on-drift") ? DRIFT : OK;
    }

    private int apply(Engine engine, Target target, Map<String, String> options, String format) {
        if (skipped(target, options, format)) {
            return OK;
        }
        boolean json = format.equals("json");
        // With JSON output, stdout carries only the result; progress goes to stderr.
        PrintStream log = json ? err : out;
        Engine.ApplyResult result = engine.apply(target, options.get("--plan-id"), options.containsKey("--allow-delete-all"),
                options.containsKey("--respect-window"), plan -> log.print(PlanRenderer.text(plan)), Applier.printing(log));
        if (json) {
            ObjectNode node = Json.MAPPER.createObjectNode()
                    .put("schemaVersion", PlanJson.SCHEMA_VERSION)
                    .put("target", target.name())
                    .put("planId", result.planId())
                    .put("applied", result.applied())
                    .put("verified", result.verified());
            node.set("after", result.after() == null ? Json.MAPPER.nullNode() : engine.planJson(target, result.after()));
            node.set("deferred", result.deferred() == null ? Json.MAPPER.nullNode() : Json.MAPPER.getNodeFactory().textNode(result.deferred()));
            node.set("health", result.health() == null ? Json.MAPPER.nullNode() : result.health());
            out.println(node.toPrettyString());
        }
        if (result.deferred() != null) {
            log.println("Deferred: " + result.deferred());
            if ("true".equals(System.getenv("GITHUB_ACTIONS"))) {
                log.println("::notice::Deferred: " + result.deferred());
            }
            return OK;
        }
        if (!result.applied()) {
            return OK;
        }
        if (result.after() == null) {
            log.println("Applied. Skipping verification (verifyAfterApply is false for '" + target.name() + "').");
        } else if (!result.verified()) {
            err.println("Apply did not converge. Remaining drift:");
            err.print(PlanRenderer.text(result.after()));
            return ERROR;
        } else {
            log.println("Applied and verified: live state matches Git.");
        }
        if (result.health() != null) {
            boolean healthy = result.health().path("ok").asBoolean();
            (healthy ? log : err).println("Health check " + (healthy ? "passed" : "FAILED") + ": " + result.health().path("url").asText()
                    + " " + result.health().path("message").asText());
            return healthy ? OK : ERROR;
        }
        return OK;
    }

    private int snapshot(Target target) {
        ObjectNode result = new Engine(target.dir().getParent()).snapshot(target);
        out.println("Wrote " + result.path("written").asInt() + " " + target.kind().id() + " to " + target.resourceDir());
        return OK;
    }

    private int fetch(Engine engine, Target target) {
        out.println(engine.fetch(target).toPrettyString());
        return OK;
    }

    private int search(Engine engine, String query, String format) {
        if (query == null) {
            throw new IllegalArgumentException("search needs --query TEXT");
        }
        ObjectNode result = engine.search(query, 200);
        if (format.equals("json")) {
            out.println(result.toPrettyString());
            return OK;
        }
        for (JsonNode hit : result.path("results")) {
            List<String> fields = new java.util.ArrayList<>();
            hit.path("fields").forEach(f -> fields.add(f.asText()));
            out.println(hit.path("target").asText() + "  " + hit.path("key").asText() + "  (" + String.join(", ", fields) + ")  " + hit.path("file").asText());
        }
        out.println(result.path("results").size() + " found" + (result.path("truncated").asBoolean() ? " (first 200)" : ""));
        return OK;
    }

    private int codeowners(Engine engine) {
        Path repo = engine.configRoot().toAbsolutePath().normalize().getParent();
        boolean changed = CodeOwners.write(repo, engine.targets());
        out.println(changed ? "Updated " + CodeOwners.file(repo) : CodeOwners.file(repo) + " is up to date");
        return OK;
    }

    private int version(String format) {
        ObjectNode version = Engine.version();
        out.println(format.equals("json") ? version.toPrettyString()
                : "gitops engine " + version.path("engineVersion").asText() + " (JSON schema v"
                + version.path("schemaVersion").asInt() + ", Java " + version.path("java").asText() + ")");
        return OK;
    }

    /** JSON to stdout, and to a file when --out is given (workflows read the file, so build output can't mix in). */
    private void json(ObjectNode result, String outFile) {
        out.println(result.toPrettyString());
        if (outFile != null) {
            write(Path.of(outFile), result.toPrettyString() + "\n");
        }
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("Unexpected argument '" + arg + "'");
            }
            if (FLAGS.contains(arg)) {
                options.put(arg, "true");
            } else if (i + 1 < args.length) {
                options.put(arg, args[++i]);
            } else {
                throw new IllegalArgumentException(arg + " needs a value");
            }
        }
        return options;
    }

    private static void write(Path file, String text) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
