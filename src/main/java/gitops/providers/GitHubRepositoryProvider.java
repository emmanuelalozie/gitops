package gitops.providers;

import com.fasterxml.jackson.databind.node.ObjectNode;
import gitops.Json;
import gitops.Plan.FieldDiff;
import io.restassured.http.Method;

import java.util.List;

/**
 * The settings of the repository itself (description, topics, merge options and so on). There is exactly
 * one resource, keyed by the repository's name, and it can be changed but never created or deleted.
 */
public final class GitHubRepositoryProvider extends GitHubProvider {

    static final List<String> FIELDS = List.of("name", "description", "homepage", "topics", "has_issues",
            "has_projects", "has_wiki", "allow_squash_merge", "allow_merge_commit", "allow_rebase_merge",
            "allow_auto_merge", "delete_branch_on_merge");

    public GitHubRepositoryProvider(String apiUrl, String repository, String token) {
        super(apiUrl, repository, token);
    }

    @Override
    public List<ObjectNode> list() {
        ObjectNode live = (ObjectNode) Json.parse(http.send(Method.GET, "/repos/{owner}/{repo}", null, owner, repo).asString());
        ObjectNode settings = Json.MAPPER.createObjectNode();
        for (String field : FIELDS) {
            settings.set(field, live.get(field));
        }
        // GitHub reports an unset description or homepage as null; the config uses "".
        for (String field : List.of("description", "homepage")) {
            if (settings.path(field).isNull()) {
                settings.put(field, "");
            }
        }
        return List.of(settings);
    }

    @Override
    public void create(ObjectNode desired) {
        throw new IllegalArgumentException("Repository settings can only be changed, not created: the config is for '"
                + desired.path("name").asText() + "' but the target repository is '" + repo + "'");
    }

    @Override
    public void update(ObjectNode live, ObjectNode desired, List<FieldDiff> diffs) {
        ObjectNode patch = desired.objectNode();
        diffs.stream().filter(d -> !d.field().equals("topics")).forEach(d -> patch.set(d.field(), d.desired()));
        if (!patch.isEmpty()) {
            http.send(Method.PATCH, "/repos/{owner}/{repo}", patch, owner, repo);
        }
        if (diffs.stream().anyMatch(d -> d.field().equals("topics"))) {
            http.send(Method.PUT, "/repos/{owner}/{repo}/topics", desired.objectNode().set("names", desired.get("topics")), owner, repo);
        }
    }

    @Override
    public void delete(ObjectNode live) {
        throw new IllegalArgumentException("Repository settings can't be deleted; turn prune off for this target");
    }
}
