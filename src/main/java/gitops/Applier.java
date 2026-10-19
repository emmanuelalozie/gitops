package gitops;

import gitops.Plan.Action;
import gitops.Plan.Change;
import gitops.providers.Provider;

import java.io.PrintStream;
import java.util.Comparator;

/** Carries out a plan against the live system. Deletes run first so replaced keys and ids are free. */
public final class Applier {

    private Applier() {
    }

    public static void apply(Plan plan, Provider provider, PrintStream log) {
        for (Change change : plan.changes().stream().sorted(Comparator.comparing(Applier::order)).toList()) {
            log.println("Applying: " + change.action().name().toLowerCase() + " " + change.key());
            switch (change.action()) {
                case CREATE -> provider.create(change.desired());
                case UPDATE -> provider.update(change.live(), change.desired(), change.diffs());
                case DELETE -> provider.delete(change.live());
            }
        }
    }

    private static int order(Change change) {
        return change.action() == Action.DELETE ? 0 : change.action() == Action.UPDATE ? 1 : 2;
    }
}
