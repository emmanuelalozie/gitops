package gitops;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Checks a target's files before anything is planned or applied. Returns every problem, not just the first. */
public final class ConfigValidator {

    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    private ConfigValidator() {
    }

    public static List<String> validate(Target target) {
        List<String> errors = new ArrayList<>();
        try {
            target.endpoint();
        } catch (IllegalArgumentException e) {
            errors.add(e.getMessage());
        }

        Kind kind = target.kind();
        JsonSchema schema = FACTORY.getSchema(kind.schema());
        Map<String, Map<String, Path>> seen = new HashMap<>();

        for (Path file : target.store().files()) {
            JsonNode resource;
            try {
                resource = Json.read(file);
            } catch (IllegalArgumentException e) {
                errors.add(e.getMessage());
                continue;
            }
            for (ValidationMessage message : schema.validate(resource)) {
                errors.add(file + ": " + message.getMessage());
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
