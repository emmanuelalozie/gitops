package gitops;

import com.fasterxml.jackson.databind.node.ObjectNode;
import gitops.Plan.Action;
import org.junit.jupiter.api.Test;

import java.util.List;

import static gitops.TestData.user;
import static org.assertj.core.api.Assertions.assertThat;

class PlannerTest {

    private static Plan plan(boolean prune, List<ObjectNode> desired, List<ObjectNode> live) {
        return Planner.plan("test", "users @ test", Kind.USERS, prune, desired, live);
    }

    @Test
    void noChangesWhenLiveMatchesGit() {
        Plan plan = plan(true, List.of(user(1, "Bret", "a@b.io")), List.of(user(1, "Bret", "a@b.io")));

        assertThat(plan.hasChanges()).isFalse();
    }

    @Test
    void ignoresLiveFieldsThatGitDoesNotManage() {
        ObjectNode live = user(1, "Bret", "a@b.io");
        live.put("website", "hildegard.org").putObject("address").put("city", "Gwenborough");

        assertThat(plan(true, List.of(user(1, "Bret", "a@b.io")), List.of(live)).hasChanges()).isFalse();
    }

    @Test
    void reportsEveryChangedFieldNotJustTheFirst() {
        Plan plan = plan(true, List.of(user(15, "Bret", "new@b.io")), List.of(user(1, "Bret", "old@b.io")));

        assertThat(plan.changes()).singleElement().satisfies(change -> {
            assertThat(change.action()).isEqualTo(Action.UPDATE);
            assertThat(change.diffs()).extracting(Plan.FieldDiff::field).containsExactly("id", "email");
        });
    }

    @Test
    void createsResourcesMissingLiveAndDeletesOnesMissingFromGit() {
        Plan plan = plan(true, List.of(user(1, "Bret", "a@b.io")), List.of(user(2, "Antonette", "c@d.io")));

        assertThat(plan.changes()).extracting(Plan.Change::action, Plan.Change::key)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(Action.CREATE, "Bret"),
                        org.assertj.core.groups.Tuple.tuple(Action.DELETE, "Antonette"));
    }

    @Test
    void reportsExtraLiveResourcesAsUnmanagedWhenPruneIsOff() {
        Plan plan = plan(false, List.of(), List.of(user(2, "Antonette", "c@d.io")));

        assertThat(plan.hasChanges()).isFalse();
        assertThat(plan.unmanaged()).containsExactly("Antonette");
    }
}
