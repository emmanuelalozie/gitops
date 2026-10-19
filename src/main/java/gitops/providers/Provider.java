package gitops.providers;

import com.fasterxml.jackson.databind.node.ObjectNode;
import gitops.Plan.FieldDiff;

import java.util.List;

/** Reads and changes resources in one live system. */
public interface Provider {

    List<ObjectNode> list();

    void create(ObjectNode desired);

    void update(ObjectNode live, ObjectNode desired, List<FieldDiff> diffs);

    void delete(ObjectNode live);

    /**
     * Whether these changes can't be made in place, so the resource has to be deleted and created again.
     * The applier then deletes it with the other deletes and creates it with the other creates.
     */
    default boolean requiresReplace(List<FieldDiff> diffs) {
        return false;
    }
}
