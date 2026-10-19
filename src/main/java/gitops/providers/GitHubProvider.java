package gitops.providers;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.restassured.http.Method;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Shared setup for providers that manage part of one GitHub repository through the REST API. */
abstract class GitHubProvider implements Provider {

    static final int PAGE_SIZE = 100;

    final Http http;
    final String owner;
    final String repo;

    GitHubProvider(String apiUrl, String repository, String token) {
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

    /** Every item of a paginated list endpoint under /repos/{owner}/{repo}. */
    List<ObjectNode> listAll(String path) {
        List<ObjectNode> items = new ArrayList<>();
        for (int page = 1; ; page++) {
            List<ObjectNode> batch = Http.objects(http.send(
                    http.spec().queryParam("per_page", PAGE_SIZE).queryParam("page", page),
                    Method.GET, "/repos/{owner}/{repo}" + path, null, owner, repo));
            items.addAll(batch);
            if (batch.size() < PAGE_SIZE) {
                return items;
            }
        }
    }
}
