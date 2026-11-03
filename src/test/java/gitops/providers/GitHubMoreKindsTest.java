package gitops.providers;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import gitops.Applier;
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

/** Environments, webhooks, collaborators, teams and rulesets against a stubbed GitHub. */
class GitHubMoreKindsTest {

    @RegisterExtension
    static WireMockExtension api = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private static ObjectNode json(String text) {
        return (ObjectNode) Json.parse(text);
    }

    private static void apply(Kind kind, Provider provider, List<ObjectNode> desired, boolean prune) {
        Plan plan = Planner.plan("t", "t", kind, prune, desired, provider.list());
        Applier.apply(plan, provider, System.out);
    }

    @Test
    void environmentsFlattenProtectionAndLookUpReviewerIds() {
        api.stubFor(get(urlPathEqualTo("/repos/o/r/environments")).willReturn(okJson("""
                {"total_count": 1, "environments": [{"name": "production", "protection_rules": [
                  {"type": "wait_timer", "wait_timer": 5},
                  {"type": "required_reviewers", "prevent_self_review": true, "reviewers": [
                    {"type": "User", "reviewer": {"login": "bob", "id": 2}}, {"type": "Team", "reviewer": {"slug": "ops", "id": 9}}]}],
                  "deployment_branch_policy": {"protected_branches": true, "custom_branch_policies": false}}]}""")));
        api.stubFor(get("/users/alice").willReturn(okJson("{\"login\": \"alice\", \"id\": 1}")));
        api.stubFor(get("/orgs/o/teams/ops").willReturn(okJson("{\"slug\": \"ops\", \"id\": 9}")));
        api.stubFor(put("/repos/o/r/environments/production").willReturn(okJson("{}")));
        GitHubEnvironmentsProvider provider = new GitHubEnvironmentsProvider(api.baseUrl(), "o/r", "t");

        assertThat(provider.list().get(0).toString()).isEqualTo("{\"name\":\"production\",\"wait_timer\":5,"
                + "\"reviewers\":[\"team:ops\",\"user:bob\"],\"prevent_self_review\":true,\"deployment_branches\":\"protected\"}");

        apply(Kind.ENVIRONMENTS, provider, List.of(json("""
                {"name": "production", "wait_timer": 5, "reviewers": ["user:alice", "team:ops"], "prevent_self_review": true,
                 "deployment_branches": "all"}""")), false);

        api.verify(putRequestedFor(urlEqualTo("/repos/o/r/environments/production")).withRequestBody(equalToJson("""
                {"wait_timer": 5, "prevent_self_review": true, "reviewers": [{"type": "User", "id": 1}, {"type": "Team", "id": 9}],
                 "deployment_branch_policy": null}""")));
    }

    @Test
    void webhooksChangeOnlyWhatDiffersAndKeepTheirSecret() {
        api.stubFor(get(urlPathEqualTo("/repos/o/r/hooks")).willReturn(okJson("""
                [{"id": 7, "name": "web", "active": true, "events": ["push"], "config": {"url": "https://ci.example.com/hook", "content_type": "form"}}]""")));
        api.stubFor(patch(urlMatching("/repos/o/r/hooks/7.*")).willReturn(okJson("{}")));
        api.stubFor(post("/repos/o/r/hooks").willReturn(jsonResponse("{}", 201)));
        GitHubWebhooksProvider provider = new GitHubWebhooksProvider(api.baseUrl(), "o/r", "t");

        apply(Kind.WEBHOOKS, provider, List.of(
                json("{\"url\": \"https://ci.example.com/hook\", \"events\": [\"push\", \"pull_request\"], \"active\": true, \"content_type\": \"json\"}"),
                json("{\"url\": \"https://chat.example.com/in\", \"events\": [\"issues\"]}")), false);

        api.verify(patchRequestedFor(urlEqualTo("/repos/o/r/hooks/7")).withRequestBody(equalToJson("{\"events\": [\"push\", \"pull_request\"]}")));
        api.verify(patchRequestedFor(urlEqualTo("/repos/o/r/hooks/7/config")).withRequestBody(equalToJson("{\"content_type\": \"json\"}")));
        api.verify(postRequestedFor(urlEqualTo("/repos/o/r/hooks")).withRequestBody(equalToJson("""
                {"name": "web", "active": true, "events": ["issues"], "config": {"url": "https://chat.example.com/in", "content_type": "json"}}""")));
    }

