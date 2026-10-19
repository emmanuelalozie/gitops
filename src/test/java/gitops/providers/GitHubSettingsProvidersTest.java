package gitops.providers;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import gitops.Json;
import gitops.Kind;
import gitops.Plan;
import gitops.Planner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHubSettingsProvidersTest {

    @RegisterExtension
    static WireMockExtension api = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private static final String PROTECTION = """
            {
              "url": "https://api.github.com/repos/octo/repo/branches/develop/protection",
              "required_status_checks": {"strict": false, "contexts": ["b", "a"], "checks": []},
              "required_pull_request_reviews": {"dismiss_stale_reviews": true, "require_code_owner_reviews": false,
                                                "required_approving_review_count": 0},
              "enforce_admins": {"enabled": false},
              "required_linear_history": {"enabled": false},
              "allow_force_pushes": {"enabled": false},
              "allow_deletions": {"enabled": false},
              "required_conversation_resolution": {"enabled": true}
            }""";

    private static com.fasterxml.jackson.databind.node.ObjectNode rule(String... checks) {
        var rule = Json.MAPPER.createObjectNode().put("branch", "develop");
        var contexts = rule.putArray("required_status_checks");
        for (String check : checks) {
            contexts.add(check);
        }
        return rule.put("strict_status_checks", false).put("require_pull_request", true)
                .put("required_approving_review_count", 0).put("require_code_owner_reviews", false)
                .put("dismiss_stale_reviews", true).put("enforce_admins", false).put("required_linear_history", false)
                .put("allow_force_pushes", false).put("allow_deletions", false).put("required_conversation_resolution", true);
    }

    @Test
    void readsBranchProtectionAsAFlatRuleAndComparesChecksAsASet() {
        api.stubFor(get(urlPathEqualTo("/repos/octo/repo/branches")).withQueryParam("protected", equalTo("true"))
                .willReturn(okJson("[{\"name\": \"develop\"}]")));
        api.stubFor(get(urlEqualTo("/repos/octo/repo/branches/develop/protection")).willReturn(okJson(PROTECTION)));

        List<com.fasterxml.jackson.databind.node.ObjectNode> live =
                new GitHubBranchProtectionProvider(api.baseUrl(), "octo/repo", "t").list();

        assertThat(live).singleElement().satisfies(r -> assertThat(r.path("required_status_checks")).hasSize(2));
        Plan plan = Planner.plan("t", "t", Kind.BRANCH_PROTECTION, false, List.of(rule("a", "b")), live);
        assertThat(plan.hasChanges()).as("same checks in another order").isFalse();
    }

    @Test
    void writesTheWholeRuleInGitHubsShape() {
        api.stubFor(put(urlEqualTo("/repos/octo/repo/branches/develop/protection")).willReturn(okJson("{}")));

        new GitHubBranchProtectionProvider(api.baseUrl(), "octo/repo", "t").create(rule("Build"));

        api.verify(putRequestedFor(urlEqualTo("/repos/octo/repo/branches/develop/protection")).withRequestBody(equalToJson("""
                {
                  "required_status_checks": {"strict": false, "contexts": ["Build"]},
                  "required_pull_request_reviews": {"required_approving_review_count": 0,
                                                    "require_code_owner_reviews": false, "dismiss_stale_reviews": true},
                  "enforce_admins": false,
                  "restrictions": null,
                  "required_linear_history": false,
                  "allow_force_pushes": false,
                  "allow_deletions": false,
                  "required_conversation_resolution": true
                }""")));
    }

    @Test
    void flattenThenExpandKeepsTheRule() {
        var flat = GitHubBranchProtectionProvider.flatten("develop", Json.parse(PROTECTION));

        assertThat(GitHubBranchProtectionProvider.flatten("develop",
                toApiShape(GitHubBranchProtectionProvider.expand(flat)))).isEqualTo(flat);
    }

    /** GET wraps the booleans that PUT takes bare in {"enabled": ...}. */
    private static com.fasterxml.jackson.databind.JsonNode toApiShape(com.fasterxml.jackson.databind.node.ObjectNode body) {
        var shaped = body.deepCopy();
        for (String flag : List.of("enforce_admins", "required_linear_history", "allow_force_pushes",
                "allow_deletions", "required_conversation_resolution")) {
            shaped.putObject(flag).put("enabled", body.path(flag).asBoolean());
        }
        return shaped;
    }

    @Test
    void repositorySettingsPatchFieldsAndReplaceTopicsSeparately() {
        api.stubFor(get(urlEqualTo("/repos/octo/repo")).willReturn(okJson("""
                {"name": "repo", "description": null, "homepage": null, "topics": ["b", "a"], "has_wiki": true,
                 "delete_branch_on_merge": false, "stargazers_count": 3}""")));
        api.stubFor(patch(urlEqualTo("/repos/octo/repo")).willReturn(okJson("{}")));
        api.stubFor(put(urlEqualTo("/repos/octo/repo/topics")).willReturn(okJson("{}")));
        var provider = new GitHubRepositoryProvider(api.baseUrl(), "octo/repo", "t");
        var desired = Json.MAPPER.createObjectNode().put("name", "repo").put("description", "")
                .put("delete_branch_on_merge", true);
        desired.putArray("topics").add("a").add("c");

        var live = provider.list();
        assertThat(live.get(0).path("description").asText()).isEmpty();
        Plan plan = Planner.plan("t", "t", Kind.REPOSITORY, false, List.of(desired), live);
        assertThat(plan.changes().get(0).diffs()).extracting(Plan.FieldDiff::field)
                .containsExactly("delete_branch_on_merge", "topics");
        provider.update(live.get(0), desired, plan.changes().get(0).diffs());

        api.verify(patchRequestedFor(urlEqualTo("/repos/octo/repo"))
                .withRequestBody(equalToJson("{\"delete_branch_on_merge\": true}")));
        api.verify(putRequestedFor(urlEqualTo("/repos/octo/repo/topics"))
                .withRequestBody(equalToJson("{\"names\": [\"a\", \"c\"]}")));
    }

    @Test
    void repositorySettingsCanOnlyBeChanged() {
        var provider = new GitHubRepositoryProvider(api.baseUrl(), "octo/repo", "t");

        assertThatThrownBy(() -> provider.create(Json.MAPPER.createObjectNode().put("name", "other")))
                .hasMessageContaining("'other'").hasMessageContaining("'repo'");
    }

    @Test
    void usersApiSendsATokenWhenGivenOne() {
        api.stubFor(get("/users").willReturn(okJson("[]")));

        new UsersProvider(api.baseUrl(), "secret").list();

        api.verify(getRequestedFor(urlEqualTo("/users")).withHeader("Authorization", equalTo("Bearer secret")));
    }
}
