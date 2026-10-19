package gitops;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/** Reads and writes one JSON file per resource: &lt;dir&gt;/&lt;slug(key)&gt;.json. */
public final class ConfigStore {

    private final Path dir;
    private final Kind kind;

    public ConfigStore(Path dir, Kind kind) {
        this.dir = dir;
        this.kind = kind;
    }

    public Path dir() {
        return dir;
    }

    /** File name for a resource key: lower case, anything outside [a-z0-9._-] becomes '-'. */
    public static String fileName(String key) {
        return key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-") + ".json";
    }

    public List<Path> files() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(f -> f.getFileName().toString().endsWith(".json")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<ObjectNode> load() {
        return files().stream().map(Json::readObject).toList();
    }

    /** Writes exactly these resources, keeping only schema fields, and removes files for anything else. */
    public void replaceAll(List<ObjectNode> resources) {
        Set<Path> written = new HashSet<>();
        for (ObjectNode resource : resources) {
            String key = resource.path(kind.keyField()).asText();
            Path file = dir.resolve(fileName(key));
            if (!written.add(file)) {
                throw new IllegalArgumentException("Two resources map to " + file.getFileName()
                        + " (keys that differ only in case or punctuation, such as '" + key + "'); rename one first");
            }
        }
        for (ObjectNode resource : resources) {
            ObjectNode managed = Json.MAPPER.createObjectNode();
            for (String field : kind.fields()) {
                if (resource.hasNonNull(field)) {
                    managed.set(field, resource.get(field));
                }
            }
            Json.write(dir.resolve(fileName(resource.path(kind.keyField()).asText())), managed);
        }
        for (Path file : files()) {
            if (!written.contains(file)) {
                delete(file);
            }
        }
    }

    private static void delete(Path file) {
        try {
            Files.delete(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
