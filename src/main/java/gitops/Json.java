package gitops;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Shared Jackson setup so every config file is read and written the same way. */
public final class Json {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private static final ObjectWriter PRETTY = MAPPER.writer(new DefaultPrettyPrinter()
            .withObjectIndenter(new DefaultIndenter("  ", "\n"))
            .withSeparators(Separators.createDefaultInstance()
                    .withObjectFieldValueSpacing(Separators.Spacing.AFTER)));

    private Json() {
    }

    public static JsonNode parse(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON: " + e.getOriginalMessage(), e);
        }
    }

    public static JsonNode read(Path file) {
        try {
            return MAPPER.readTree(file.toFile());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(file + " is not valid JSON: " + e.getOriginalMessage(), e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static ObjectNode readObject(Path file) {
        JsonNode node = read(file);
        if (!node.isObject()) {
            throw new IllegalArgumentException(file + " must contain a JSON object");
        }
        return (ObjectNode) node;
    }

    public static String compact(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void write(Path file, JsonNode node) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, PRETTY.writeValueAsString(node) + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
