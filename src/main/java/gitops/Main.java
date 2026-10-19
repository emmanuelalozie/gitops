package gitops;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Command-line entry point. Usually run through the Gradle tasks in build.gradle.
 *
 * <p>Exit codes: 0 success, 1 error or invalid config, 2 drift found with --fail-on-drift.
 */
public final class Main {

    static final int OK = 0;
    static final int ERROR = 1;
    static final int DRIFT = 2;

    private static final Set<String> FLAGS = Set.of("--fail-on-drift", "--allow-delete-all", "--skip-without-token");
    private static final String USAGE = """
            Usage: gitops <command> [options]
              validate [--target NAME]                      validate config against the schemas
              plan --target NAME [--fail-on-drift]          show what apply would change
                   [--format text|markdown] [--out FILE]
              apply --target NAME [--allow-delete-all]      make the live system match Git
            plan and apply also take --skip-without-token: succeed without doing anything when the
            target requires a token (tokenRequired in target.json) and its variable isn't set
              snapshot --target NAME                        write live state into Git
              fetch --target NAME                           print live state
            Common: --config-dir DIR (default: config)""";

    private final PrintStream out;
    private final PrintStream err;

    Main(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    public static void main(String[] args) {
        System.exit(new Main(System.out, System.err).run(args));
    }

    int run(String[] args) {
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("help")) {
            out.println(USAGE);
            return args.length == 0 ? ERROR : OK;
        }
        try {
            Map<String, String> options = parse(args);
            Path configRoot = Path.of(options.getOrDefault("--config-dir", "config"));
            return switch (args[0]) {
                case "validate" -> validate(configRoot, options.get("--target"));
                case "plan" -> skipped(load(configRoot, options, "--target"), options) ? OK
                        : plan(load(configRoot, options, "--target"), options);
                case "apply" -> skipped(load(configRoot, options, "--target"), options) ? OK
                        : apply(load(configRoot, options, "--target"), options.containsKey("--allow-delete-all"));
                case "snapshot" -> snapshot(load(configRoot, options, "--target"));
                case "fetch" -> fetch(load(configRoot, options, "--target"));
                default -> throw new IllegalArgumentException("Unknown command '" + args[0] + "'\n" + USAGE);
            };
        } catch (Exception e) {
            // RESTAssured rethrows connection errors unchecked, so they arrive here as checked exceptions.
            if (e instanceof java.net.ConnectException) {
                err.println("error: can't connect to the live system (" + e.getMessage() + "). Is it running?");
            } else {
                err.println("error: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            }
            return ERROR;
        }
    }

    private int validate(Path configRoot, String only) {
        List<Target> targets = only == null ? Target.loadAll(configRoot) : List.of(Target.load(configRoot, only));
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("No targets found under " + configRoot);
        }
        List<String> errors = new ArrayList<>();
        for (Target target : targets) {
            List<String> targetErrors = ConfigValidator.validate(target);
            out.println((targetErrors.isEmpty() ? "ok    " : "FAIL  ") + target.name() + " ("
                    + target.store().files().size() + " " + target.kind().id() + ")");
            errors.addAll(targetErrors);
        }
        errors.forEach(e -> err.println("  " + e));
        return errors.isEmpty() ? OK : ERROR;
    }

    /**
     * A target whose required token is missing can't be planned. With --skip-without-token that is
     * reported and skipped, so CI without the secret still passes; without it, it's an error.
     */
    private boolean skipped(Target target, Map<String, String> options) {
        if (!target.missingRequiredToken()) {
            return false;
        }
        String reason = "'" + target.name() + "' needs a token in " + target.tokenVariable();
        if (!options.containsKey("--skip-without-token")) {
            throw new IllegalArgumentException(reason + ".");
        }
        String note = "Skipped " + reason + ", which isn't set.";
        out.println(note);
        if ("true".equals(System.getenv("GITHUB_ACTIONS"))) {
            out.println("::notice::" + note);
        }
        if (options.containsKey("--out")) {
            write(Path.of(options.get("--out")), "### Plan for `" + target.name() + "` (" + target.describe() + ")\n\n" + note + "\n");
        }
        return true;
    }

    private int plan(Target target, Map<String, String> options) {
        requireValid(target);
        Plan plan = Planner.plan(target);
        String rendered = PlanRenderer.render(plan, options.getOrDefault("--format", "text"));
        out.print(rendered);
        if (options.containsKey("--out")) {
            write(Path.of(options.get("--out")), rendered);
        }
        return plan.hasChanges() && options.containsKey("--fail-on-drift") ? DRIFT : OK;
    }

    private int apply(Target target, boolean allowDeleteAll) {
        requireValid(target);
        Plan plan = Planner.plan(target);
        out.print(PlanRenderer.text(plan));
        if (!plan.hasChanges()) {
            return OK;
        }
        if (plan.count(Plan.Action.DELETE) > 0 && plan.count(Plan.Action.DELETE) == plan.changes().size()
                && target.store().files().isEmpty() && !allowDeleteAll) {
            throw new IllegalArgumentException("Refusing to delete every " + target.kind().id() + " in '" + target.name()
                    + "' because Git has none. If that's really what you want, pass --allow-delete-all.");
        }
        Applier.apply(plan, target.provider(), out);
        if (!target.verifyAfterApply()) {
            out.println("Applied. Skipping verification (verifyAfterApply is false for '" + target.name() + "').");
            return OK;
        }
        Plan after = Planner.plan(target);
        if (after.hasChanges()) {
            err.println("Apply did not converge. Remaining drift:");
            err.print(PlanRenderer.text(after));
            return ERROR;
        }
        out.println("Applied and verified: live state matches Git.");
        return OK;
    }

    private int snapshot(Target target) {
        List<ObjectNode> live = target.provider().list();
        target.store().replaceAll(live);
        out.println("Wrote " + live.size() + " " + target.kind().id() + " to " + target.resourceDir());
        return OK;
    }

    private int fetch(Target target) {
        ArrayNode array = Json.MAPPER.createArrayNode();
        target.provider().list().forEach(array::add);
        out.println(array.toPrettyString());
        return OK;
    }

    private void requireValid(Target target) {
        List<String> errors = ConfigValidator.validate(target);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Config for '" + target.name() + "' is invalid:\n  " + String.join("\n  ", errors));
        }
    }

    private static Target load(Path configRoot, Map<String, String> options, String option) {
        String name = options.get(option);
        if (name == null) {
            throw new IllegalArgumentException(option + " is required");
        }
        return Target.load(configRoot, name);
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
