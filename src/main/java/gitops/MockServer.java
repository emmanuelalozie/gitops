package gitops;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * A local json-server mock API from a seed file (mock/db.json), with Node.js or, failing that, Docker. The seed
 * is copied first, so applying never edits the committed file. Used by {@code ./gradlew demo} and by the desktop
 * app's "Start mock" button (through the engine server, which stops what it started when it exits).
 */
public final class MockServer {

    static final String JSON_SERVER = "json-server@0.17.4";
    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");

    /** A running mock. {@code via} is "Node.js" or "Docker". */
    public record Handle(String via, String host, int port, Path seed, Path log, Process process, Runnable stopper) {
        public void stop() {
            stopper.run();
        }
    }

    private MockServer() {
    }

    /** Starts a mock serving a copy of {@code seed}. Returns null when neither Node.js nor Docker is available. */
    public static Handle start(Path seed, String host, int port) throws IOException {
        if (!Files.isRegularFile(seed)) {
            throw new IllegalArgumentException("The mock's seed " + seed + " doesn't exist");
        }
        Path dir = Files.createTempDirectory("gitops-mock");
        Path db = dir.resolve("db.json");
        Files.copy(seed, db, StandardCopyOption.REPLACE_EXISTING);
        Path log = dir.resolve("mock.log");
        String npx = WINDOWS ? "npx.cmd" : "npx";
        if (works(npx, "--version")) {
            Process process = new ProcessBuilder(npx, "--yes", JSON_SERVER, "--host", host, "--port", String.valueOf(port), db.toString())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            return new Handle("Node.js", host, port, seed, log, process, () -> {
                // npx starts json-server as a child process, which has to be stopped too.
                process.descendants().forEach(ProcessHandle::destroy);
                process.destroy();
            });
        }
        if (works("docker", "info")) {
            String container = "gitops-mock-" + port;
            Process process = new ProcessBuilder("docker", "run", "--rm", "--name", container,
                    "-p", host + ":" + port + ":" + port, "-v", dir.toAbsolutePath() + ":/data",
                    "node:20-alpine", "npx", "--yes", JSON_SERVER, "--host", "0.0.0.0", "--port", String.valueOf(port), "/data/db.json")
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            return new Handle("Docker", host, port, seed, log, process, () -> {
                works("docker", "rm", "-f", container);
                process.destroy();
            });
        }
        return null;
    }

    public static boolean portInUse(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** The first start downloads json-server (and with Docker, the Node image), so allow some time. */
    public static boolean waitUntilUp(String host, int port, Duration timeout) throws InterruptedException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + host + ":" + port + "/db")).build();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) {
                    return true;
                }
            } catch (IOException e) {
                // not up yet
            }
            Thread.sleep(500);
        }
        return false;
    }

    static boolean works(String... command) {
        try {
            Process process = new ProcessBuilder(List.of(command)).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
