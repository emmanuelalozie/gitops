package gitops;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * A type of resource this repo can manage. Each kind has a JSON Schema in
 * src/main/resources/schemas, a key field that identifies a resource across
 * Git and the live system, and optional fields that must be unique.
 */
public enum Kind {
    USERS("users", "username", List.of("id"), false),
    // GitHub treats "Bug" and "bug" as the same label.
    LABELS("labels", "name", List.of(), true);

    private final String id;
    private final String keyField;
    private final List<String> uniqueFields;
    private final boolean caseInsensitiveKeys;
    private JsonNode schema;

    Kind(String id, String keyField, List<String> uniqueFields, boolean caseInsensitiveKeys) {
        this.id = id;
        this.keyField = keyField;
        this.uniqueFields = uniqueFields;
        this.caseInsensitiveKeys = caseInsensitiveKeys;
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

    public static Kind fromId(String id) {
        return Arrays.stream(values())
                .filter(k -> k.id.equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown kind '" + id + "', expected one of " + Arrays.stream(values()).map(Kind::id).toList()));
    }
}
