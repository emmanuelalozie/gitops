package gitops.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gitops.Plan.FieldDiff;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Any JSON API that manages a collection the usual way: {@code GET path} lists it, {@code POST path} creates,
 * {@code PATCH} (or {@code PUT}) {@code path/{id}} updates and {@code DELETE path/{id}} deletes. Configured in
 * target.json with kind "rest" (see Target.restKind), so an inventory API, feature flags, DNS records and the like
 * need no Java.
 *
 * <p>For real-world APIs: {@code listField} points at the array when the list is wrapped (such as "/result"),
 * {@code headers} adds fixed headers, the token goes in {@code tokenHeader} with {@code tokenPrefix} (default
 * "Authorization: Bearer ..."), and {@code pagination} requests every page. A token is sent only when the target
 * names one in tokenEnv.
 */
public final class RestProvider implements Provider {

    /** Pages: {@code pageParam}=1,2,... and {@code sizeParam}={@code size}, until a page comes back short. */
    public record Pagination(String pageParam, String sizeParam, int size, int first) {
    }

    /** How to talk to the API, beyond its base URL and path. All optional. */
    public record Options(String idField, String updateMethod, String listField, Map<String, String> headers,
                          String tokenHeader, String tokenPrefix, Pagination pagination) {

        public static Options defaults() {
            return new Options("id", "PATCH", "", Map.of(), "Authorization", "Bearer ", null);
        }
    }

    private static final int MAX_PAGES = 1000;

    private final Http http;
    private final String path;
    private final String idField;
    private final Http.Method updateMethod;
    private final String listField;
    private final Pagination pagination;

    public RestProvider(String baseUrl, String path, String idField, String updateMethod, String token) {
        this(baseUrl, path, new Options(idField, updateMethod, "", Map.of(), "Authorization", "Bearer ", null), token);
    }

    public RestProvider(String baseUrl, String path, Options options, String token) {
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("\"path\" must start with /, got '" + path + "'");
        }
        this.path = path.replaceAll("/+$", "");
        this.idField = options.idField();
        this.updateMethod = switch (options.updateMethod().toUpperCase(Locale.ROOT)) {
            case "PATCH" -> Http.Method.PATCH;
            case "PUT" -> Http.Method.PUT;
            default -> throw new IllegalArgumentException("\"updateMethod\" must be PATCH or PUT, got '" + options.updateMethod() + "'");
        };
        if (!options.listField().isEmpty() && !options.listField().startsWith("/")) {
            throw new IllegalArgumentException("\"listField\" is a JSON Pointer such as /result, got '" + options.listField() + "'");
        }
        this.listField = options.listField();
        this.pagination = options.pagination();
        Map<String, String> headers = new HashMap<>(options.headers());
        for (String name : headers.keySet()) {
            if (name.equalsIgnoreCase(options.tokenHeader()) || name.equalsIgnoreCase("Authorization")) {
                throw new IllegalArgumentException("\"headers\" can't set " + name + ": put the token in an environment variable"
                        + " named by tokenEnv, so it never lands in Git");
            }
        }
        if (token != null && !token.isBlank()) {
            headers.put(options.tokenHeader(), options.tokenPrefix() + token);
        }
        this.http = new Http(baseUrl, headers);
    }

    @Override
    public List<ObjectNode> list() {
        if (pagination == null) {
            return items(http.send(Http.Method.GET, path, null));
        }
        List<ObjectNode> all = new ArrayList<>();
        for (int page = pagination.first(); page < pagination.first() + MAX_PAGES; page++) {
            Map<String, Object> query = new LinkedHashMap<>();
            query.put(pagination.pageParam(), page);
            if (!pagination.sizeParam().isEmpty()) {
                query.put(pagination.sizeParam(), pagination.size());
            }
            List<ObjectNode> batch = items(http.send(Http.Method.GET, path, query, null));
            all.addAll(batch);
            if (batch.size() < pagination.size()) {
                return all;
            }
        }
        throw new IllegalStateException("Stopped after " + MAX_PAGES + " pages of " + path);
    }

    private List<ObjectNode> items(Http.Response response) {
        JsonNode body = response.json();
        JsonNode array = listField.isEmpty() ? body : body.at(listField);
        if (!array.isArray()) {
            throw new IllegalStateException("Expected a JSON array" + (listField.isEmpty() ? "" : " at " + listField)
                    + " from GET " + path + (listField.isEmpty() ? " (set \"listField\" if the list is wrapped in an object)" : ""));
        }
        List<ObjectNode> items = new ArrayList<>();
        array.forEach(n -> items.add((ObjectNode) n));
        return items;
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
