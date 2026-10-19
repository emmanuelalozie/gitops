package gitops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * The changes needed to make a target's live state match Git.
 *
 * @param unmanaged keys that exist live but not in Git, on targets where prune is off
 */
public record Plan(String target, String description, List<Change> changes, List<String> unmanaged) {

    public enum Action { CREATE, UPDATE, DELETE }

    public record FieldDiff(String field, JsonNode live, JsonNode desired) {
    }

    /** One change. {@code live} is null for CREATE, {@code desired} is null for DELETE. */
    public record Change(Action action, String key, ObjectNode desired, ObjectNode live, List<FieldDiff> diffs) {
    }

    public boolean hasChanges() {
        return !changes.isEmpty();
    }

    public long count(Action action) {
        return changes.stream().filter(c -> c.action() == action).count();
    }
}
