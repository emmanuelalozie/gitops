package gitops.providers;

import com.fasterxml.jackson.databind.node.ObjectNode;
import gitops.Plan.FieldDiff;
import io.restassured.http.Method;

import java.util.List;

/** Issue labels of a GitHub repository, via the REST API. Reads work without a token on public repos. */
public final class GitHubLabelsProvider extends GitHubProvider {

    public GitHubLabelsProvider(String apiUrl, String repository, String token) {
        super(apiUrl, repository, token);
    }

    @Override
    public List<ObjectNode> list() {
        return listAll("/labels");
    }

    @Override
    public void create(ObjectNode desired) {
        http.send(Method.POST, "/repos/{owner}/{repo}/labels", desired, owner, repo);
    }

    @Override
    public void update(ObjectNode live, ObjectNode desired, List<FieldDiff> diffs) {
        ObjectNode patch = desired.objectNode();
        // Label names match case-insensitively, so a name diff is a change of case, which GitHub calls new_name.
        diffs.forEach(d -> patch.set(d.field().equals("name") ? "new_name" : d.field(), d.desired()));
        http.send(Method.PATCH, "/repos/{owner}/{repo}/labels/{name}", patch, owner, repo, live.path("name").asText());
    }

    @Override
    public void delete(ObjectNode live) {
        http.send(Method.DELETE, "/repos/{owner}/{repo}/labels/{name}", null, owner, repo, live.path("name").asText());
    }
}
