package gitops;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * A type of resource this repo can manage. Each kind has a JSON Schema, a key field that identifies a resource
 * across Git and the live system, and optional fields that must be unique.
 *
 * <p>The built-in kinds have their schema in src/main/resources/schemas. A {@code rest} target defines its own
 * kind in target.json (see {@link #rest}): any JSON API that lists, creates, updates and deletes a collection,
 * with the schema in a file next to target.json. That's how a system like an inventory API is managed without
 * writing Java.
 */
public final class Kind {

    /** Which kind of live system a kind talks to; decides the endpoint setting and the provider. */
    public enum Family { USERS_API, GITHUB, REST }

    public static final Kind USERS = builtIn("users", "username", List.of("id"), false, Family.USERS_API);
    // GitHub treats "Bug" and "bug" as the same label.
    public static final Kind LABELS = builtIn("labels", "name", List.of(), true, Family.GITHUB);
    public static final Kind REPOSITORY = builtIn("repository", "name", List.of(), false, Family.GITHUB);
    public static final Kind BRANCH_PROTECTION = builtIn("branch-protection", "branch", List.of(), false, Family.GITHUB);
    // GitHub stores variable names in upper case and matches them regardless of case.
    public static final Kind ACTIONS_VARIABLES = builtIn("actions-variables", "name", List.of(), true, Family.GITHUB);

    // GitHub matches environment names regardless of case.
    public static final Kind ENVIRONMENTS = builtIn("environments", "name", List.of(), true, Family.GITHUB);
    public static final Kind WEBHOOKS = builtIn("webhooks", "url", List.of(), false, Family.GITHUB);
    // GitHub logins are case-insensitive.
    public static final Kind COLLABORATORS = builtIn("collaborators", "login", List.of(), true, Family.GITHUB);
    public static final Kind TEAMS = builtIn("teams", "slug", List.of(), false, Family.GITHUB);
    public static final Kind RULESETS = builtIn("rulesets", "name", List.of(), false, Family.GITHUB);

    /** The kinds that need no definition in target.json. */
    public static final List<Kind> BUILT_IN = List.of(USERS, LABELS, REPOSITORY, BRANCH_PROTECTION, ACTIONS_VARIABLES,
            ENVIRONMENTS, WEBHOOKS, COLLABORATORS, TEAMS, RULESETS);

    private final String id;
    private final String keyField;
    private final List<String> uniqueFields;
    private final boolean caseInsensitiveKeys;
    private final Family family;
    private JsonNode schema;

    private Kind(String id, String keyField, List<String> uniqueFields, boolean caseInsensitiveKeys, Family family, JsonNode schema) {
        this.id = id;
        this.keyField = keyField;
        this.uniqueFields = List.copyOf(uniqueFields);
        this.caseInsensitiveKeys = caseInsensitiveKeys;
        this.family = family;
        this.schema = schema;
    }

    private static Kind builtIn(String id, String keyField, List<String> uniqueFields, boolean caseInsensitive, Family family) {
        return new Kind(id, keyField, uniqueFields, caseInsensitive, family, null);
    }

    /**
     * A kind defined by a {@code rest} target: {@code resource} names the collection (and its folder in config/),
     * {@code keyField} identifies a resource across Git and the API, and {@code idField} (the API's id, used in
     * URLs) must be unique when it isn't the key.
     */
    public static Kind rest(String resource, String keyField, String idField, JsonNode schema) {
        if (!resource.matches("[a-z0-9][a-z0-9._-]*")) {
            throw new IllegalArgumentException("\"resource\" must be a lower-case folder name such as items, got '" + resource + "'");
        }
        if (BUILT_IN.stream().anyMatch(k -> k.id.equals(resource))) {
            throw new IllegalArgumentException("\"resource\" can't be '" + resource + "', which is a built-in kind");
        }
        if (!schema.path("properties").has(keyField)) {
            throw new IllegalArgumentException("the schema for " + resource + " must declare the key field '" + keyField + "'");
        }
        List<String> unique = idField.equals(keyField) ? List.of() : List.of(idField);
        return new Kind(resource, keyField, unique, false, Family.REST, schema);
    }

    public String id() {
        return id;
    }

    public String keyField() {
        return keyField;
    }

    public List<String> uniqueFields() {
        return uniqueFields;
    }

    public Family family() {
        return family;
    }

    /** "users", "github" or "rest": what the desktop app shows next to the kind. */
    public String type() {
        return switch (family) {
            case USERS_API -> "users";
            case GITHUB -> "github";
            case REST -> "rest";
        };
    }

    /** The key of a resource as the live system compares it. */
    public String matchKey(JsonNode resource) {
        String key = resource.path(keyField).asText();
        return caseInsensitiveKeys ? key.toLowerCase(Locale.ROOT) : key;
    }

    public synchronized JsonNode schema() {
        if (schema == null) {
            String path = "/schemas/" + id + ".schema.json";
            try (InputStream in = Kind.class.getResourceAsStream(path)) {
                if (in == null) {
                    throw new IllegalStateException("Missing schema resource " + path);
                }
                schema = Json.MAPPER.readTree(in);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return schema;
    }

    /** The fields declared in the schema, in declaration order. Only these are managed. */
    public List<String> fields() {
        List<String> fields = new ArrayList<>();
        schema().path("properties").fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    /** A built-in kind by id. {@code rest} kinds are defined by their target, so they aren't found here. */
    public static Kind fromId(String id) {
        return BUILT_IN.stream()
                .filter(k -> k.id.equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown kind '" + id + "', expected one of "
                        + BUILT_IN.stream().map(Kind::id).toList() + " or rest"));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Kind k && k.id.equals(id) && k.keyField.equals(keyField) && k.family == family
                && k.uniqueFields.equals(uniqueFields);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, keyField, family, uniqueFields);
    }

    @Override
    public String toString() {
        return id;
    }
}
