package gitops;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The rules around the desktop app's mock button; starting json-server itself is covered by ./gradlew demo and CI. */
class MocksTest {

    @TempDir
    Path repo;

    private Target target(String baseUrl, String mock) throws Exception {
        Files.createDirectories(repo.resolve("config/dev/users"));
        Files.createDirectories(repo.resolve("mock"));
        Files.writeString(repo.resolve("mock/db.json"), "{\"users\": []}");
        Files.writeString(repo.resolve("config/dev/target.json"), "{\"kind\": \"users\", \"baseUrl\": \"" + baseUrl + "\""
                + (mock == null ? "" : ", \"mock\": \"" + mock + "\"") + "}");
        return Target.load(repo.resolve("config"), "dev");
    }

    @Test
    void reportsAMockThatIsNotRunning() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        var status = new Mocks(repo).status(target("http://127.0.0.1:" + port, "mock/db.json"));

        assertThat(status.path("configured").asBoolean()).isTrue();
        assertThat(status.path("running").asBoolean()).isFalse();
        assertThat(status.path("seed").asText()).isEqualTo("mock/db.json");
        assertThat(status.path("url").asText()).isEqualTo("http://127.0.0.1:" + port);
    }

    @Test
    void seesAMockStartedElsewhereButDoesNotStopIt() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            Target target = target("http://localhost:" + socket.getLocalPort(), "mock/db.json");
            Mocks mocks = new Mocks(repo);

            assertThat(mocks.status(target).path("running").asBoolean()).isTrue();
            assertThat(mocks.status(target).path("startedHere").asBoolean()).isFalse();
            assertThatThrownBy(() -> mocks.stop(target)).isInstanceOf(EngineError.NotAllowed.class).hasMessageContaining("outside the app");
            // Something already answers there, so starting is a no-op rather than a second server.
            assertThat(mocks.start(target).path("running").asBoolean()).isTrue();
        }
    }

    @Test
    void onlyLocalTargetsWithASeedInsideTheRepository() throws Exception {
        Mocks mocks = new Mocks(repo);
        assertThat(mocks.status(target("http://127.0.0.1:3000", null)).path("configured").asBoolean()).isFalse();
        assertThatThrownBy(() -> mocks.start(target("http://127.0.0.1:3000", null))).hasMessageContaining("has no mock");
        assertThatThrownBy(() -> mocks.status(target("https://api.example.com", "mock/db.json"))).hasMessageContaining("only runs on this computer");
        assertThatThrownBy(() -> mocks.status(target("http://127.0.0.1:3000", "../../etc/passwd"))).hasMessageContaining("inside the repository");
    }
}
