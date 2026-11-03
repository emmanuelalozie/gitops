package gitops.providers;

import com.fasterxml.jackson.databind.node.ObjectNode;
import gitops.Plan.FieldDiff;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Any JSON API that manages a collection the usual way: {@code GET path} lists it as an array, {@code POST path}
 * creates, {@code PATCH} (or {@code PUT}) {@code path/{id}} updates and {@code DELETE path/{id}} deletes. Configured
 * in target.json with kind "rest" (see Target.restKind), so an inventory API, feature flags or similar need no
 * Java. A token is sent as a Bearer token only when the target names one in tokenEnv.
 */
public final class RestProvider implements Provider {

    private final Http http;
    private final String path;
    private final String idField;
    private final Http.Method updateMethod;

    public RestProvider(String baseUrl, String path, String idField, String updateMethod, String token) {
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("\"path\" must start with /, got '" + path + "'");
        }
        this.path = path.replaceAll("/+$", "");
        this.idField = idField;
        this.updateMethod = switch (updateMethod.toUpperCase(Locale.ROOT)) {
            case "PATCH" -> Http.Method.PATCH;
            case "PUT" -> Http.Method.PUT;
            default -> throw new IllegalArgumentException("\"updateMethod\" must be PATCH or PUT, got '" + updateMethod + "'");
        };
        Map<String, String> headers = new HashMap<>();
        if (token != null && !token.isBlank()) {
            headers.put("Authorization", "Bearer " + token);
        }
        this.http = new Http(baseUrl, headers);
    }

    @Override
    public List<ObjectNode> list() {
        return Http.objects(http.send(Http.Method.GET, path, null));
    }

    @Override
    public void create(ObjectNode desired) {
        http.send(Http.Method.POST, path, desired);
    }

    /** PATCH sends only the changed fields; PUT sends the whole resource as Git has it. */
    @Override
    public void update(ObjectNode live, ObjectNode desired, List<FieldDiff> diffs) {
        ObjectNode body = desired;
        if (updateMethod == Http.Method.PATCH) {
            body = desired.objectNode();
            for (FieldDiff diff : diffs) {
                body.set(diff.field(), diff.desired());
            }
        }
        http.send(updateMethod, path + "/{id}", body, id(live));
    }

    @Override
    public void delete(ObjectNode live) {
        http.send(Http.Method.DELETE, path + "/{id}", null, id(live));
    }

    /** The id is part of the URL, so changing it means deleting the resource and creating it again. */
    @Override
    public boolean requiresReplace(List<FieldDiff> diffs) {
        return diffs.stream().anyMatch(d -> d.field().equals(idField));
    }

    private String id(ObjectNode live) {
        if (!live.hasNonNull(idField)) {
            throw new IllegalStateException("The API returned a resource without \"" + idField + "\" (set \"idField\" in target.json): " + live);
        }
        return live.get(idField).asText();
    }
}
