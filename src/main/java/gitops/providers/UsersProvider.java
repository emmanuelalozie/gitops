package gitops.providers;

import com.fasterxml.jackson.databind.node.ObjectNode;
import gitops.Plan.FieldDiff;

import java.util.List;
import java.util.Map;

/**
 * A JSONPlaceholder-style users API (GET/POST /users, PATCH/DELETE /users/{id}).
 * Works with jsonplaceholder.typicode.com and with the json-server mock in mock/.
 */
public final class UsersProvider implements Provider {

    private final Http http;

    public UsersProvider(String baseUrl) {
        this.http = new Http(baseUrl, Map.of());
    }

    @Override
    public List<ObjectNode> list() {
        return Http.objects(http.send(Http.Method.GET, "/users", null));
    }

    @Override
    public void create(ObjectNode desired) {
        http.send(Http.Method.POST, "/users", desired);
    }

    @Override
    public void update(ObjectNode live, ObjectNode desired, List<FieldDiff> diffs) {
        ObjectNode patch = desired.objectNode();
        diffs.forEach(d -> patch.set(d.field(), d.desired()));
        http.send(Http.Method.PATCH, "/users/{id}", patch, live.path("id").asText());
    }

    @Override
    public void delete(ObjectNode live) {
        http.send(Http.Method.DELETE, "/users/{id}", null, live.path("id").asText());
    }

    /** A changed id cannot be patched in place, so the user is deleted and recreated under the new id. */
    @Override
    public boolean requiresReplace(List<FieldDiff> diffs) {
        return diffs.stream().anyMatch(d -> d.field().equals("id"));
    }
}
