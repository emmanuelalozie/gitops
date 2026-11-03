package gitops;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * {@code ./gradlew demo}: starts the mock API from mock/db.json (with Node.js, or Docker if Node isn't installed),
 * runs plan, apply and a drift check against the dev and dev-inventory targets, then stops the mock.
 */
public final class Demo {

    private static final int PORT = 3000;
    // Not "localhost": on Windows, Node binds that to IPv6 (::1) while Java connects over IPv4, so they never meet.
    private static final String HOST = "127.0.0.1";

    private Demo() {
    }

    public static void main(String[] args) throws Exception {
        System.exit(run());
    }

    private static int run() throws Exception {
        if (MockServer.portInUse(HOST, PORT)) {
            System.err.println("Port " + PORT + " is already in use. Stop whatever is using it (maybe a mock you started"
                    + " earlier, or the desktop app's) and run ./gradlew demo again.");
            return Main.ERROR;
        }
        MockServer.Handle mock = MockServer.start(Path.of("mock", "db.json"), HOST, PORT);
        if (mock == null) {
            System.err.println("The demo needs Node.js (https://nodejs.org) or Docker to run the mock API."
                    + " Install either one, or run scripts/doctor to see what's missing.");
            return Main.ERROR;
        }
        // Stop the mock on Ctrl+C too, so it doesn't keep port 3000 busy.
        Thread cleanup = new Thread(mock::stop);
        Runtime.getRuntime().addShutdownHook(cleanup);
        try {
            System.out.println("Starting the mock API with " + mock.via() + " on http://" + HOST + ":" + PORT
                    + " (the first run downloads json-server, which can take a minute)...");
            if (!MockServer.waitUntilUp(HOST, PORT, Duration.ofSeconds(120))) {
                System.err.println("The mock didn't answer within 2 minutes. Its output:\n" + Files.readString(mock.log()));
                return Main.ERROR;
            }
            System.out.println("The mock is up.");
            Main main = new Main(System.out, System.err);
            // dev manages the mock's users with a built-in kind; dev-inventory its items with the generic rest kind.
            for (String target : List.of("dev", "dev-inventory")) {
                step("1. Plan " + target + ": the mock starts out edited by hand, so Git and the live state differ");
                if (main.run(new String[] {"plan", "--target", target}) != Main.OK) {
                    return Main.ERROR;
                }
                step("2. Apply " + target + ": make the mock match Git, then check that it does");
                if (main.run(new String[] {"apply", "--target", target}) != Main.OK) {
                    return Main.ERROR;
                }
                step("3. Drift check on " + target + ": fails if anything still differs");
                int drift = main.run(new String[] {"plan", "--target", target, "--fail-on-drift"});
                if (drift != Main.OK) {
                    return drift;
                }
            }
            step("Done. Edit a file in config/dev/users or config/dev-inventory/items and run ./gradlew demo again to see your change planned and applied.");
            return Main.OK;
        } finally {
            mock.stop();
            Runtime.getRuntime().removeShutdownHook(cleanup);
            System.out.println("Stopped the mock.");
        }
    }

    private static void step(String text) {
        System.out.println();
        System.out.println("== " + text);
    }
}
