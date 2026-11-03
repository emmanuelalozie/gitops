package gitops.providers;

import com.fasterxml.jackson.databind.node.ObjectNode;
import gitops.Json;
import gitops.Plan.FieldDiff;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Who can reach a repository: direct collaborators (by login) or organization teams (by slug), each with a role
 * (read, triage, write, maintain or admin). Adding a collaborator sends an invitation; until it's accepted the
 * invitation counts as the collaborator, so a fresh apply verifies and doesn't show up as drift. Needs an admin token.
 */
public final class GitHubAccessProvider extends GitHubProvider {

    public enum Mode { COLLABORATORS, TEAMS }

    private final Mode mode;

    public GitHubAccessProvider(String apiUrl, String repository, String token, Mode mode) {
        super(apiUrl, repository, token);
        this.mode = mode;
    }

    @Override
    public List<ObjectNode> list() {
        List<ObjectNode> list = new ArrayList<>();
        if (mode == Mode.TEAMS) {
            for (ObjectNode team : listAll("/teams")) {
                list.add(Json.MAPPER.createObjectNode().put("slug", team.path("slug").asText())
                        .put("permission", roleName(team.path("permission").asText())));
            }
            return list;
        }
        for (ObjectNode person : listAll("/collaborators", Map.of("affiliation", "direct"))) {
            String role = person.path("role_name").asText("");
            list.add(Json.MAPPER.createObjectNode().put("login", person.path("login").asText())
                    .put("permission", role.isEmpty() ? roleName(highest(person)) : roleName(role)));
        }
        for (ObjectNode invitation : listAll("/invitations")) {
            list.add(Json.MAPPER.createObjectNode().put("login", invitation.path("invitee").path("login").asText())
                    .put("permission", roleName(invitation.path("permissions").asText()))
                    .put("invitation_id", invitation.path("id").asLong()));
        }
        return list;
    }

    private static String highest(ObjectNode person) {
        for (String p : List.of("admin", "maintain", "push", "triage", "pull")) {
            if (person.path("permissions").path(p).asBoolean(false)) {
                return p;
            }
        }
        return "pull";
    }

    @Override
    public void create(ObjectNode desired) {
        put(desired);
    }

    @Override
    public void update(ObjectNode live, ObjectNode desired, List<FieldDiff> diffs) {
        if (live.has("invitation_id")) {
            http.send(Http.Method.PATCH, "/repos/{owner}/{repo}/invitations/{id}",
                    Json.MAPPER.createObjectNode().put("permissions", desired.path("permission").asText()),
                    owner, repo, live.path("invitation_id").asText());
            return;
        }
        put(desired);
    }

    @Override
    public void delete(ObjectNode live) {
        if (mode == Mode.TEAMS) {
            http.send(Http.Method.DELETE, "/orgs/{org}/teams/{slug}/repos/{owner}/{repo}", null, owner, live.path("slug").asText(), owner, repo);
        } else if (live.has("invitation_id")) {
            http.send(Http.Method.DELETE, "/repos/{owner}/{repo}/invitations/{id}", null, owner, repo, live.path("invitation_id").asText());
        } else {
            http.send(Http.Method.DELETE, "/repos/{owner}/{repo}/collaborators/{login}", null, owner, repo, live.path("login").asText());
        }
    }

    private void put(ObjectNode desired) {
        ObjectNode body = Json.MAPPER.createObjectNode().put("permission", apiPermission(desired.path("permission").asText()));
        if (mode == Mode.TEAMS) {
            http.send(Http.Method.PUT, "/orgs/{org}/teams/{slug}/repos/{owner}/{repo}", body, owner, desired.path("slug").asText(), owner, repo);
        } else {
            http.send(Http.Method.PUT, "/repos/{owner}/{repo}/collaborators/{login}", body, owner, repo, desired.path("login").asText());
        }
    }
}
