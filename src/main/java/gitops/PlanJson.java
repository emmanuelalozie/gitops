package gitops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gitops.Plan.Action;
import gitops.Plan.Change;
import gitops.Plan.FieldDiff;
import gitops.providers.Provider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The machine-readable form of a plan (schema: src/main/resources/schemas/output/plan.v1.schema.json).
 *
 * <p>Every plan has an id: a hash of exactly what it would change. {@code apply --plan-id} refuses to run
 * when the plan it computes has a different id, so what someone approved is what runs. Only managed fields
 * go into the id, and lists are hashed as sets (as the planner compares them), so a change to a field Git
 * doesn't manage, or a reordered list, never makes an approved plan stale.
 */
public final class PlanJson {

    /** Bumped on any incompatible change to the JSON this class or the engine server writes. */
    public static final int SCHEMA_VERSION = 1;

    private PlanJson() {
    }

    public static ObjectNode toJson(Plan plan, Kind kind, Provider provider) {
        ObjectNode root = Json.MAPPER.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("target", plan.target());
        root.put("description", plan.description());
        root.put("kind", kind.id());
        root.put("keyField", kind.keyField());
        root.put("skipped", false);
        root.put("planId", id(plan, kind));
        root.put("hasChanges", plan.hasChanges());
        ObjectNode summary = root.putObject("summary");
        for (Action action : Action.values()) {
            summary.put(action.name().toLowerCase(Locale.ROOT), (int) plan.count(action));
        }
        ArrayNode changes = root.putArray("changes");
        for (Change change : plan.changes()) {
            ObjectNode node = changes.addObject();
            node.put("action", change.action().name().toLowerCase(Locale.ROOT));
            node.put("key", change.key());
            node.put("replace", change.action() == Action.UPDATE && provider != null
                    && provider.requiresReplace(change.diffs()));
            node.set("desired", change.desired() == null ? Json.MAPPER.nullNode() : managed(change.desired(), kind));
            node.set("live", change.live() == null ? Json.MAPPER.nullNode() : managed(change.live(), kind));
            ArrayNode diffs = node.putArray("diffs");
            for (FieldDiff diff : change.diffs()) {
                ObjectNode d = diffs.addObject().put("field", diff.field());
                d.set("live", diff.live() == null ? Json.MAPPER.nullNode() : diff.live());
                d.put("liveMissing", diff.live() == null);
                d.set("desired", diff.desired());
            }
        }
        ArrayNode unmanaged = root.putArray("unmanaged");
        plan.unmanaged().forEach(unmanaged::add);
        return root;
    }

    /** What a plan reports for a target that was skipped because its token isn't set. */
    public static ObjectNode skipped(Target target, String reason) {
        ObjectNode root = Json.MAPPER.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("target", target.name());
        root.put("description", target.describe());
        root.put("kind", target.kind().id());
        root.put("keyField", target.kind().keyField());
        root.put("skipped", true);
        root.put("skipReason", reason);
        root.putNull("planId");
        root.put("hasChanges", false);
        root.putObject("summary").put("create", 0).put("update", 0).put("delete", 0);
        root.putArray("changes");
        root.putArray("unmanaged");
        return root;
    }

    /** sha256 over the canonical form of the changes (managed fields only) and the unmanaged keys. */
    public static String id(Plan plan, Kind kind) {
        StringBuilder canonical = new StringBuilder();
        canonical.append("target=").append(plan.target()).append('\n');
        for (Change change : plan.changes()) {
            canonical.append(change.action()).append(' ').append(change.key()).append('\n');
            canonical.append(" desired=").append(change.desired() == null ? "null" : canonical(managed(change.desired(), kind))).append('\n');
            canonical.append(" live=").append(change.live() == null ? "null" : canonical(managed(change.live(), kind))).append('\n');
        }
        List<String> unmanaged = new ArrayList<>(plan.unmanaged());
        unmanaged.sort(null);
        canonical.append("unmanaged=").append(unmanaged);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Only the fields the schema declares, in schema order. */
    static ObjectNode managed(ObjectNode resource, Kind kind) {
        ObjectNode managed = Json.MAPPER.createObjectNode();
        for (String field : kind.fields()) {
            if (resource.has(field)) {
                managed.set(field, resource.get(field));
            }
        }
        return managed;
    }

    /** JSON text with object keys sorted and array items sorted, so equal content always hashes the same. */
    static String canonical(JsonNode node) {
        if (node.isObject()) {
            Map<String, String> fields = new TreeMap<>();
            node.properties().forEach(e -> fields.put(e.getKey(), canonical(e.getValue())));
            StringBuilder out = new StringBuilder("{");
            fields.forEach((k, v) -> out.append(Json.MAPPER.getNodeFactory().textNode(k)).append(':').append(v).append(','));
            return out.append('}').toString();
        }
        if (node.isArray()) {
            List<String> items = new ArrayList<>();
            node.forEach(item -> items.add(canonical(item)));
            items.sort(null);
            return "[" + String.join(",", items) + "]";
        }
        return node.toString();
    }
}
