package gitops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gitops.ConfigValidator.Problem;
import gitops.Plan.Action;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Everything the engine can do, in one place, so the command line and the engine server ({@code serve}) run
 * exactly the same code. Targets are loaded fresh on every call, because the files under config/ can change
 * between calls (someone edits them, or switches branch).
 */
public final class Engine {

    /** The engine's version, from the jar manifest ("dev" when run from classes). */
    public static final String VERSION = Objects.requireNonNullElse(Engine.class.getPackage().getImplementationVersion(), "dev");

    private final Path configRoot;

    public Engine(Path configRoot) {
        this.configRoot = configRoot;
    }

    public Path configRoot() {
        return configRoot;
    }

    public static ObjectNode version() {
        return Json.MAPPER.createObjectNode()
                .put("engineVersion", VERSION)
                .put("schemaVersion", PlanJson.SCHEMA_VERSION)
                .put("java", Runtime.version().toString());
    }

    public List<Target> targets() {
        if (!Files.isDirectory(configRoot)) {
            throw new IllegalArgumentException("Config folder " + configRoot + " does not exist");
        }
        return Target.loadAll(configRoot);
    }

    public Target target(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("--target is required");
        }
        return Target.load(configRoot, name);
    }

    public ObjectNode targetsJson() {
        ObjectNode root = Json.MAPPER.createObjectNode().put("schemaVersion", PlanJson.SCHEMA_VERSION);
        ArrayNode list = root.putArray("targets");
        targets().forEach(t -> list.add(t.toJson()));
        return root;
    }

    /** Validates one target, or every target when {@code only} is null. */
    public ObjectNode validateJson(String only) {
        List<Target> targets = only == null ? targets() : List.of(target(only));
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("No targets found under " + configRoot);
        }
        ObjectNode root = Json.MAPPER.createObjectNode().put("schemaVersion", PlanJson.SCHEMA_VERSION);
        ArrayNode list = root.putArray("targets");
        boolean ok = true;
        for (Target target : targets) {
            List<Problem> problems = ConfigValidator.problems(target);
            ok &= problems.isEmpty();
            ObjectNode node = list.addObject()
                    .put("name", target.name())
                    .put("kind", target.kind().id())
                    .put("files", target.store().files().size())
                    .put("ok", problems.isEmpty());
            ArrayNode array = node.putArray("problems");
            problems.forEach(p -> array.add(p.toJson()));
        }
        root.put("ok", ok);
        return root;
    }

    public void requireValid(Target target) {
        List<Problem> problems = ConfigValidator.problems(target);
        if (!problems.isEmpty()) {
            throw new EngineError.InvalidConfig(target.name(), problems);
        }
    }

    public void requireToken(Target target) {
        if (target.missingRequiredToken()) {
            throw new EngineError.TokenMissing(target.name(), target.tokenVariable());
        }
    }

    /** Validates, then compares Git with the live system. */
    public Plan plan(Target target) {
        requireValid(target);
        return Planner.plan(target);
    }

    public ObjectNode planJson(Target target, Plan plan) {
        return PlanJson.toJson(plan, target.kind(), target.provider());
    }

    /**
     * The outcome of an apply.
     *
     * @param after    the plan made after applying when verification ran, otherwise null
     * @param verified true when verification ran and found nothing left to change
     */
    public record ApplyResult(Plan before, String planId, boolean applied, Plan after, boolean verified) {
    }

    /**
     * Validates, plans, checks the plan against {@code expectedPlanId} (when given), applies and verifies.
     * {@code onPlanned} sees the plan before anything changes, so it can be shown.
     */
    public ApplyResult apply(Target target, String expectedPlanId, boolean allowDeleteAll,
                             Consumer<Plan> onPlanned, Applier.Listener listener) {
        Plan plan = plan(target);
        String planId = PlanJson.id(plan, target.kind());
        onPlanned.accept(plan);
        if (expectedPlanId != null && !expectedPlanId.equals(planId)) {
            throw new EngineError.PlanChanged(target.name(), expectedPlanId, planId);
        }
        if (!plan.hasChanges()) {
            return new ApplyResult(plan, planId, false, null, true);
        }
        if (plan.count(Action.DELETE) > 0 && plan.count(Action.DELETE) == plan.changes().size()
                && target.store().files().isEmpty() && !allowDeleteAll) {
            throw new EngineError.NotAllowed("Refusing to delete every " + target.kind().id() + " in '" + target.name()
                    + "' because Git has none. If that's really what you want, pass --allow-delete-all.");
        }
        Applier.apply(plan, target.provider(), listener);
        if (!target.verifyAfterApply()) {
            return new ApplyResult(plan, planId, true, null, false);
        }
        Plan after = Planner.plan(target);
        return new ApplyResult(plan, planId, true, after, !after.hasChanges());
    }

    public ArrayNode fetch(Target target) {
        ArrayNode array = Json.MAPPER.createArrayNode();
        target.provider().list().forEach(array::add);
        return array;
    }

    /**
     * Compares the Git config of two targets of the same kind, as a plan that would make {@code to} look like
     * {@code from}: "create" is only in from, "update" differs, "delete" is only in to. Promotion uses it.
     */
    public ObjectNode compare(String fromName, String toName) {
        Target from = target(fromName);
        Target to = target(toName);
        if (!from.kind().equals(to.kind())) {
            throw new IllegalArgumentException("Can't compare '" + fromName + "' (" + from.kind().id() + ") with '"
                    + toName + "' (" + to.kind().id() + "): they manage different kinds");
        }
        Plan plan = Planner.plan(fromName + " -> " + toName, from.kind().id() + " in Git", from.kind(), true,
                from.store().load(), to.store().load());
        ObjectNode json = PlanJson.toJson(plan, from.kind(), null);
        json.put("from", fromName).put("to", toName);
        return json;
    }

    /** The resources in Git for a target, each with its key and file. Unreadable files come back with an error. */
    public ObjectNode resources(Target target) {
        ObjectNode root = Json.MAPPER.createObjectNode().put("schemaVersion", PlanJson.SCHEMA_VERSION)
                .put("target", target.name()).put("kind", target.kind().id()).put("keyField", target.kind().keyField());
        ArrayNode list = root.putArray("resources");
        for (Path file : target.store().files()) {
            ObjectNode item = list.addObject().put("file", file.toString());
            try {
                JsonNode resource = Json.read(file);
                item.put("key", resource.path(target.kind().keyField()).asText(""));
                item.set("resource", resource);
            } catch (IllegalArgumentException e) {
                item.put("key", "");
                item.putNull("resource");
                item.put("error", e.getMessage());
            }
        }
        return root;
    }

    /** The schema of a target's kind (a rest target has its own), or of a built-in kind by id. */
    public JsonNode schema(String targetName, String kindId) {
        return targetName != null ? target(targetName).kind().schema() : Kind.fromId(kindId).schema();
    }

    /**
     * Problems with one resource before it's written: its schema, a key that another file already has, and
     * unique fields used elsewhere. {@code previousKey} is the key it had before an edit (null for a new one).
     */
    public List<Problem> checkResource(Target target, ObjectNode resource, String previousKey) {
        Kind kind = target.kind();
        ConfigStore store = target.store();
        String key = resource.path(kind.keyField()).asText("");
        Path file = key.isEmpty() ? store.dir().resolve("(new).json") : store.file(key);
        List<Problem> problems = new ArrayList<>(ConfigValidator.schemaProblems(kind, file, resource));
        if (key.isEmpty()) {
            return problems;
        }
        Path own = previousKey == null ? null : store.file(previousKey);
        for (Path other : store.files()) {
            if (other.equals(own)) {
                continue;
            }
            if (other.equals(file)) {
                problems.add(new Problem(file, "/" + kind.keyField(), kind.keyField() + " '" + key
                        + "' is already used by " + other));
                continue;
            }
            JsonNode existing = Json.read(other);
            for (String field : kind.uniqueFields()) {
                JsonNode value = resource.get(field);
                if (value != null && value.equals(existing.get(field))) {
                    problems.add(new Problem(file, "/" + field, field + " " + value + " is already used by " + other));
                }
            }
        }
        return problems;
    }

    /** Writes a resource after checking it; renaming it removes the old file. Returns what changed on disk. */
    public ObjectNode writeResource(Target target, ObjectNode resource, String previousKey) {
        List<Problem> problems = checkResource(target, resource, previousKey);
        if (!problems.isEmpty()) {
            throw new EngineError.InvalidConfig(target.name(), problems);
        }
        ConfigStore store = target.store();
        Path file = store.write(resource);
        ObjectNode result = Json.MAPPER.createObjectNode().put("file", file.toString());
        ArrayNode removed = result.putArray("removed");
        if (previousKey != null && !store.file(previousKey).equals(file) && store.remove(previousKey)) {
            removed.add(store.file(previousKey).toString());
        }
        return result;
    }

    public ObjectNode deleteResource(Target target, String key) {
        ConfigStore store = target.store();
        boolean removed = store.remove(key);
        return Json.MAPPER.createObjectNode().put("file", store.file(key).toString()).put("removed", removed);
    }
}
