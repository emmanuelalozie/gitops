package gitops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gitops.providers.GitHubBranchProtectionProvider;
import gitops.providers.GitHubLabelsProvider;
import gitops.providers.GitHubRepositoryProvider;
import gitops.providers.GitHubActionsVariablesProvider;
import gitops.providers.Provider;
import gitops.providers.RestProvider;
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
 *
 * <p>Two more settings say who may change the target. {@code workflows} (default true) puts it in the plan,
 * apply and drift workflows. {@code localApply} (default false) lets the desktop app apply it from a laptop;
 * only throwaway systems such as the dev mock should set it, since everything else changes through a merge.
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
        Kind kind = "rest".equals(settings.path("kind").asText()) ? restKind(dir, settings) : Kind.fromId(settings.path("kind").asText());
        return new Target(name, dir, kind,
                settings.path("prune").asBoolean(false),
                settings.path("verifyAfterApply").asBoolean(true),
                settings);
    }

    /**
     * The kind a {@code rest} target defines: "resource" (the collection, and its folder), "key" (default: the
     * id field), "idField" (default "id") and "schema" (default "&lt;resource&gt;.schema.json" next to target.json).
     */
    static Kind restKind(Path dir, ObjectNode settings) {
        String where = dir.resolve(FILE).toString();
        String resource = settings.path("resource").asText("");
        if (resource.isBlank()) {
            throw new IllegalArgumentException(where + " must set \"resource\" for a rest target, such as \"items\"");
        }
        String idField = settings.path("idField").asText("id");
        String key = settings.path("key").asText(idField);
        Path schemaFile = dir.resolve(settings.path("schema").asText(resource + ".schema.json")).normalize();
        if (!schemaFile.startsWith(dir.normalize())) {
            throw new IllegalArgumentException(where + ": \"schema\" must be a file inside " + dir);
        }
        if (!Files.isRegularFile(schemaFile)) {
            throw new IllegalArgumentException(where + ": the schema " + schemaFile + " doesn't exist. A rest target needs a"
                    + " JSON Schema for its resources; only the fields it declares are managed.");
        }
        try {
            return Kind.rest(resource, key, idField, Json.readObject(schemaFile));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(where + ": " + e.getMessage(), e);
        }
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

    /** The desktop app may apply this target directly (otherwise changes go through a pull request). */
    public boolean localApply() {
        return settings.path("localApply").asBoolean(false);
    }

    /** The plan, apply and drift workflows manage this target. */
    public boolean workflows() {
        return settings.path("workflows").asBoolean(true);
    }

    public String description() {
        return settings.path("description").asText("");
    }

    /** Whether this kind of target talks to GitHub (and so sends a token when there is one). */
    public boolean usesGitHub() {
        return kind.family() == Kind.Family.GITHUB;
    }

    /** Everything a program needs to know about the target, without contacting the live system. */
    public ObjectNode toJson() {
        ObjectNode node = Json.MAPPER.createObjectNode();
        node.put("name", name);
        node.put("kind", kind.id());
        node.put("type", kind.type());
        node.put("keyField", kind.keyField());
        node.put("description", description());
        try {
            node.put("endpoint", endpoint());
        } catch (IllegalArgumentException e) {
            node.putNull("endpoint");
        }
        if (kind.family() != Kind.Family.GITHUB) {
            node.put("endpointVariable", baseUrlVariable());
            node.put("endpointOverridden", System.getenv(baseUrlVariable()) != null
                    && !System.getenv(baseUrlVariable()).isBlank());
        }
        node.put("prune", prune);
        node.put("verifyAfterApply", verifyAfterApply);
        node.put("localApply", localApply());
        String mock = settings.path("mock").asText("");
        if (mock.isBlank()) {
            node.putNull("mock");
        } else {
            node.put("mock", mock);
        }
        node.put("workflows", workflows());
        boolean tokenRequired = settings.path("tokenRequired").asBoolean(false);
        if (tokenVariable() != null) {
            node.put("tokenVariable", tokenVariable());
        } else {
            node.putNull("tokenVariable");
        }
        node.put("tokenRequired", tokenRequired);
        node.put("tokenPresent", token() != null);
        node.put("dir", dir.toString());
        node.put("resourceDir", resourceDir().toString());
        node.put("resources", store().files().size());
        return node;
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
        return switch (kind.family()) {
            case USERS_API, REST -> envOr(baseUrlVariable(), setting("baseUrl"));
            case GITHUB -> setting("repository");
        };
    }

    /**
     * The environment variable holding this target's token: "tokenEnv" in target.json, or GITHUB_TOKEN for GitHub
     * kinds. Other APIs get a token only when tokenEnv names one, so a GitHub token never goes anywhere else.
     */
    public String tokenVariable() {
        String configured = settings.path("tokenEnv").asText("");
        if (!configured.isBlank()) {
            return configured;
        }
        return usesGitHub() ? "GITHUB_TOKEN" : null;
    }

    /** True when target.json sets "tokenRequired" and the token variable is empty. */
    public boolean missingRequiredToken() {
        return settings.path("tokenRequired").asBoolean(false) && token() == null;
    }

    private String token() {
        return tokenVariable() == null ? null : envOr(tokenVariable(), null);
    }

    public Provider provider() {
        String github = envOr("GITHUB_API_URL", "https://api.github.com");
        if (kind.family() == Kind.Family.REST) {
            return new RestProvider(endpoint(), settings.path("path").asText("/" + kind.id()),
                    settings.path("idField").asText("id"), settings.path("updateMethod").asText("PATCH"), token());
        }
        if (kind.equals(Kind.USERS)) {
            return new UsersProvider(endpoint());
        }
        if (kind.equals(Kind.LABELS)) {
            return new GitHubLabelsProvider(github, endpoint(), token());
        }
        if (kind.equals(Kind.REPOSITORY)) {
            return new GitHubRepositoryProvider(github, endpoint(), token());
        }
        if (kind.equals(Kind.BRANCH_PROTECTION)) {
            return new GitHubBranchProtectionProvider(github, endpoint(), token());
        }
        if (kind.equals(Kind.ACTIONS_VARIABLES)) {
            return new GitHubActionsVariablesProvider(github, endpoint(), token());
        }
        throw new IllegalStateException("No provider for kind " + kind);
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
