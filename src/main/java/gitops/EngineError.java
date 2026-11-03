package gitops;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gitops.providers.ApiException;
import gitops.providers.UnreachableException;

import java.util.List;

/**
 * An error in the form programs can act on: a stable {@code code}, a message for people, and details.
 * The CLI prints it as JSON with {@code --format json}, and the engine server returns it as JSON-RPC error data.
 */
public final class EngineError {

    /** The config doesn't pass validation. Details: {@code problems}. */
    public static final String INVALID_CONFIG = "invalid_config";
    /** The live system couldn't be reached. Details: {@code method}, {@code url}. */
    public static final String UNREACHABLE = "unreachable";
    /** The live system answered with an error status. Details: {@code method}, {@code url}, {@code status}, {@code body}. */
    public static final String API = "api";
    /** {@code apply --plan-id} found that the plan is no longer the one that was approved. Details: {@code expected}, {@code actual}. */
    public static final String PLAN_CHANGED = "plan_changed";
    /** The target needs a token that isn't set. Details: {@code variable}. */
    public static final String TOKEN_MISSING = "token_missing";
    /** The request isn't allowed here, such as applying to a target without localApply from the desktop app. */
    public static final String NOT_ALLOWED = "not_allowed";
    /** Wrong command, option or parameter. */
    public static final String USAGE = "usage";
    /** Anything else. */
    public static final String INTERNAL = "internal";

    private EngineError() {
    }

    /** Thrown for config that fails validation; carries every problem. */
    public static final class InvalidConfig extends IllegalArgumentException {
        private final List<ConfigValidator.Problem> problems;

        public InvalidConfig(String target, List<ConfigValidator.Problem> problems) {
            super("Config for '" + target + "' is invalid:\n  "
                    + String.join("\n  ", problems.stream().map(ConfigValidator.Problem::toString).toList()));
            this.problems = List.copyOf(problems);
        }

        public List<ConfigValidator.Problem> problems() {
            return problems;
        }
    }

    /** Thrown when an approved plan id no longer matches what the engine would do. */
    public static final class PlanChanged extends IllegalStateException {
        private final String expected;
        private final String actual;

        public PlanChanged(String target, String expected, String actual) {
            super("The plan for '" + target + "' changed since it was approved (expected " + shortId(expected)
                    + ", now " + shortId(actual) + "). Nothing was applied: review the new plan and approve it again.");
            this.expected = expected;
            this.actual = actual;
        }
    }

    /** Thrown when a target needs a token that isn't set. */
    public static final class TokenMissing extends IllegalArgumentException {
        private final String variable;

        public TokenMissing(String target, String variable) {
            super("'" + target + "' needs a token in " + variable + ".");
            this.variable = variable;
        }
    }

    /** Thrown for a request that is valid but not permitted. */
    public static final class NotAllowed extends IllegalStateException {
        public NotAllowed(String message) {
            super(message);
        }
    }

    public static String code(Throwable e) {
        return switch (e) {
            case InvalidConfig ignored -> INVALID_CONFIG;
            case PlanChanged ignored -> PLAN_CHANGED;
            case TokenMissing ignored -> TOKEN_MISSING;
            case NotAllowed ignored -> NOT_ALLOWED;
            case UnreachableException ignored -> UNREACHABLE;
            case ApiException ignored -> API;
            case IllegalArgumentException ignored -> USAGE;
            default -> INTERNAL;
        };
    }

    public static String message(Throwable e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    public static ObjectNode toJson(Throwable e) {
        ObjectNode error = Json.MAPPER.createObjectNode();
        error.put("code", code(e));
        error.put("message", message(e));
        ObjectNode details = error.putObject("details");
        switch (e) {
            case InvalidConfig invalid -> {
                ArrayNode problems = details.putArray("problems");
                invalid.problems().forEach(p -> problems.add(p.toJson()));
            }
            case PlanChanged changed -> details.put("expected", changed.expected).put("actual", changed.actual);
            case TokenMissing missing -> details.put("variable", missing.variable);
            case UnreachableException unreachable -> details.put("method", unreachable.method()).put("url", unreachable.url());
            case ApiException api -> details.put("method", api.method()).put("url", api.url())
                    .put("status", api.status()).put("body", api.body());
            default -> {
            }
        }
        return error;
    }

    static String shortId(String id) {
        return id == null ? "none" : id.substring(0, Math.min(12, id.length()));
    }
}
