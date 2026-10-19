package gitops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gitops.providers.GitHubBranchProtectionProvider;
import gitops.providers.GitHubLabelsProvider;
import gitops.providers.GitHubRepositoryProvider;
import gitops.providers.Provider;
import gitops.providers.UsersProvider;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * A live system managed from Git: config/&lt;name&gt;/target.json plus the resource
 * files in config/&lt;name&gt;/&lt;kind&gt;/.
 *
 * @param prune            delete live resources that are not in Git (otherwise they are reported as unmanaged)
 * @param verifyAfterApply re-run the plan after apply and fail if drift remains
 */
public record Target(String name, Path dir, Kind kind, boolean prune, boolean verifyAfterApply, ObjectNode settings) {

    public static final String FILE = "target.json";

    public static Target load(Path configRoot, String name) {
        Path dir = configRoot.resolve(name);
        Path file = dir.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("No target '" + name + "': " + file + " does not exist");
        }
        ObjectNode settings = Json.readObject(file);
        Kind kind = Kind.fromId(settings.path("kind").asText());
        return new Target(name, dir, kind,
                settings.path("prune").asBoolean(false),
                settings.path("verifyAfterApply").asBoolean(true),
                settings);
    }

    public static List<Target> loadAll(Path configRoot) {
        try (Stream<Path> dirs = Files.list(configRoot)) {
            return dirs.filter(d -> Files.isRegularFile(d.resolve(FILE)))
                    .map(d -> load(configRoot, d.getFileName().toString()))
                    .sorted((a, b) -> a.name().compareTo(b.name()))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Path resourceDir() {
        return dir.resolve(kind.id());
    }

    public ConfigStore store() {
        return new ConfigStore(resourceDir(), kind);
    }

    public String setting(String key) {
        JsonNode value = settings.get(key);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(dir.resolve(FILE) + " must set \"" + key + "\"");
        }
        return value.asText();
    }

    /** Where the target points, after applying environment-variable overrides. */
    public String endpoint() {
        return switch (kind) {
            case USERS -> envOr(baseUrlVariable(), setting("baseUrl"));
            case LABELS, REPOSITORY, BRANCH_PROTECTION -> setting("repository");
        };
    }

    /**
     * The environment variable holding this target's API token: "tokenEnv" in target.json, otherwise
     * GITHUB_TOKEN for GitHub targets and none for users APIs.
     */
    public String tokenVariable() {
        String configured = settings.path("tokenEnv").asText("");
        if (!configured.isBlank()) {
            return configured;
        }
        return kind == Kind.USERS ? null : "GITHUB_TOKEN";
    }

    /** True when target.json sets "tokenRequired" and the token variable is empty. */
    public boolean missingRequiredToken() {
        return settings.path("tokenRequired").asBoolean(false) && token() == null;
    }

    private String token() {
        String variable = tokenVariable();
        return variable == null ? null : envOr(variable, null);
    }

    public Provider provider() {
        String github = envOr("GITHUB_API_URL", "https://api.github.com");
        return switch (kind) {
            case USERS -> new UsersProvider(endpoint(), token());
            case LABELS -> new GitHubLabelsProvider(github, endpoint(), token());
            case REPOSITORY -> new GitHubRepositoryProvider(github, endpoint(), token());
            case BRANCH_PROTECTION -> new GitHubBranchProtectionProvider(github, endpoint(), token());
        };
    }

    public String describe() {
        return kind.id() + " @ " + endpoint();
    }

    /** GITOPS_&lt;TARGET&gt;_BASE_URL, so an override for one environment can never point another one elsewhere. */
    public String baseUrlVariable() {
        return "GITOPS_" + name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_") + "_BASE_URL";
    }

    private static String envOr(String variable, String fallback) {
        String value = System.getenv(variable);
        return value == null || value.isBlank() ? fallback : value;
    }
}
