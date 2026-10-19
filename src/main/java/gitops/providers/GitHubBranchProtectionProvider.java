package gitops.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gitops.Json;
import gitops.Plan.FieldDiff;
import io.restassured.http.Method;

import java.util.ArrayList;
import java.util.List;

/**
 * Branch protection rules. GitHub's nested API shape is flattened to one object per branch, so a rule
 * reads like a checklist. Reading a rule needs a token with admin rights on the repository.
 */
public final class GitHubBranchProtectionProvider extends GitHubProvider {

    public GitHubBranchProtectionProvider(String apiUrl, String repository, String token) {
        super(apiUrl, repository, token);
    }

    @Override
    public List<ObjectNode> list() {
        List<ObjectNode> rules = new ArrayList<>();
        for (ObjectNode branch : listAll("/branches?protected=true")) {
            String name = branch.path("name").asText();
            JsonNode live = Json.parse(http.send(Method.GET, "/repos/{owner}/{repo}/branches/{branch}/protection",
                    null, owner, repo, name).asString());
            rules.add(flatten(name, live));
        }
        return rules;
    }

    @Override
    public void create(ObjectNode desired) {
        put(desired);
    }

    /** GitHub only replaces a whole rule, so every update sends all of it. */
    @Override
    public void update(ObjectNode live, ObjectNode desired, List<FieldDiff> diffs) {
        put(desired);
    }

    @Override
    public void delete(ObjectNode live) {
        http.send(Method.DELETE, "/repos/{owner}/{repo}/branches/{branch}/protection", null,
                owner, repo, live.path("branch").asText());
    }

    private void put(ObjectNode desired) {
        http.send(Method.PUT, "/repos/{owner}/{repo}/branches/{branch}/protection", expand(desired),
                owner, repo, desired.path("branch").asText());
    }

    static ObjectNode flatten(String branch, JsonNode live) {
        ObjectNode rule = Json.MAPPER.createObjectNode().put("branch", branch);
        JsonNode checks = live.path("required_status_checks");
        ArrayNode contexts = rule.putArray("required_status_checks");
        checks.path("contexts").forEach(contexts::add);
        rule.put("strict_status_checks", checks.path("strict").asBoolean(false));
        JsonNode reviews = live.path("required_pull_request_reviews");
        rule.put("require_pull_request", !reviews.isMissingNode());
        rule.put("required_approving_review_count", reviews.path("required_approving_review_count").asInt(0));
        rule.put("require_code_owner_reviews", reviews.path("require_code_owner_reviews").asBoolean(false));
        rule.put("dismiss_stale_reviews", reviews.path("dismiss_stale_reviews").asBoolean(false));
        for (String flag : List.of("enforce_admins", "required_linear_history", "allow_force_pushes",
                "allow_deletions", "required_conversation_resolution")) {
            rule.put(flag, live.path(flag).path("enabled").asBoolean(false));
        }
        return rule;
    }

    static ObjectNode expand(ObjectNode rule) {
        ObjectNode body = Json.MAPPER.createObjectNode();
        ArrayNode contexts = (ArrayNode) rule.path("required_status_checks");
        if (contexts.isEmpty()) {
            body.putNull("required_status_checks");
        } else {
            body.putObject("required_status_checks")
                    .put("strict", rule.path("strict_status_checks").asBoolean(false))
                    .set("contexts", contexts);
        }
        if (rule.path("require_pull_request").asBoolean(false)) {
            body.putObject("required_pull_request_reviews")
                    .put("required_approving_review_count", rule.path("required_approving_review_count").asInt(0))
                    .put("require_code_owner_reviews", rule.path("require_code_owner_reviews").asBoolean(false))
                    .put("dismiss_stale_reviews", rule.path("dismiss_stale_reviews").asBoolean(false));
        } else {
            body.putNull("required_pull_request_reviews");
        }
        body.put("enforce_admins", rule.path("enforce_admins").asBoolean(false));
        body.putNull("restrictions");
        for (String flag : List.of("required_linear_history", "allow_force_pushes", "allow_deletions",
                "required_conversation_resolution")) {
            body.put(flag, rule.path(flag).asBoolean(false));
        }
        return body;
    }
}
