package gitops;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import gitops.providers.RestProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static gitops.TestData.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Policies, apply windows, health checks, CODEOWNERS, search and the REST options. */
class OperationsTest {

    @RegisterExtension
    static WireMockExtension api = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @TempDir
    Path root;

    private Path config() {
        return root.resolve("config");
    }

    private Target target(String extraSettings) throws Exception {
        Files.createDirectories(config().resolve("dev/users"));
        Files.writeString(config().resolve("dev/target.json"), "{\"kind\": \"users\", \"baseUrl\": \"" + api.baseUrl() + "\""
                + extraSettings + "}");
        Json.write(config().resolve("dev/users/bret.json"), user(1, "Bret", "new@b.io"));
        return Target.load(config(), "dev");
    }

    // --- policies

    @Test
    void policiesAreValidationProblems() throws Exception {
        Target target = target("");
        Files.writeString(config().resolve("policies.json"), """
                {"rules": [
                  {"id": "company-email", "targets": ["dev"], "field": "email", "pattern": "@company\\\\.com$", "message": "Use work addresses."},
                  {"id": "no-guests", "kinds": ["users"], "field": "username", "notEquals": "guest"},
                  {"id": "other-target", "targets": ["prod"], "field": "email", "required": true},
                  {"id": "phone-needed", "field": "phone", "required": true}
                ]}""");

        List<String> problems = ConfigValidator.validate(target);

        assertThat(problems).hasSize(1).first().asString()
                .contains("bret.json").contains("policy company-email").contains("Use work addresses");
        assertThat(ConfigValidator.problems(target).get(0).pointer()).isEqualTo("/email");
        // The editor's live check sees policies too.
        assertThat(new Engine(config()).checkResource(target, user(9, "guest", "g@company.com"), null))
                .extracting(ConfigValidator.Problem::message).anySatisfy(m -> assertThat(m).contains("policy no-guests"));
    }

    @Test
    void aBrokenPoliciesFileIsReported() throws Exception {
        Target target = target("");
        Files.writeString(config().resolve("policies.json"), "{\"rules\": [{\"id\": \"x\", \"field\": \"email\"}]}");

        assertThat(ConfigValidator.validate(target)).anySatisfy(p -> assertThat(p).contains("policies.json"));
    }

    @Test
    void thisRepositorysPoliciesHold() {
        for (Target target : Target.loadAll(Path.of("config"))) {
            assertThat(Policies.check(target, Path.of("config"))).as(target.name()).isEmpty();
        }
    }

    // --- apply windows

    @Test
    void windowsOpenOnTheirDaysAndHoursIncludingPastMidnight() {
        ApplyWindow weekend = new ApplyWindow(EnumSet.of(DayOfWeek.SATURDAY), LocalTime.of(22, 0), LocalTime.of(2, 0), ZoneId.of("UTC"));
        // 2026-11-07 is a Saturday.
        assertThat(weekend.isOpen(Instant.parse("2026-11-07T23:30:00Z"))).isTrue();
        assertThat(weekend.isOpen(Instant.parse("2026-11-08T01:59:00Z"))).isTrue();
        assertThat(weekend.isOpen(Instant.parse("2026-11-08T02:00:00Z"))).isFalse();
        assertThat(weekend.isOpen(Instant.parse("2026-11-07T21:00:00Z"))).isFalse();
        assertThat(weekend.nextOpening(Instant.parse("2026-11-03T10:00:00Z"))).isEqualTo(Instant.parse("2026-11-07T22:00:00Z"));
        assertThat(weekend.describe()).isEqualTo("Sat 22:00–02:00 UTC");
    }

    @Test
    void applyOutsideTheWindowIsDeferredAndChangesNothing() throws Exception {
        String closedNow = LocalTime.now(ZoneId.of("UTC")).plusHours(2).withMinute(0).withSecond(0).withNano(0).toString();
        String closesAfter = LocalTime.parse(closedNow).plusHours(1).toString();
        Target target = target(", \"prune\": true, \"applyWindow\": {\"start\": \"" + closedNow + "\", \"end\": \"" + closesAfter + "\"}");
        api.stubFor(get("/users").willReturn(okJson("[" + Json.compact(user(1, "Bret", "old@b.io")) + "]")));

        Engine.ApplyResult result = new Engine(config()).apply(target, null, false, true, p -> { }, new Applier.Listener() { });

        assertThat(result.deferred()).contains("applies only every day " + closedNow);
        assertThat(result.applied()).isFalse();
        api.verify(0, patchRequestedFor(anyUrl()));
        assertThat(target.toJson().path("applyWindow").path("open").asBoolean()).isFalse();
    }

    @Test
    void badWindowsAreValidationProblems() throws Exception {
        Target target = target(", \"applyWindow\": {\"days\": [\"funday\"], \"start\": \"02:00\", \"end\": \"03:00\"}");
        assertThat(ConfigValidator.validate(target)).anySatisfy(p -> assertThat(p).contains("unknown day 'funday'"));
    }

