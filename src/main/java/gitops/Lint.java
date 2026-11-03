package gitops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Checks that span files, which the schema can't see. Today: every status check that a branch protection
 * rule requires must be produced by a job of a workflow that runs on pull requests. A required check that
 * nothing produces (a job was renamed, say) blocks every pull request into that branch, and GitHub only
 * says "Expected — waiting for status to be reported".
 *
 * <p>Findings are warnings, because a check can also come from outside this repository's workflows (a
 * GitHub App, an external CI). The result also lists every check the workflows produce, for the graph.
 */
public final class Lint {

    private static final YAMLMapper YAML = new YAMLMapper();
    private static final Pattern EXPRESSION = Pattern.compile("\\$\\{\\{.*?}}");

    /** A status check a workflow job reports, as a pattern when its name has expressions in it. */
    record Check(String workflow, Path file, String job, String name, Pattern pattern, boolean onPullRequest) {
        boolean matches(String required) {
            return pattern.matcher(required).matches();
        }
    }

    private Lint() {
    }

    public static ObjectNode run(Engine engine) {
        Path repo = engine.configRoot().toAbsolutePath().normalize().getParent();
        List<Check> checks = checks(repo.resolve(".github/workflows"));
        ObjectNode root = Json.MAPPER.createObjectNode().put("schemaVersion", PlanJson.SCHEMA_VERSION);
        ArrayNode findings = root.putArray("findings");
        ArrayNode requirements = root.putArray("requirements");

        for (Target target : engine.targets()) {
            if (target.kind() != Kind.BRANCH_PROTECTION) {
                continue;
            }
            for (Path file : target.store().files()) {
                JsonNode rule;
                try {
                    rule = Json.read(file);
                } catch (IllegalArgumentException e) {
                    continue; // validation reports unreadable files
                }
                JsonNode required = rule.path("required_status_checks");
                for (int i = 0; i < required.size(); i++) {
                    String name = required.get(i).asText();
                    ObjectNode requirement = requirements.addObject()
                            .put("target", target.name())
                            .put("branch", rule.path("branch").asText())
                            .put("file", file.toString())
                            .put("check", name);
                    ArrayNode satisfiedBy = requirement.putArray("satisfiedBy");
                    for (Check check : checks) {
                        if (check.onPullRequest() && check.matches(name)) {
                            satisfiedBy.addObject().put("workflow", check.workflow()).put("job", check.job())
                                    .put("file", repo.relativize(check.file()).toString());
                        }
                    }
                    if (satisfiedBy.isEmpty()) {
                        findings.addObject()
                                .put("severity", "warning")
                                .put("code", "unknown-required-check")
                                .put("target", target.name())
                                .put("file", file.toString())
                                .put("pointer", "/required_status_checks/" + i)
                                .put("message", "Branch '" + rule.path("branch").asText() + "' requires the check '" + name
                                        + "', but no job in .github/workflows that runs on pull requests reports it."
                                        + " Pull requests into it will wait for that check forever.");
                    }
                }
            }
        }
        ArrayNode list = root.putArray("checks");
        for (Check check : checks) {
            list.addObject().put("workflow", check.workflow()).put("file", repo.relativize(check.file()).toString())
                    .put("job", check.job()).put("name", check.name()).put("onPullRequest", check.onPullRequest());
        }
        root.put("ok", findings.isEmpty());
        return root;
    }

    static List<Check> checks(Path workflows) {
        if (!Files.isDirectory(workflows)) {
            return List.of();
        }
        List<Path> files;
        try (Stream<Path> list = Files.list(workflows)) {
            files = list.filter(f -> f.toString().endsWith(".yml") || f.toString().endsWith(".yaml")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<Check> checks = new ArrayList<>();
        for (Path file : files) {
            JsonNode workflow;
            try {
                // The YAML parser is Jackson 3 (from the schema validator); hand the result over as JSON text.
                workflow = Json.parse(YAML.readTree(Files.readString(file)).toString());
            } catch (IOException | RuntimeException e) {
                continue; // not our job to validate workflow syntax; GitHub does
            }
            String name = workflow.path("name").asText(file.getFileName().toString());
            boolean onPullRequest = triggers(workflow.path("on"), "pull_request") || triggers(workflow.path("on"), "pull_request_target");
            for (Map.Entry<String, JsonNode> job : workflow.path("jobs").properties()) {
                JsonNode definition = job.getValue();
                String checkName = definition.path("name").asText(job.getKey());
                Pattern pattern;
                if (definition.has("name")) {
                    pattern = pattern(checkName);
                } else if (definition.path("strategy").has("matrix")) {
                    // A matrix job without a name reports "<job id> (<matrix values>)".
                    pattern = Pattern.compile(Pattern.quote(job.getKey()) + " \\(.+\\)");
                    checkName = job.getKey() + " (…)";
                } else {
                    pattern = Pattern.compile(Pattern.quote(checkName));
                }
                checks.add(new Check(name, file, job.getKey(), checkName, pattern, onPullRequest));
            }
        }
        return checks;
    }

    private static boolean triggers(JsonNode on, String event) {
        if (on.isTextual()) {
            return on.asText().equals(event);
        }
        if (on.isArray()) {
            for (JsonNode item : on) {
                if (item.asText().equals(event)) {
                    return true;
                }
            }
            return false;
        }
        return on.has(event);
    }

    /** A job name with ${{ }} expressions matches any text where the expressions are. */
    static Pattern pattern(String name) {
        Matcher matcher = EXPRESSION.matcher(name);
        StringBuilder regex = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            regex.append(Pattern.quote(name.substring(last, matcher.start()))).append(".+");
            last = matcher.end();
        }
        regex.append(Pattern.quote(name.substring(last)));
        return Pattern.compile(regex.toString());
    }
}
