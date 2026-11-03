package gitops.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gitops.Json;
import gitops.Plan.FieldDiff;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Repository rulesets, the newer form of branch and tag protection, keyed by name and kept in GitHub's own shape:
 * target, enforcement, conditions, rules and bypass actors. Rulesets inherited from the organization are left out.
 * Needs an admin token.
 */
public final class GitHubRulesetsProvider extends GitHubProvider {

    static final List<String> FIELDS = List.of("target", "enforcement", "conditions", "rules", "bypass_actors");

    public GitHubRulesetsProvider(String apiUrl, String repository, String token) {
        super(apiUrl, repository, token);
    }

    @Override
    public List<ObjectNode> list() {
        List<ObjectNode> rulesets = new ArrayList<>();
        for (ObjectNode summary : listAll("/rulesets", Map.of("includes_parents", "false"))) {
            if (!"Repository".equals(summary.path("source_type").asText("Repository"))) {
                continue;
            }
            JsonNode full = http.send(Http.Method.GET, "/repos/{owner}/{repo}/rulesets/{id}", null, owner, repo,
                    summary.path("id").asText()).json();
            ObjectNode ruleset = Json.MAPPER.createObjectNode().put("name", full.path("name").asText());
            for (String field : FIELDS) {
                if (full.hasNonNull(field)) {
                    ruleset.set(field, full.get(field));
                }
            }
            ruleset.put("id", summary.path("id").asLong()); // not managed; used to address the ruleset
            rulesets.add(ruleset);
        }
        return rulesets;
    }

    @Override
    public void create(ObjectNode desired) {
        http.send(Http.Method.POST, "/repos/{owner}/{repo}/rulesets", body(desired), owner, repo);
    }

    /** A ruleset is replaced as a whole. */
    @Override
    public void update(ObjectNode live, ObjectNode desired, List<FieldDiff> diffs) {
        http.send(Http.Method.PUT, "/repos/{owner}/{repo}/rulesets/{id}", body(desired), owner, repo, live.path("id").asText());
    }

    @Override
    public void delete(ObjectNode live) {
        http.send(Http.Method.DELETE, "/repos/{owner}/{repo}/rulesets/{id}", null, owner, repo, live.path("id").asText());
    }

    private static ObjectNode body(ObjectNode desired) {
        ObjectNode body = Json.MAPPER.createObjectNode().put("name", desired.path("name").asText());
        body.put("target", desired.path("target").asText("branch"));
        for (String field : FIELDS) {
            if (desired.hasNonNull(field)) {
                body.set(field, desired.get(field));
            }
        }
        return body;
    }
}