    // --- health checks

    @Test
    void aHealthCheckRunsAfterApplyAndCanFailIt() throws Exception {
        Target target = target(", \"healthCheck\": {\"url\": \"{baseUrl}/health\", \"contains\": \"ok\", \"timeoutSeconds\": 2}");
        api.stubFor(get("/users").inScenario("h").whenScenarioStateIs("Started")
                .willReturn(okJson("[" + Json.compact(user(1, "Bret", "old@b.io")) + "]")));
        api.stubFor(patch("/users/1").inScenario("h").willReturn(okJson("{}")).willSetStateTo("done"));
        api.stubFor(get("/users").inScenario("h").whenScenarioStateIs("done")
                .willReturn(okJson("[" + Json.compact(user(1, "Bret", "new@b.io")) + "]")));
        api.stubFor(get("/health").willReturn(ok("degraded")));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new Main(new PrintStream(out, true), new PrintStream(err, true))
                .run(new String[] {"apply", "--target", "dev", "--config-dir", config().toString()});

        assertThat(code).isEqualTo(Main.ERROR);
        assertThat(err.toString()).contains("Health check FAILED").contains("doesn't contain \"ok\"");

        api.stubFor(get("/health").willReturn(ok("all ok")));
        assertThat(HealthCheck.run(target).path("ok").asBoolean()).isTrue();
    }

    // --- CODEOWNERS

    @Test
    void codeownersGetsAGeneratedBlockThatCanBeRewritten() throws Exception {
        target(", \"owners\": [\"@alice\", \"@org/platform\"]");
        Files.createDirectories(root.resolve(".github"));
        Files.writeString(root.resolve(".github/CODEOWNERS"), "* @everyone\n");
        List<Target> targets = Target.loadAll(config());

        assertThat(CodeOwners.upToDate(root, targets)).isFalse();
        assertThat(CodeOwners.write(root, targets)).isTrue();
        assertThat(Files.readString(root.resolve(".github/CODEOWNERS")))
                .startsWith("* @everyone\n\n# BEGIN gitops").contains("/config/dev/ @alice @org/platform\n# END gitops\n");
        assertThat(CodeOwners.upToDate(root, targets)).isTrue();
        assertThat(CodeOwners.write(root, targets)).isFalse();
        assertThat(Lint.run(new Engine(config())).path("findings")).isEmpty();
    }

    @Test
    void badOwnersAreValidationProblems() throws Exception {
        assertThat(ConfigValidator.validate(target(", \"owners\": [\"alice\"]"))).anySatisfy(p -> assertThat(p).contains("owner 'alice'"));
    }

    // --- search

    @Test
    void searchFindsResourcesByAnyValue() throws Exception {
        target("");
        Json.write(config().resolve("dev/users/ann.json"), user(2, "Ann", "ann@corp.io"));

        ObjectNode result = new Engine(config()).search("CORP", 10);

        assertThat(result.path("results")).hasSize(1);
        assertThat(result.path("results").get(0).path("key").asText()).isEqualTo("Ann");
        assertThat(result.path("results").get(0).path("fields").toString()).isEqualTo("[\"email\"]");
        assertThat(new Engine(config()).search(".io", 1).path("truncated").asBoolean()).isTrue();
    }

    // --- REST options

    @Test
    void restReadsWrappedPagedListsWithItsOwnTokenHeader() {
        api.stubFor(get(urlPathEqualTo("/zones/z/records")).withQueryParam("page", equalTo("1")).withHeader("X-Auth", equalTo("Key secret"))
                .willReturn(okJson("{\"result\": [{\"id\": \"a\"}, {\"id\": \"b\"}]}")));
        api.stubFor(get(urlPathEqualTo("/zones/z/records")).withQueryParam("page", equalTo("2"))
                .willReturn(okJson("{\"result\": [{\"id\": \"c\"}]}")));
        RestProvider provider = new RestProvider(api.baseUrl(), "/zones/z/records", new RestProvider.Options("id", "PATCH", "/result",
                Map.of("Accept", "application/json"), "X-Auth", "Key ", new RestProvider.Pagination("page", "per_page", 2, 1)), "secret");

        assertThat(provider.list()).extracting(n -> n.path("id").asText()).containsExactly("a", "b", "c");
    }

    @Test
    void restKeepsTokensOutOfHeadersInGit() {
        assertThatThrownBy(() -> new RestProvider(api.baseUrl(), "/x", new RestProvider.Options("id", "PATCH", "",
                Map.of("Authorization", "Bearer abc"), "Authorization", "Bearer ", null), null))
                .hasMessageContaining("tokenEnv");
        assertThatThrownBy(() -> new RestProvider(api.baseUrl(), "/x", new RestProvider.Options("id", "PATCH", "result",
                Map.of(), "Authorization", "Bearer ", null), null)).hasMessageContaining("JSON Pointer");
    }
}
