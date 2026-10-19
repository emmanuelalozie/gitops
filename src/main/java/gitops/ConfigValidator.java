package gitops;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Checks a target's files before anything is planned or applied. Returns every problem, not just the first. */
public final class ConfigValidator {

    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    private ConfigValidator() {
    }

    public static List<String> validate(Target target) {
        List<String> errors = new ArrayList<>();
        try {
            target.endpoint();
        } catch (IllegalArgumentException e) {
            errors.add(e.getMessage());
        }

        if (!Files.isDirectory(target.resourceDir())) {
            errors.add(target.resourceDir() + " does not exist. An empty target would delete everything live, so create the folder on purpose.");
        }

        Kind kind = target.kind();
        // The validator is built on Jackson 3 and this project on Jackson 2, so they exchange JSON as text.
        Schema schema = REGISTRY.getSchema(Json.compact(kind.schema()), InputFormat.JSON);
        Map<String, Map<String, Path>> seen = new HashMap<>();

        for (Path file : target.store().files()) {
            JsonNode resource;
            try {
                resource = Json.read(file);
            } catch (IllegalArgumentException e) {
                errors.add(e.getMessage());
                continue;
            }
            for (Error error : schema.validate(Json.compact(resource), InputFormat.JSON)) {
                String location = error.getInstanceLocation().toString();
                errors.add(file + ": " + (location.isEmpty() ? "" : location + ": ") + error.getMessage());
            }
            JsonNode key = resource.get(kind.keyField());
            if (key == null || !key.isTextual()) {
                continue;
            }
            String expected = ConfigStore.fileName(key.asText());
            if (!file.getFileName().toString().equals(expected)) {
                errors.add(file + ": file name must be " + expected + " for " + kind.keyField() + " '" + key.asText() + "'");
            }
            List<String> unique = new ArrayList<>(kind.uniqueFields());
            unique.add(kind.keyField());
            for (String field : unique) {
                JsonNode value = resource.get(field);
                if (value == null) {
                    continue;
                }
                Path previous = seen.computeIfAbsent(field, f -> new HashMap<>()).putIfAbsent(value.asText(), file);
                if (previous != null) {
                    errors.add(file + ": " + field + " " + value + " is already used by " + previous);
                }
            }
        }
        return errors;
    }
}
