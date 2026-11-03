package gitops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.path.NodePath;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Checks a target's files before anything is planned or applied. Returns every problem, not just the first. */
public final class ConfigValidator {

    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    /**
     * One thing wrong with the config.
     *
     * @param file    the resource file, or null for a problem with the target as a whole
     * @param pointer JSON Pointer to the field inside the file ("" for the whole file)
     */
    public record Problem(Path file, String pointer, String message) {

        /** The one-line form the CLI prints: {@code file: message}. */
        @Override
        public String toString() {
            return file == null ? message : file + ": " + message;
        }

        public ObjectNode toJson() {
            ObjectNode node = Json.MAPPER.createObjectNode();
            if (file == null) {
                node.putNull("file");
            } else {
                node.put("file", file.toString());
            }
            return node.put("pointer", pointer).put("message", message);
        }
    }

    private ConfigValidator() {
    }

    public static List<String> validate(Target target) {
        return problems(target).stream().map(Problem::toString).toList();
    }

    public static List<Problem> problems(Target target) {
        List<Problem> problems = new ArrayList<>();
        try {
            target.endpoint();
        } catch (IllegalArgumentException e) {
            problems.add(new Problem(null, "", e.getMessage()));
        }

        if (!Files.isDirectory(target.resourceDir())) {
            problems.add(new Problem(null, "", target.resourceDir()
                    + " does not exist. An empty target would delete everything live, so create the folder on purpose."));
        }

        Kind kind = target.kind();
        Map<String, Map<String, Path>> seen = new HashMap<>();

        for (Path file : target.store().files()) {
            JsonNode resource;
            try {
                resource = Json.read(file);
            } catch (IllegalArgumentException e) {
                problems.add(new Problem(null, "", e.getMessage()));
                continue;
            }
            problems.addAll(schemaProblems(kind, file, resource));
            JsonNode key = resource.get(kind.keyField());
            if (key == null || !key.isTextual()) {
                continue;
            }
            String expected = ConfigStore.fileName(key.asText());
            if (!file.getFileName().toString().equals(expected)) {
                problems.add(new Problem(file, "/" + escape(kind.keyField()), "file name must be " + expected + " for " + kind.keyField()
                        + " '" + key.asText() + "'"));
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
                    problems.add(new Problem(file, "/" + escape(field), field + " " + value + " is already used by " + previous));
                }
            }
        }
        return problems;
    }

    /**
     * Schema problems of one resource that may not be on disk yet (the desktop app checks edits as they're
     * typed). The pointer always names the field, even for a missing required one, so the field can be marked.
     */
    public static List<Problem> schemaProblems(Kind kind, Path file, JsonNode resource) {
        // The validator is built on Jackson 3 and this project on Jackson 2, so they exchange JSON as text.
        Schema schema = REGISTRY.getSchema(Json.compact(kind.schema()), InputFormat.JSON);
        List<Problem> problems = new ArrayList<>();
        for (Error error : schema.validate(Json.compact(resource), InputFormat.JSON)) {
            String location = error.getInstanceLocation().toString();
            String message = (location.isEmpty() ? "" : location + ": ") + error.getMessage();
            String pointer = pointer(error.getInstanceLocation());
            if (error.getProperty() != null && ("required".equals(error.getKeyword())
                    || "additionalProperties".equals(error.getKeyword()))) {
                pointer = pointer + "/" + escape(error.getProperty());
            }
            problems.add(new Problem(file, pointer, message));
        }
        return problems;
    }

    private static String pointer(NodePath path) {
        StringBuilder pointer = new StringBuilder();
        for (int i = 0; i < path.getNameCount(); i++) {
            pointer.append('/').append(escape(String.valueOf(path.getElement(i))));
        }
        return pointer.toString();
    }

    private static String escape(String segment) {
        return segment.replace("~", "~0").replace("/", "~1");
    }
}
