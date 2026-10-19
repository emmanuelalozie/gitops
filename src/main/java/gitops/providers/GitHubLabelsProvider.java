package gitops.providers;

import com.fasterxml.jackson.databind.node.ObjectNode;
import gitops.Plan.FieldDiff;
import io.restassured.http.Method;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Issue labels of a GitHub repository, via the REST API. Reads work without a token on public repos. */
public final class GitHubLabelsProvider implements Provider {

    static final int PAGE_SIZE = 100;

    private final Http http;
    private final String owner;
    private final String repo;

    public GitHubLabelsProvider(String apiUrl, String repository, String token) {
        String[] parts = repository.split("/");
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("repository must look like owner/name, got '" + repository + "'");
        }
        this.owner = parts[0];
        this.repo = parts[1];
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/vnd.github+json");
        headers.put("X-GitHub-Api-Version", "2022-11-28");
        if (token != null && !token.isBlank()) {
            headers.put("Authorization", "Bearer " + token);
        }
        this.http = new Http(apiUrl, headers);
    }

    @Override
    public List<ObjectNode> list() {
        List<ObjectNode> labels = new ArrayList<>();
        for (int page = 1; ; page++) {
            List<ObjectNode> batch = Http.objects(http.send(
                    http.spec().queryParam("per_page", PAGE_SIZE).queryParam("page", page),
                    Method.GET, "/repos/{owner}/{repo}/labels", null, owner, repo));
            labels.addAll(batch);
            if (batch.size() < PAGE_SIZE) {
                return labels;
            }
        }
    }

    @Override
    public void create(ObjectNode desired) {
        http.send(Method.POST, "/repos/{owner}/{repo}/labels", desired, owner, repo);
    }

    @Override
    public void update(ObjectNode live, ObjectNode desired, List<FieldDiff> diffs) {
        ObjectNode patch = desired.objectNode();
        diffs.forEach(d -> patch.set(d.field(), d.desired()));
        http.send(Method.PATCH, "/repos/{owner}/{repo}/labels/{name}", patch, owner, repo, live.path("name").asText());
    }

    @Override
    public void delete(ObjectNode live) {
        http.send(Method.DELETE, "/repos/{owner}/{repo}/labels/{name}", null, owner, repo, live.path("name").asText());
    }
}
