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
}
