package gitops;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LintTest {

    @TempDir
    Path repo;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(repo.resolve(".github/workflows"));
        Files.writeString(repo.resolve(".github/workflows/ci.yml"), """
                name: CI
                on:
                  pull_request:
                jobs:
                  build:
                    name: Build, test, validate
                    runs-on: ubuntu-latest
                  e2e:
                    name: E2E on ${{ matrix.os }}
                    strategy:
                      matrix:
                        os: [linux, mac]
                  unnamed:
                    strategy:
                      matrix:
                        node: [20]
                """);
        Files.writeString(repo.resolve(".github/workflows/nightly.yml"), """
                name: Nightly
                on:
                  schedule:
                    - cron: '0 0 * * *'
                jobs:
                  slow:
                    name: Slow suite
                """);
        Files.createDirectories(repo.resolve("config/branches/branch-protection"));
        Files.writeString(repo.resolve("config/branches/target.json"),
                "{\"kind\": \"branch-protection\", \"repository\": \"o/r\"}");
    }

    private JsonNode lint(String... checks) throws Exception {
        StringBuilder list = new StringBuilder();
        for (String check : checks) {
            list.append(list.isEmpty() ? "" : ", ").append('"').append(check).append('"');
        }
        Files.writeString(repo.resolve("config/branches/branch-protection/main.json"),
                "{\"branch\": \"main\", \"required_status_checks\": [" + list + "]}");
        return Lint.run(new Engine(repo.resolve("config")));
    }

    @Test
    void requiredChecksMatchJobNamesIncludingMatrixJobs() throws Exception {
        JsonNode result = lint("Build, test, validate", "E2E on linux", "unnamed (20)");

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("requirements").get(1).path("satisfiedBy").get(0).path("job").asText()).isEqualTo("e2e");
    }

    @Test
    void warnsAboutRequiredChecksNothingReportsOnPullRequests() throws Exception {
        JsonNode result = lint("Build, test, validate", "Build and test", "Slow suite");

        assertThat(result.path("ok").asBoolean()).isFalse();
        assertThat(result.path("findings")).extracting(f -> f.path("pointer").asText())
                .containsExactly("/required_status_checks/1", "/required_status_checks/2");
    }

    @Test
    void listsEveryCheckForTheGraph() throws Exception {
        JsonNode result = lint();

        assertThat(result.path("checks")).extracting(c -> c.path("name").asText())
                .containsExactly("Build, test, validate", "E2E on ${{ matrix.os }}", "unnamed (…)", "Slow suite");
    }
}
