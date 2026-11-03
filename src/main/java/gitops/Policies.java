package gitops;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import gitops.ConfigValidator.Problem;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Rules a change must follow beyond what the schema says, kept in config/policies.json, such as "no branch may
 * allow force pushes" or "prod emails must be on the company domain". A rule names the targets (by name) and/or
 * kinds it applies to, one field, and one or more checks: {@code required}, {@code equals}, {@code notEquals},
 * {@code oneOf} or {@code pattern} (for strings, and for each string in a list). A violation is a validation
 * problem: plan and apply refuse to run, and CI fails, until the config follows the rule.
 */
public final class Policies {

    public static final String FILE = "policies.json";
    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    record Rule(String id, List<String> targets, List<String> kinds, String field, boolean required, JsonNode equals,
                JsonNode notEquals, JsonNode oneOf, Pattern pattern, String message) {

        boolean appliesTo(Target target) {
            return (targets.isEmpty() || targets.contains(target.name())) && (kinds.isEmpty() || kinds.contains(target.kind().id()));
        }

        /** Why the resource breaks the rule, or null. */
        String violation(JsonNode resource) {
            JsonNode value = resource.get(field);
            if (value == null || value.isNull()) {
                return required ? "is required" : null;
            }
            if (equals != null && !equals.equals(value)) {
                return "must be " + equals + ", not " + value;
            }
            if (notEquals != null && notEquals.equals(value)) {
                return "must not be " + value;
            }
            if (oneOf != null) {
                boolean found = false;
                for (JsonNode allowed : oneOf) {
                    found |= allowed.equals(value);
                }
                if (!found) {
                    return "must be one of " + oneOf + ", not " + value;
                }
            }
            if (pattern != null) {
                List<JsonNode> strings = new ArrayList<>();
                if (value.isArray()) {
                    value.forEach(strings::add);
                } else {
                    strings.add(value);
                }
                for (JsonNode s : strings) {
                    if (s.isTextual() && !pattern.matcher(s.asText()).find()) {
                        return s + " doesn't match " + pattern.pattern();
                    }
                }
            }
            return null;
        }
    }

    private Policies() {
    }

    /** Every rule in config/policies.json; none when there's no file. Problems with the file itself go to {@code problems}. */
    static List<Rule> load(Path configRoot, List<Problem> problems) {
        Path file = configRoot.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        JsonNode root;
        try {
            root = Json.read(file);
        } catch (IllegalArgumentException e) {
            problems.add(new Problem(null, "", e.getMessage()));
            return List.of();
        }
        Schema schema = REGISTRY.getSchema(Json.compact(schema()), InputFormat.JSON);
        var errors = schema.validate(Json.compact(root), InputFormat.JSON);
        if (!errors.isEmpty()) {
            errors.forEach(e -> problems.add(new Problem(file, "", e.getInstanceLocation() + ": " + e.getMessage())));
            return List.of();
        }
        List<Rule> rules = new ArrayList<>();
        for (JsonNode r : root.path("rules")) {
            Pattern pattern = null;
            if (r.has("pattern")) {
                try {
                    pattern = Pattern.compile(r.path("pattern").asText());
                } catch (PatternSyntaxException e) {
                    problems.add(new Problem(file, "", "rule " + r.path("id").asText() + ": invalid pattern: " + e.getDescription()));
                    continue;
                }
            }
            rules.add(new Rule(r.path("id").asText(), strings(r.path("targets")), strings(r.path("kinds")), r.path("field").asText(),
                    r.path("required").asBoolean(false), r.get("equals"), r.get("notEquals"), r.get("oneOf"), pattern,
                    r.path("message").asText("")));
        }
        return rules;
    }

    /** The policy problems of one target's resources. */
    public static List<Problem> check(Target target, Path configRoot) {
        List<Problem> problems = new ArrayList<>();
        List<Rule> rules = load(configRoot, problems).stream().filter(r -> r.appliesTo(target)).toList();
        if (rules.isEmpty()) {
            return problems;
        }
        for (Path file : target.store().files()) {
            JsonNode resource;
            try {
                resource = Json.read(file);
            } catch (IllegalArgumentException e) {
                continue; // reported by the schema check
            }
            problems.addAll(check(rules, file, resource));
        }
        return problems;
    }

    /** The policy problems of one resource that may not be on disk yet (the app checks edits as they're typed). */
    public static List<Problem> checkResource(Target target, Path configRoot, Path file, JsonNode resource) {
        List<Problem> problems = new ArrayList<>();
        List<Rule> rules = load(configRoot, problems).stream().filter(r -> r.appliesTo(target)).toList();
        problems.addAll(check(rules, file, resource));
        return problems;
    }

    private static List<Problem> check(List<Rule> rules, Path file, JsonNode resource) {
        List<Problem> problems = new ArrayList<>();
        for (Rule rule : rules) {
            String why = rule.violation(resource);
            if (why != null) {
                problems.add(new Problem(file, "/" + rule.field(), "policy " + rule.id() + ": " + rule.field() + " " + why
                        + (rule.message().isBlank() ? "" : ". " + rule.message())));
            }
        }
        return problems;
    }

    private static List<String> strings(JsonNode array) {
        List<String> list = new ArrayList<>();
        array.forEach(n -> list.add(n.asText()));
        return list;
    }

    private static JsonNode schema() {
        try (InputStream in = Policies.class.getResourceAsStream("/schemas/policies.schema.json")) {
            return Json.MAPPER.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
