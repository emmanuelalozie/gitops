package gitops;

import java.io.PrintStream;
import java.util.List;

/** Copies the desired state of one environment to another, e.g. dev to prod, so it can go through a PR. */
public final class Promoter {

    private Promoter() {
    }

    public static void promote(Target from, Target to, PrintStream log) {
        if (from.kind() != to.kind()) {
            throw new IllegalArgumentException("Cannot promote " + from.kind().id() + " from '" + from.name()
                    + "' to " + to.kind().id() + " in '" + to.name() + "'");
        }
        List<String> errors = ConfigValidator.validate(from);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("'" + from.name() + "' is invalid, not promoting:\n  " + String.join("\n  ", errors));
        }
        var resources = from.store().load();
        to.store().replaceAll(resources);
        log.println("Promoted " + resources.size() + " " + from.kind().id() + " from '" + from.name() + "' to '" + to.name() + "'");
    }
}
