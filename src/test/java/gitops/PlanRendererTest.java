package gitops;

import org.junit.jupiter.api.Test;

import java.util.List;

import static gitops.TestData.user;
import static org.assertj.core.api.Assertions.assertThat;

class PlanRendererTest {

    private final Plan plan = Planner.plan("prod", "users @ https://example.test", Kind.USERS, true,
            List.of(user(15, "Bret", "a@b.io"), user(3, "Samantha", "s@b.io")),
            List.of(user(1, "Bret", "a@b.io")));

    @Test
    void textShowsEachChangeAndASummary() {
        assertThat(PlanRenderer.text(plan))
                .contains("~ update Bret", "id: 1 -> 15", "+ create Samantha",
                        "Plan: 1 to create, 1 to update, 0 to delete.");
    }

    @Test
    void markdownRendersATableForPullRequestComments() {
        assertThat(PlanRenderer.markdown(plan))
                .contains("### Plan for `prod`", "| Action | Resource | Changes |", "| ~ update | `Bret` | `id`: 1 → 15 |");
    }

    @Test
    void saysSoWhenThereIsNothingToDo() {
        Plan empty = Planner.plan("prod", "users", Kind.USERS, true, List.of(), List.of());

        assertThat(PlanRenderer.text(empty)).contains("No changes.");
        assertThat(PlanRenderer.markdown(empty)).contains("No changes.");
    }
}
