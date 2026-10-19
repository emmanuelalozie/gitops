package gitops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gitops.Plan.Action;
import gitops.Plan.Change;
import gitops.Plan.FieldDiff;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compares desired state (Git) with live state and works out what has to change. */
public final class Planner {

    private Planner() {
    }

    public static Plan plan(Target target) {
        return plan(target.name(), target.describe(), target.kind(), target.prune(),
                target.store().load(), target.provider().list());
    }

    public static Plan plan(String name, String description, Kind kind, boolean prune,
                            List<ObjectNode> desired, List<ObjectNode> live) {
        Map<String, ObjectNode> liveByKey = index(live, kind);
        List<Change> changes = new ArrayList<>();
        List<String> unmanaged = new ArrayList<>();

        for (Map.Entry<String, ObjectNode> entry : index(desired, kind).entrySet()) {
            ObjectNode current = liveByKey.remove(entry.getKey());
            if (current == null) {
                changes.add(new Change(Action.CREATE, entry.getKey(), entry.getValue(), null, List.of()));
                continue;
            }
            List<FieldDiff> diffs = diff(entry.getValue(), current);
            if (!diffs.isEmpty()) {
                changes.add(new Change(Action.UPDATE, entry.getKey(), entry.getValue(), current, diffs));
            }
        }
        for (Map.Entry<String, ObjectNode> entry : liveByKey.entrySet()) {
            if (prune) {
                changes.add(new Change(Action.DELETE, entry.getKey(), null, entry.getValue(), List.of()));
            } else {
                unmanaged.add(entry.getKey());
            }
        }
        return new Plan(name, description, changes, unmanaged);
    }

    /** Only fields present in the desired resource are compared; extra live fields are ignored. */
    static List<FieldDiff> diff(ObjectNode desired, ObjectNode live) {
        List<FieldDiff> diffs = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> fields = desired.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode current = live.get(field.getKey());
            if (current == null || !current.equals(field.getValue())) {
                diffs.add(new FieldDiff(field.getKey(), current, field.getValue()));
            }
        }
        return diffs;
    }

    private static Map<String, ObjectNode> index(List<ObjectNode> resources, Kind kind) {
        Map<String, ObjectNode> byKey = new LinkedHashMap<>();
        for (ObjectNode resource : resources) {
            byKey.put(resource.path(kind.keyField()).asText(), resource);
        }
        return byKey;
    }
}
