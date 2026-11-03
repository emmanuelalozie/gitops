package gitops.providers;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gitops.Json;
import gitops.Plan.FieldDiff;

import java.util.ArrayList;
import java.util.List;

/**
 * Repository webhooks, keyed by URL: events, active and content type. Secrets aren't managed (GitHub never returns
 * them), and an update leaves an existing secret alone: fields go through PATCH /hooks/{id} and the config
 * endpoint, which change only what they're given. Needs an admin token.
 */
public final class GitHubWebhooksProvider extends GitHubProvider {

    public GitHubWebhooksProvider(String apiUrl, String repository, String token) {
        super(apiUrl, repository, token);
    }

    @Override
    public List<ObjectNode> list() {
        List<ObjectNode> hooks = new ArrayList<>();
        for (ObjectNode live : listAll("/hooks")) {
            if (!"web".equals(live.path("name").asText("web"))) {
                continue;
            }
            ObjectNode hook = Json.MAPPER.createObjectNode().put("url", live.path("config").path("url").asText());
            ArrayNode events = hook.putArray("events");
            live.path("events").forEach(events::add);
            hook.put("active", live.path("active").asBoolean(true));
            hook.put("content_type", live.path("config").path("content_type").asText("form"));
            hook.put("id", live.path("id").asLong()); // not managed; used to address the hook
            hooks.add(hook);
        }
        return hooks;
    }

    @Override
    public void create(ObjectNode desired) {
        ObjectNode body = Json.MAPPER.createObjectNode().put("name", "web").put("active", desired.path("active").asBoolean(true));
        body.set("events", desired.get("events"));
        body.putObject("config").put("url", desired.path("url").asText()).put("content_type", desired.path("content_type").asText("json"));
        http.send(Http.Method.POST, "/repos/{owner}/{repo}/hooks", body, owner, repo);
    }

    @Override
    public void update(ObjectNode live, ObjectNode desired, List<FieldDiff> diffs) {
        String id = live.path("id").asText();
        ObjectNode body = Json.MAPPER.createObjectNode();
        for (FieldDiff d : diffs) {
            if (d.field().equals("events") || d.field().equals("active")) {
                body.set(d.field(), d.desired());
            }
        }
        if (!body.isEmpty()) {
            http.send(Http.Method.PATCH, "/repos/{owner}/{repo}/hooks/{id}", body, owner, repo, id);
        }
        if (diffs.stream().anyMatch(d -> d.field().equals("content_type"))) {
            http.send(Http.Method.PATCH, "/repos/{owner}/{repo}/hooks/{id}/config",
                    Json.MAPPER.createObjectNode().put("content_type", desired.path("content_type").asText()), owner, repo, id);
        }
    }

    @Override
    public void delete(ObjectNode live) {
        http.send(Http.Method.DELETE, "/repos/{owner}/{repo}/hooks/{id}", null, owner, repo, live.path("id").asText());
    }
}
