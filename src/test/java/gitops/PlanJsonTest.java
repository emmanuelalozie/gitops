package gitops;

import com.fasterxml.jackson.databind.node.ObjectNode;
import gitops.providers.UsersProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static gitops.TestData.label;
import static gitops.TestData.user;
import static org.assertj.core.api.Assertions.assertThat;

class PlanJsonTest {

    private static Plan users(List<ObjectNode> desired, List<ObjectNode> live) {
        return Planner.plan("dev", "users @ x", Kind.USERS, true, desired, live);
    }

    @Test
    void describesEveryChangeForPrograms() {
        Plan plan = users(List.of(user(1, "Bret", "new@b.io"), user(2, "Ann", "a@b.io")),
                List.of(user(1, "Bret", "old@b.io"), user(9, "Gone", "g@b.io")));

        ObjectNode json = PlanJson.toJson(plan, Kind.USERS, new UsersProvider("http://unused"));

        assertThat(json.path("schemaVersion").asInt()).isEqualTo(PlanJson.SCHEMA_VERSION);
        assertThat(json.path("summary").toString()).isEqualTo("{\"create\":1,\"update\":1,\"delete\":1}");
        assertThat(json.path("planId").asText()).hasSize(64);
        ObjectNode update = (ObjectNode) json.path("changes").get(0);
        assertThat(update.path("action").asText()).isEqualTo("update");
        assertThat(update.path("replace").asBoolean()).isFalse();
        assertThat(update.path("diffs").get(0).path("field").asText()).isEqualTo("email");
        assertThat(update.path("diffs").get(0).path("live").asText()).isEqualTo("old@b.io");
        assertThat(json.path("changes").get(2).path("desired").isNull()).isTrue();
    }

    @Test
    void marksUpdatesThatReplaceTheResource() {
        Plan plan = users(List.of(user(2, "Bret", "a@b.io")), List.of(user(1, "Bret", "a@b.io")));

        ObjectNode json = PlanJson.toJson(plan, Kind.USERS, new UsersProvider("http://unused"));

        assertThat(json.path("changes").get(0).path("replace").asBoolean()).isTrue();
    }

    @Test
    void theIdIgnoresFieldsGitDoesNotManageAndTheOrderOfLiveData() {
        ObjectNode live = user(1, "Bret", "old@b.io");
        ObjectNode liveWithExtras = Json.MAPPER.createObjectNode().put("website", "x.org").put("phone", "555-0100")
                .put("email", "old@b.io").put("username", "Bret").put("id", 1);
        List<ObjectNode> desired = List.of(user(1, "Bret", "new@b.io"));

        assertThat(PlanJson.id(users(desired, List.of(live)), Kind.USERS))
                .isEqualTo(PlanJson.id(users(desired, List.of(liveWithExtras)), Kind.USERS));
    }

    @Test
    void theIdTreatsListsAsSets() {
        ObjectNode a = Json.MAPPER.createObjectNode().put("name", "r");
        a.putArray("topics").add("x").add("y");
        ObjectNode b = Json.MAPPER.createObjectNode().put("name", "r");
        b.putArray("topics").add("y").add("x");

        assertThat(PlanJson.canonical(a)).isEqualTo(PlanJson.canonical(b));
    }

    @Test
    void theIdChangesWhenWhatWouldBeAppliedChanges() {
        List<ObjectNode> live = List.of(user(1, "Bret", "old@b.io"));

        assertThat(PlanJson.id(users(List.of(user(1, "Bret", "new@b.io")), live), Kind.USERS))
                .isNotEqualTo(PlanJson.id(users(List.of(user(1, "Bret", "other@b.io")), live), Kind.USERS));
        assertThat(PlanJson.id(users(List.of(user(1, "Bret", "new@b.io")), live), Kind.USERS))
                .isNotEqualTo(PlanJson.id(users(List.of(user(1, "Bret", "new@b.io")), List.of(user(1, "Bret", "x@b.io"))), Kind.USERS));
    }

    @Test
    void unmanagedResourcesAreListed() {
        Plan plan = Planner.plan("github", "labels", Kind.LABELS, false, List.of(), List.of(label("bug", "d73a4a")));

        ObjectNode json = PlanJson.toJson(plan, Kind.LABELS, null);

        assertThat(json.path("hasChanges").asBoolean()).isFalse();
        assertThat(json.path("unmanaged").get(0).asText()).isEqualTo("bug");
    }
}
