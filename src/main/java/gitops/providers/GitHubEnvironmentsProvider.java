package gitops.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gitops.Json;
import gitops.Plan.FieldDiff;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deployment environments and their protection: wait timer, required reviewers and which branches may deploy.
 * GitHub's nested protection rules are flattened, and reviewers are written as user:&lt;login&gt; or
 * team:&lt;slug&gt; (GitHub wants numeric ids, which are looked up). Needs an admin token.
 */
public final class GitHubEnvironmentsProvider extends GitHubProvider {

    private final Map<String, Long> ids = new ConcurrentHashMap<>();

    public GitHubEnvironmentsProvider(String apiUrl, String repository, String token) {
        super(apiUrl, repository, token);
    }

    @Override
    public List<ObjectNode> list() {
        List<ObjectNode> environments = new ArrayList<>();
        for (ObjectNode live : listAllIn("/environments", "environments", 100)) {
            environments.add(flatten(live));
        }
        return environments;
    }

    static ObjectNode flatten(JsonNode live) {
        ObjectNode env = Json.MAPPER.createObjectNode().put("name", live.path("name").asText());
        int wait = 0;
        boolean preventSelfReview = false;
        List<String> reviewers = new ArrayList<>();
        for (JsonNode rule : live.path("protection_rules")) {
            switch (rule.path("type").asText()) {
                case "wait_timer" -> wait = rule.path("wait_timer").asInt(0);
                case "required_reviewers" -> {
                    preventSelfReview = rule.path("prevent_self_review").asBoolean(false);
                    for (JsonNode r : rule.path("reviewers")) {
                        boolean team = "Team".equals(r.path("type").asText());
                        reviewers.add(team ? "team:" + r.path("reviewer").path("slug").asText() : "user:" + r.path("reviewer").path("login").asText());
                    }
                }
                default -> {
                }
            }
        }
        env.put("wait_timer", wait);
        ArrayNode list = env.putArray("reviewers");
        reviewers.stream().sorted().forEach(list::add);
        env.put("prevent_self_review", preventSelfReview);
        JsonNode policy = live.path("deployment_branch_policy");
        env.put("deployment_branches", policy.isObject() && policy.path("protected_branches").asBoolean(false) ? "protected" : "all");
        return env;
    }

    @Override
    public void create(ObjectNode desired) {
        put(desired);
    }

    /** GitHub replaces an environment's protection as a whole, so every update sends all of it. */
    @Override
    public void update(ObjectNode live, ObjectNode desired, List<FieldDiff> diffs) {
        put(desired);
    }

    @Override
    public void delete(ObjectNode live) {
        http.send(Http.Method.DELETE, "/repos/{owner}/{repo}/environments/{name}", null, owner, repo, live.path("name").asText());
    }

    private void put(ObjectNode desired) {
        ObjectNode body = Json.MAPPER.createObjectNode();
        body.put("wait_timer", desired.path("wait_timer").asInt(0));
        body.put("prevent_self_review", desired.path("prevent_self_review").asBoolean(false));
        ArrayNode reviewers = body.putArray("reviewers");
        for (JsonNode r : desired.path("reviewers")) {
            String text = r.asText();
            boolean team = text.startsWith("team:");
            reviewers.addObject().put("type", team ? "Team" : "User").put("id", id(text));
        }
        if ("protected".equals(desired.path("deployment_branches").asText("all"))) {
            body.putObject("deployment_branch_policy").put("protected_branches", true).put("custom_branch_policies", false);
        } else {
            body.putNull("deployment_branch_policy");
        }
        http.send(Http.Method.PUT, "/repos/{owner}/{repo}/environments/{name}", body, owner, repo, desired.path("name").asText());
    }

    /** The numeric id GitHub wants for user:login or team:slug (a team of the repository's organization). */
    private long id(String reviewer) {
        return ids.computeIfAbsent(reviewer, r -> {
            String name = r.substring(r.indexOf(':') + 1);
            JsonNode found = r.startsWith("team:")
                    ? http.send(Http.Method.GET, "/orgs/{org}/teams/{slug}", null, owner, name).json()
                    : http.send(Http.Method.GET, "/users/{login}", null, name).json();
            return found.path("id").asLong();
        });
    }
}
