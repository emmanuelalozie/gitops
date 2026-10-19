package gitops.providers;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import gitops.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.stream.IntStream;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHubLabelsProviderTest {

    @RegisterExtension
    static WireMockExtension api = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private GitHubLabelsProvider provider(String token) {
        return new GitHubLabelsProvider(api.baseUrl(), "octo/repo", token);
    }

    @Test
    void followsPaginationAndSendsTheToken() {
        String fullPage = IntStream.range(0, GitHubProvider.PAGE_SIZE)
                .mapToObj(i -> "{\"name\": \"l" + i + "\", \"color\": \"ffffff\"}")
                .reduce((a, b) -> a + "," + b).map(s -> "[" + s + "]").orElseThrow();
        api.stubFor(get(urlPathEqualTo("/repos/octo/repo/labels")).withQueryParam("page", equalTo("1"))
                .willReturn(okJson(fullPage)));
        api.stubFor(get(urlPathEqualTo("/repos/octo/repo/labels")).withQueryParam("page", equalTo("2"))
                .willReturn(okJson("[{\"name\": \"last\", \"color\": \"000000\"}]")));

        assertThat(provider("secret").list()).hasSize(GitHubProvider.PAGE_SIZE + 1);
        api.verify(getRequestedFor(urlPathEqualTo("/repos/octo/repo/labels"))
                .withHeader("Authorization", equalTo("Bearer secret"))
                .withHeader("X-GitHub-Api-Version", equalTo("2022-11-28")));
    }

    @Test
    void encodesLabelNamesInThePath() {
        api.stubFor(patch(urlEqualTo("/repos/octo/repo/labels/good%20first%20issue")).willReturn(okJson("{}")));
        var live = Json.MAPPER.createObjectNode().put("name", "good first issue").put("color", "7057ff");
        var desired = live.deepCopy().put("color", "000000");

        provider(null).update(live, desired, List.of(new gitops.Plan.FieldDiff("color", live.get("color"), desired.get("color"))));

        api.verify(patchRequestedFor(urlEqualTo("/repos/octo/repo/labels/good%20first%20issue"))
                .withRequestBody(equalToJson("{\"color\": \"000000\"}"))
                .withoutHeader("Authorization"));
    }

    @Test
    void renamesThroughNewNameWhenOnlyTheCaseDiffers() {
        api.stubFor(patch(urlEqualTo("/repos/octo/repo/labels/Drift")).willReturn(okJson("{}")));
        var live = Json.MAPPER.createObjectNode().put("name", "Drift").put("color", "d93f0b");
        var desired = live.deepCopy().put("name", "drift");
        var plan = gitops.Planner.plan("t", "t", gitops.Kind.LABELS, false, List.of(desired), List.of(live));

        assertThat(plan.changes()).singleElement().satisfies(c -> assertThat(c.action()).isEqualTo(gitops.Plan.Action.UPDATE));
        provider(null).update(live, desired, plan.changes().get(0).diffs());

        api.verify(patchRequestedFor(urlEqualTo("/repos/octo/repo/labels/Drift"))
                .withRequestBody(equalToJson("{\"new_name\": \"drift\"}")));
    }

    @Test
    void reportsTheApiErrorForNamesWithDollarSigns() {
        api.stubFor(delete(urlEqualTo("/repos/octo/repo/labels/cost%241")).willReturn(jsonResponse("{\"message\": \"nope\"}", 422)));
        var live = Json.MAPPER.createObjectNode().put("name", "cost$1");

        assertThatThrownBy(() -> provider(null).delete(live))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("/repos/octo/repo/labels/cost$1").hasMessageContaining("422");
    }

    @Test
    void rejectsMalformedRepositoryNames() {
        assertThatThrownBy(() -> new GitHubLabelsProvider(api.baseUrl(), "just-a-name", null))
                .hasMessageContaining("owner/name");
    }
}
