package gitops;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static gitops.TestData.user;
import static org.assertj.core.api.Assertions.assertThat;

/** The JSON-RPC server the desktop app talks to, through in-memory stdin and stdout. */
class EngineServerTest {

    @RegisterExtension
    static WireMockExtension api = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @TempDir
    Path root;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(root.resolve("dev/users"));
        Files.writeString(root.resolve("dev/target.json"),
                "{\"kind\": \"users\", \"baseUrl\": \"" + api.baseUrl() + "\", \"prune\": true, \"localApply\": true}");
        Json.write(root.resolve("dev/users/bret.json"), user(1, "Bret", "new@b.io"));
        Files.createDirectories(root.resolve("prod/users"));
        Files.writeString(root.resolve("prod/target.json"),
                "{\"kind\": \"users\", \"baseUrl\": \"" + api.baseUrl() + "\", \"prune\": true}");
    }

    /** Sends the requests one per line, closes stdin, and returns every line the server wrote. */
    private List<JsonNode> exchange(String... requests) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String input = String.join("\n", requests) + "\n";
        int code = new EngineServer(new Engine(root), new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), out).serve();
        assertThat(code).isEqualTo(Main.OK);
        List<JsonNode> lines = new ArrayList<>();
        for (String line : out.toString(StandardCharsets.UTF_8).split("\n")) {
            if (!line.isBlank()) {
                lines.add(Json.parse(line));
            }
        }
        return lines;
    }

    private static JsonNode response(List<JsonNode> lines, int id) {
        return lines.stream().filter(l -> l.path("id").asInt(-1) == id).findFirst().orElseThrow();
    }

    private static String request(int id, String method, String params) {
        return "{\"jsonrpc\": \"2.0\", \"id\": " + id + ", \"method\": \"" + method + "\", \"params\": " + params + "}";
    }

    @Test
    void reportsItsVersionAndTargets() {
        List<JsonNode> lines = exchange(request(1, "engine.version", "{}"), request(2, "targets.list", "{}"));

        assertThat(response(lines, 1).path("result").path("schemaVersion").asInt()).isEqualTo(PlanJson.SCHEMA_VERSION);
        assertThat(response(lines, 2).path("result").path("targets")).extracting(t -> t.path("name").asText())
                .containsExactly("dev", "prod");
        assertThat(response(lines, 2).path("result").path("targets").get(0).path("localApply").asBoolean()).isTrue();
    }

    @Test
    void appliesOnlyThePlanThatWasApprovedAndReportsProgress() {
        api.stubFor(get("/users").inScenario("apply").whenScenarioStateIs("Started")
                .willReturn(okJson("[" + Json.compact(user(1, "Bret", "old@b.io")) + "]")));
        api.stubFor(patch("/users/1").inScenario("apply").willReturn(okJson("{}")).willSetStateTo("patched"));
        api.stubFor(get("/users").inScenario("apply").whenScenarioStateIs("patched")
                .willReturn(okJson("[" + Json.compact(user(1, "Bret", "new@b.io")) + "]")));
        String planId = response(exchange(request(1, "plan.compute", "{\"target\": \"dev\"}")), 1)
                .path("result").path("planId").asText();

        JsonNode stale = response(exchange(request(2, "plan.apply", "{\"target\": \"dev\", \"planId\": \"0000\"}")), 2);
        assertThat(stale.path("error").path("data").path("code").asText()).isEqualTo(EngineError.PLAN_CHANGED);
        api.verify(0, patchRequestedFor(anyUrl()));

        List<JsonNode> lines = exchange(request(3, "plan.apply", "{\"target\": \"dev\", \"planId\": \"" + planId + "\"}"));
        JsonNode result = response(lines, 3).path("result");
        assertThat(result.path("applied").asBoolean()).isTrue();
        assertThat(result.path("verified").asBoolean()).isTrue();
        assertThat(lines).filteredOn(l -> "apply.progress".equals(l.path("method").asText()))
                .extracting(l -> l.path("params").path("step").asText() + " " + l.path("params").path("state").asText())
                .containsExactly("update starting", "update finished");
    }

    @Test
    void refusesToApplyTargetsThatChangeThroughPullRequests() {
        JsonNode response = response(exchange(request(1, "plan.apply", "{\"target\": \"prod\", \"planId\": \"x\"}")), 1);

        assertThat(response.path("error").path("data").path("code").asText()).isEqualTo(EngineError.NOT_ALLOWED);
        api.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void writesResourcesOnlyWhenTheyAreValid() {
        List<JsonNode> lines = exchange(
                request(1, "resource.write", "{\"target\": \"dev\", \"resource\": {\"username\": \"Ann\", \"id\": 1}}"),
                request(2, "resource.write", "{\"target\": \"dev\", \"previousKey\": \"Bret\", \"resource\": "
                        + "{\"id\": 1, \"username\": \"Bert\", \"email\": \"b@b.io\", \"phone\": \"1\"}}"));

        JsonNode invalid = response(lines, 1).path("error").path("data");
        assertThat(invalid.path("code").asText()).isEqualTo(EngineError.INVALID_CONFIG);
        assertThat(invalid.path("details").path("problems")).extracting(p -> p.path("pointer").asText())
                .contains("/email", "/phone", "/id");
        assertThat(root.resolve("dev/users/ann.json")).doesNotExist();

        assertThat(response(lines, 2).path("result").path("removed").get(0).asText()).endsWith("bret.json");
        assertThat(root.resolve("dev/users/bert.json")).exists();
        assertThat(root.resolve("dev/users/bret.json")).doesNotExist();
    }

    @Test
    void comparesTwoTargetsInGit() {
        JsonNode result = response(exchange(request(1, "targets.compare", "{\"from\": \"dev\", \"to\": \"prod\"}")), 1)
                .path("result");

        assertThat(result.path("summary").path("create").asInt()).isEqualTo(1);
        assertThat(result.path("changes").get(0).path("key").asText()).isEqualTo("Bret");
    }

    @Test
    void answersBadRequestsWithJsonRpcErrors() {
        List<JsonNode> lines = exchange("not json", request(1, "no.such.method", "{}"), request(2, "plan.compute", "{}"));

        assertThat(lines.get(0).path("error").path("code").asInt()).isEqualTo(EngineServer.PARSE_ERROR);
        assertThat(response(lines, 1).path("error").path("code").asInt()).isEqualTo(EngineServer.METHOD_NOT_FOUND);
        assertThat(response(lines, 2).path("error").path("data").path("code").asText()).isEqualTo(EngineError.USAGE);
    }
}