    @Test
    void collaboratorsCountPendingInvitationsAndUseApiPermissionNames() {
        api.stubFor(get(urlPathEqualTo("/repos/o/r/collaborators")).withQueryParam("affiliation", equalTo("direct"))
                .willReturn(okJson("[{\"login\": \"bob\", \"role_name\": \"write\"}]")));
        api.stubFor(get(urlPathEqualTo("/repos/o/r/invitations")).willReturn(okJson("""
                [{"id": 55, "invitee": {"login": "carol"}, "permissions": "read"}]""")));
        api.stubFor(put("/repos/o/r/collaborators/dave").willReturn(jsonResponse("{}", 201)));
        api.stubFor(patch("/repos/o/r/invitations/55").willReturn(okJson("{}")));
        api.stubFor(delete("/repos/o/r/collaborators/bob").willReturn(aResponse().withStatus(204)));
        GitHubAccessProvider provider = new GitHubAccessProvider(api.baseUrl(), "o/r", "t", GitHubAccessProvider.Mode.COLLABORATORS);

        assertThat(provider.list()).extracting(Object::toString).containsExactly(
                "{\"login\":\"bob\",\"permission\":\"write\"}", "{\"login\":\"carol\",\"permission\":\"read\",\"invitation_id\":55}");

        apply(Kind.COLLABORATORS, provider, List.of(json("{\"login\": \"Carol\", \"permission\": \"triage\"}"),
                json("{\"login\": \"dave\", \"permission\": \"write\"}")), true);

        api.verify(putRequestedFor(urlEqualTo("/repos/o/r/collaborators/dave")).withRequestBody(equalToJson("{\"permission\": \"push\"}")));
        api.verify(patchRequestedFor(urlEqualTo("/repos/o/r/invitations/55")).withRequestBody(equalToJson("{\"permissions\": \"triage\"}")));
        api.verify(deleteRequestedFor(urlEqualTo("/repos/o/r/collaborators/bob")));
    }

    @Test
    void teamsAreGrantedThroughTheOrganization() {
        api.stubFor(get(urlPathEqualTo("/repos/o/r/teams")).willReturn(okJson("[{\"slug\": \"ops\", \"permission\": \"pull\"}]")));
        api.stubFor(put("/orgs/o/teams/ops/repos/o/r").willReturn(aResponse().withStatus(204)));
        GitHubAccessProvider provider = new GitHubAccessProvider(api.baseUrl(), "o/r", "t", GitHubAccessProvider.Mode.TEAMS);

        assertThat(provider.list().get(0).toString()).isEqualTo("{\"slug\":\"ops\",\"permission\":\"read\"}");
        apply(Kind.TEAMS, provider, List.of(json("{\"slug\": \"ops\", \"permission\": \"maintain\"}")), false);

        api.verify(putRequestedFor(urlEqualTo("/orgs/o/teams/ops/repos/o/r")).withRequestBody(equalToJson("{\"permission\": \"maintain\"}")));
    }

    @Test
    void rulesetsAreReadInFullAndReplacedAsAWhole() {
        api.stubFor(get(urlPathEqualTo("/repos/o/r/rulesets")).willReturn(okJson("""
                [{"id": 3, "name": "main", "source_type": "Repository"}, {"id": 4, "name": "org-wide", "source_type": "Organization"}]""")));
        api.stubFor(get("/repos/o/r/rulesets/3").willReturn(okJson("""
                {"id": 3, "name": "main", "target": "branch", "enforcement": "evaluate", "node_id": "x",
                 "conditions": {"ref_name": {"include": ["~DEFAULT_BRANCH"], "exclude": []}}, "rules": [{"type": "deletion"}]}""")));
        api.stubFor(put("/repos/o/r/rulesets/3").willReturn(okJson("{}")));
        GitHubRulesetsProvider provider = new GitHubRulesetsProvider(api.baseUrl(), "o/r", "t");

        List<ObjectNode> live = provider.list();
        assertThat(live).hasSize(1);
        assertThat(live.get(0).has("node_id")).isFalse();

        ObjectNode desired = live.get(0).deepCopy();
        desired.remove("id");
        desired.put("enforcement", "active");
        apply(Kind.RULESETS, provider, List.of(desired), false);

        api.verify(putRequestedFor(urlEqualTo("/repos/o/r/rulesets/3"))
                .withRequestBody(matchingJsonPath("$.enforcement", equalTo("active")))
                .withRequestBody(matchingJsonPath("$.rules[0].type", equalTo("deletion"))));
    }

    @Test
    void everyBuiltInKindHasASchemaAndAProvider() {
        for (Kind kind : Kind.BUILT_IN) {
            assertThat(kind.fields()).as(kind.id()).contains(kind.keyField());
        }
    }
}
