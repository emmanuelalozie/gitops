package gitops.providers;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
        return listAll(path, Map.of());
    }

    List<ObjectNode> listAll(String path, Map<String, ?> query) {
        List<ObjectNode> items = new ArrayList<>();
        for (int page = 1; ; page++) {
            Map<String, Object> pageQuery = new LinkedHashMap<>(query);
            pageQuery.put("per_page", PAGE_SIZE);
            pageQuery.put("page", page);
            List<ObjectNode> batch = Http.objects(http.send(Http.Method.GET, "/repos/{owner}/{repo}" + path,
                    pageQuery, null, owner, repo));
            items.addAll(batch);
            if (batch.size() < PAGE_SIZE) {
                return items;
            }
        }
    }

    /** Every item of a paginated endpoint that wraps its list in an object, such as {"environments": [...]}. */
    List<ObjectNode> listAllIn(String path, String field, int pageSize) {
        List<ObjectNode> items = new ArrayList<>();
        for (int page = 1; ; page++) {
            Map<String, Object> query = new LinkedHashMap<>();
            query.put("per_page", pageSize);
            query.put("page", page);
            com.fasterxml.jackson.databind.JsonNode batch = http.send(Http.Method.GET, "/repos/{owner}/{repo}" + path, query, null, owner, repo)
                    .json().path(field);
            batch.forEach(n -> items.add((ObjectNode) n));
            if (batch.size() < pageSize) {
                return items;
            }
        }
    }

    /** GitHub's permission names in the API, from the role names people see (read, triage, write, maintain, admin). */
    static String apiPermission(String role) {
        return switch (role) {
            case "read" -> "pull";
            case "write" -> "push";
            default -> role;
        };
    }

    /** The role name for a permission as the API reports it. */
    static String roleName(String permission) {
        return switch (permission) {
            case "pull" -> "read";
            case "push" -> "write";
            default -> permission;
        };
    }
}
