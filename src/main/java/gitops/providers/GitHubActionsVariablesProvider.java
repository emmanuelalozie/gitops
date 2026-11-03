package gitops.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gitops.Json;
import gitops.Plan.FieldDiff;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * GitHub Actions variables of a repository (Settings > Secrets and variables > Actions > Variables): the plain
 * configuration workflows read as {@code vars.NAME}. Reading and changing them needs a token with access to the
 * repository's variables (a fine-grained token with "Variables: Read and write"). Secrets aren't managed: GitHub
 * never returns their values, so there would be nothing to compare.
 */
public final class GitHubActionsVariablesProvider extends GitHubProvider {

    /** The most GitHub returns per page for this endpoint. */
    static final int VARIABLES_PAGE = 30;

    public GitHubActionsVariablesProvider(String apiUrl, String repository, String token) {
        super(apiUrl, repository, token);
    }

    @Override
    public List<ObjectNode> list() {
        List<ObjectNode> variables = new ArrayList<>();
        for (int page = 1; ; page++) {
            JsonNode body = http.send(Http.Method.GET, "/repos/{owner}/{repo}/actions/variables",
                    Map.of("per_page", VARIABLES_PAGE, "page", page), null, owner, repo).json();
            JsonNode batch = body.path("variables");
            for (JsonNode variable : batch) {
                variables.add(Json.MAPPER.createObjectNode()
                        .put("name", variable.path("name").asText())
                        .put("value", variable.path("value").asText()));
            }
            if (batch.size() < VARIABLES_PAGE) {
                return variables;
            }
        }
    }

    @Override
    public void create(ObjectNode desired) {
        http.send(Http.Method.POST, "/repos/{owner}/{repo}/actions/variables", body(desired), owner, repo);
    }

    @Override
    public void update(ObjectNode live, ObjectNode desired, List<FieldDiff> diffs) {
        http.send(Http.Method.PATCH, "/repos/{owner}/{repo}/actions/variables/{name}", body(desired),
                owner, repo, live.path("name").asText());
    }

    @Override
    public void delete(ObjectNode live) {
        http.send(Http.Method.DELETE, "/repos/{owner}/{repo}/actions/variables/{name}", null,
                owner, repo, live.path("name").asText());
    }

    private static ObjectNode body(ObjectNode desired) {
        return Json.MAPPER.createObjectNode().put("name", desired.path("name").asText()).put("value", desired.path("value").asText());
    }
}
