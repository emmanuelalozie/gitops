package gitops;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static gitops.TestData.user;
import static org.assertj.core.api.Assertions.assertThat;

/** End-to-end through the CLI against a stubbed users API. */
class MainTest {

    @RegisterExtension
    static WireMockExtension api = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @TempDir
    Path root;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(root.resolve("dev/users"));
        Files.writeString(root.resolve("dev/target.json"),
                "{\"kind\": \"users\", \"baseUrl\": \"" + api.baseUrl() + "\", \"prune\": true}");
        Json.write(root.resolve("dev/users/bret.json"), user(1, "Bret", "new@b.io"));
    }

    private int run(String... args) {
        String[] full = new String[args.length + 2];
        System.arraycopy(args, 0, full, 0, args.length);
        full[args.length] = "--config-dir";
        full[args.length + 1] = root.toString();
        return new Main(new PrintStream(out, true), new PrintStream(err, true)).run(full);
    }

    @Test
    void planExitsWithDriftCodeOnlyWhenAsked() {
        api.stubFor(get("/users").willReturn(okJson("[" + Json.compact(user(1, "Bret", "old@b.io")) + "]")));

        assertThat(run("plan", "--target", "dev")).isEqualTo(Main.OK);
        assertThat(run("plan", "--target", "dev", "--fail-on-drift")).isEqualTo(Main.DRIFT);
        assertThat(out.toString()).contains("email: \"old@b.io\" -> \"new@b.io\"");
    }

    @Test
    void planWritesMarkdownForPullRequests() {
        api.stubFor(get("/users").willReturn(okJson("[]")));
        Path report = root.resolve("plan.md");

        run("plan", "--target", "dev", "--format", "markdown", "--out", report.toString());

        assertThat(report).content().contains("| + create | `Bret` |");
    }

    @Test
    void applyFailsWhenTheLiveSystemDoesNotConverge() {
        api.stubFor(get("/users").willReturn(okJson("[" + Json.compact(user(1, "Bret", "old@b.io")) + "]")));
        api.stubFor(patch("/users/1").willReturn(okJson("{}")));

        assertThat(run("apply", "--target", "dev")).isEqualTo(Main.ERROR);
        assertThat(err.toString()).contains("Apply did not converge");
        api.verify(patchRequestedFor(urlEqualTo("/users/1")));
    }

    @Test
    void applyRefusesToDeleteEverythingUnlessAsked() throws Exception {
        Files.delete(root.resolve("dev/users/bret.json"));
        api.stubFor(get("/users").willReturn(okJson("[" + Json.compact(user(1, "Bret", "a@b.io")) + "]")));
        api.stubFor(delete("/users/1").willReturn(okJson("{}")));

        assertThat(run("apply", "--target", "dev")).isEqualTo(Main.ERROR);
        assertThat(err.toString()).contains("--allow-delete-all");
        api.verify(0, deleteRequestedFor(anyUrl()));

        run("apply", "--target", "dev", "--allow-delete-all");
        api.verify(deleteRequestedFor(urlEqualTo("/users/1")));
    }

    @Test
    void targetsWithoutTheirRequiredTokenAreSkippedOnlyWhenAsked() throws Exception {
        Files.writeString(root.resolve("dev/target.json"), "{\"kind\": \"users\", \"baseUrl\": \"" + api.baseUrl()
                + "\", \"tokenEnv\": \"GITOPS_TEST_TOKEN_THAT_IS_NEVER_SET\", \"tokenRequired\": true}");
        Path report = root.resolve("plan.md");

        assertThat(run("plan", "--target", "dev")).isEqualTo(Main.ERROR);
        assertThat(err.toString()).contains("GITOPS_TEST_TOKEN_THAT_IS_NEVER_SET");
        assertThat(run("plan", "--target", "dev", "--skip-without-token", "--fail-on-drift", "--out", report.toString()))
                .isEqualTo(Main.OK);
        assertThat(report).content().contains("Skipped");
        api.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void commandsThatNeedATargetSaySo() {
        assertThat(run("apply")).isEqualTo(Main.ERROR);
        assertThat(err.toString()).contains("--target is required");
    }

    @Test
    void invalidConfigStopsPlanBeforeAnyRequest() throws Exception {
        Files.writeString(root.resolve("dev/users/bad.json"), "{\"username\": \"Bad\"}");

        assertThat(run("plan", "--target", "dev")).isEqualTo(Main.ERROR);
        assertThat(err.toString()).contains("invalid");
        api.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void snapshotImportsLiveState() {
        api.stubFor(get("/users").willReturn(okJson("[" + Json.compact(user(3, "Samantha", "s@b.io")) + "]")));

        assertThat(run("snapshot", "--target", "dev")).isEqualTo(Main.OK);
        assertThat(root.resolve("dev/users/samantha.json")).exists();
        assertThat(root.resolve("dev/users/bret.json")).doesNotExist();
    }

    @Test
    void planWritesJsonForProgramsAlongsideMarkdown() {
        api.stubFor(get("/users").willReturn(okJson("[]")));
        Path markdown = root.resolve("plan.md");
        Path json = root.resolve("plan.json");

        assertThat(run("plan", "--target", "dev", "--format", "markdown", "--out", markdown.toString(),
                "--json-out", json.toString())).isEqualTo(Main.OK);

        assertThat(markdown).content().contains("| + create | `Bret` |");
        assertThat(Json.read(json).path("summary").path("create").asInt()).isEqualTo(1);
        api.verify(1, getRequestedFor(urlEqualTo("/users")));
    }

    @Test
    void applyWithAStalePlanIdChangesNothing() {
        api.stubFor(get("/users").willReturn(okJson("[" + Json.compact(user(1, "Bret", "old@b.io")) + "]")));

        assertThat(run("apply", "--target", "dev", "--plan-id", "not-the-plan")).isEqualTo(Main.PLAN_CHANGED);
        assertThat(err.toString()).contains("changed since it was approved");
        api.verify(0, patchRequestedFor(anyUrl()));
    }

    @Test
    void applyWithTheCurrentPlanIdRuns() {
        api.stubFor(get("/users").willReturn(okJson("[" + Json.compact(user(1, "Bret", "old@b.io")) + "]")));
        api.stubFor(patch("/users/1").willReturn(okJson("{}")));
        run("plan", "--target", "dev", "--format", "json");
        String planId = Json.parse(out.toString()).path("planId").asText();

        run("apply", "--target", "dev", "--plan-id", planId);

        api.verify(patchRequestedFor(urlEqualTo("/users/1")));
    }

    @Test
    void errorsAreJsonWhenAsked() {
        api.stubFor(get("/users").willReturn(aResponse().withStatus(500).withBody("boom")));

        assertThat(run("plan", "--target", "dev", "--format", "json")).isEqualTo(Main.ERROR);

        var error = Json.parse(err.toString().strip()).path("error");
        assertThat(error.path("code").asText()).isEqualTo(EngineError.API);
        assertThat(error.path("details").path("status").asInt()).isEqualTo(500);
    }

    @Test
    void targetsAreListedAsJson() {
        assertThat(run("targets", "--format", "json")).isEqualTo(Main.OK);

        var target = Json.parse(out.toString()).path("targets").get(0);
        assertThat(target.path("name").asText()).isEqualTo("dev");
        assertThat(target.path("workflows").asBoolean()).isTrue();
        assertThat(target.path("localApply").asBoolean()).isFalse();
        assertThat(target.path("resources").asInt()).isEqualTo(1);
    }

    @Test
    void skippedTargetsWriteJsonToo() throws Exception {
        Files.writeString(root.resolve("dev/target.json"), "{\"kind\": \"users\", \"baseUrl\": \"" + api.baseUrl()
                + "\", \"tokenEnv\": \"GITOPS_TEST_TOKEN_THAT_IS_NEVER_SET\", \"tokenRequired\": true}");
        Path json = root.resolve("plan.json");

        run("plan", "--target", "dev", "--skip-without-token", "--json-out", json.toString());

        assertThat(Json.read(json).path("skipped").asBoolean()).isTrue();
    }
}
