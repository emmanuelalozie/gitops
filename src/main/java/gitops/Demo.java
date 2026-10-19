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
 * {@code ./gradlew demo}: starts the mock users API from mock/db.json (with Node.js, or Docker if Node
 * isn't installed), runs plan, apply and a drift check against the dev target, then stops the mock.
 */
public final class Demo {

    private static final int PORT = 3000;
    // Not "localhost": on Windows, Node binds that to IPv6 (::1) while Java connects over IPv4, so they never meet.
    private static final String HOST = "127.0.0.1";
    private static final String JSON_SERVER = "json-server@0.17.4";
    private static final String CONTAINER = "gitops-demo-mock";
    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");

    private Demo() {
    }

    public static void main(String[] args) throws Exception {
        System.exit(run());
    }

    private static int run() throws Exception {
        if (portInUse()) {
            System.err.println("Port " + PORT + " is already in use. Stop whatever is using it (maybe a mock you started"
                    + " earlier) and run ./gradlew demo again.");
            return Main.ERROR;
        }
        Path dir = Files.createTempDirectory("gitops-demo");
        Path db = dir.resolve("db.json");
        Files.copy(Path.of("mock", "db.json"), db, StandardCopyOption.REPLACE_EXISTING);
        Path log = dir.resolve("mock.log");

        Mock mock = start(db, log);
        if (mock == null) {
            System.err.println("The demo needs Node.js (https://nodejs.org) or Docker to run the mock users API."
                    + " Install either one, or run scripts/doctor to see what's missing.");
            return Main.ERROR;
        }
        // Stop the mock on Ctrl+C too, so it doesn't keep port 3000 busy.
        Thread cleanup = new Thread(mock::stop);
        Runtime.getRuntime().addShutdownHook(cleanup);
        try {
            System.out.println("Starting the mock users API with " + mock.name + " on http://" + HOST + ":" + PORT
                    + " (the first run downloads json-server, which can take a minute)...");
            if (!waitUntilUp(Duration.ofSeconds(120))) {
                System.err.println("The mock didn't answer within 2 minutes. Its output:\n" + Files.readString(log));
                return Main.ERROR;
            }
            System.out.println("The mock is up.");
            Main main = new Main(System.out, System.err);
            step("1. Plan: the mock starts out edited by hand, so Git and the live state differ");
            if (main.run(new String[] {"plan", "--target", "dev"}) != Main.OK) {
                return Main.ERROR;
            }
            step("2. Apply: make the mock match Git, then check that it does");
            if (main.run(new String[] {"apply", "--target", "dev"}) != Main.OK) {
                return Main.ERROR;
            }
            step("3. Drift check: fails if anything still differs");
            int drift = main.run(new String[] {"plan", "--target", "dev", "--fail-on-drift"});
            if (drift != Main.OK) {
                return drift;
            }
            step("Done. Edit a file in config/dev/users and run ./gradlew demo again to see your change planned and applied.");
            return Main.OK;
        } finally {
            mock.stop();
            Runtime.getRuntime().removeShutdownHook(cleanup);
            System.out.println("Stopped the mock.");
        }
    }

    private record Mock(String name, Process process, Runnable stopper) {
        void stop() {
            stopper.run();
        }
    }

    /** Node.js first, since it starts faster; Docker otherwise. Null when neither is available. */
    private static Mock start(Path db, Path log) throws IOException {
        if (works(WINDOWS ? "npx.cmd" : "npx", "--version")) {
            Process process = new ProcessBuilder(WINDOWS ? "npx.cmd" : "npx", "--yes", JSON_SERVER,
                    "--host", HOST, "--port", String.valueOf(PORT), db.toString())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            return new Mock("Node.js", process, () -> {
                // npx starts json-server as a child process, which has to be stopped too.
                process.descendants().forEach(ProcessHandle::destroy);
                process.destroy();
            });
        }
        if (works("docker", "info")) {
            Process process = new ProcessBuilder("docker", "run", "--rm", "--name", CONTAINER,
                    "-p", HOST + ":" + PORT + ":" + PORT, "-v", db.getParent().toAbsolutePath() + ":/data",
                    "node:20-alpine", "npx", "--yes", JSON_SERVER, "--host", "0.0.0.0", "--port", String.valueOf(PORT),
                    "/data/" + db.getFileName())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            return new Mock("Docker", process, () -> {
                works("docker", "rm", "-f", CONTAINER);
                process.destroy();
            });
        }
        return null;
    }

    private static boolean works(String... command) {
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

    private static boolean portInUse() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** The first start downloads json-server (and with Docker, the Node image), so allow some time. */
    private static boolean waitUntilUp(Duration timeout) throws InterruptedException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + HOST + ":" + PORT + "/users")).build();
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

    private static void step(String text) {
        System.out.println();
        System.out.println("== " + text);
    }
}
