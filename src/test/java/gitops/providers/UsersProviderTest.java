package gitops.providers;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import gitops.Applier;
import gitops.Kind;
import gitops.Plan;
import gitops.Planner;
import gitops.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UsersProviderTest {

    @RegisterExtension
    static WireMockExtension api = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private UsersProvider provider() {
        return new UsersProvider(api.baseUrl());
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode user(int id, String username, String email) {
        return Json.MAPPER.createObjectNode().put("id", id).put("username", username).put("email", email).put("phone", "1");
    }

    @Test
    void listsUsers() {
        api.stubFor(get("/users").willReturn(okJson("""
                [{"id": 1, "username": "Bret", "email": "a@b.io", "phone": "1", "website": "hildegard.org"}]""")));

        assertThat(provider().list()).singleElement()
                .satisfies(u -> assertThat(u.path("username").asText()).isEqualTo("Bret"));
    }

    @Test
    void patchesOnlyTheChangedFields() {
        api.stubFor(patch(urlEqualTo("/users/1")).willReturn(okJson("{}")));
        Plan.Change change = Planner.plan("t", "t", Kind.USERS, true,
                List.of(user(1, "Bret", "new@b.io")), List.of(user(1, "Bret", "old@b.io"))).changes().get(0);

        provider().update(change.live(), change.desired(), change.diffs());

        api.verify(patchRequestedFor(urlEqualTo("/users/1")).withRequestBody(equalToJson("{\"email\": \"new@b.io\"}")));
    }

    @Test
    void swapsIdsBetweenUsersWithoutACollision() {
        api.stubFor(delete(urlMatching("/users/.*")).willReturn(okJson("{}")));
        api.stubFor(post("/users").willReturn(jsonResponse("{}", 201)));
        Plan plan = Planner.plan("t", "t", Kind.USERS, true,
                List.of(user(2, "Bret", "a@b.io"), user(1, "Antonette", "c@d.io")),
                List.of(user(1, "Bret", "a@b.io"), user(2, "Antonette", "c@d.io")));

        Applier.apply(plan, provider(), System.out);

        // Both old ids are freed before either user is created under its new one.
        List<String> calls = api.getAllServeEvents().reversed().stream()
                .map(e -> e.getRequest().getMethod() + " " + e.getRequest().getUrl()).toList();
        assertThat(calls).containsExactly("DELETE /users/1", "DELETE /users/2", "POST /users", "POST /users");
        api.verify(0, patchRequestedFor(anyUrl()));
    }

    @Test
    void failsLoudlyOnErrorResponses() {
        api.stubFor(get("/users").willReturn(serverError().withBody("boom")));

        assertThatThrownBy(() -> provider().list())
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("GET").hasMessageContaining("/users").hasMessageContaining("500").hasMessageContaining("boom");
    }
}
