package gitops;

import gitops.Plan.Action;
import gitops.Plan.Change;
import gitops.providers.Provider;

import java.io.PrintStream;
import java.util.List;

/**
 * Carries out a plan against the live system: all deletes, then updates, then creates. A change the
 * provider can't make in place is split into a delete and a create, so swapping ids or keys between
 * resources never collides with one that hasn't moved yet.
 */
public final class Applier {

    private Applier() {
    }

    public static void apply(Plan plan, Provider provider, PrintStream log) {
        List<Change> replaced = plan.changes().stream()
                .filter(c -> c.action() == Action.UPDATE && provider.requiresReplace(c.diffs()))
                .toList();

        for (Change change : plan.changes()) {
            if (change.action() == Action.DELETE || replaced.contains(change)) {
                log.println("Applying: delete " + change.key() + (replaced.contains(change) ? " (to replace it)" : ""));
                provider.delete(change.live());
            }
        }
        for (Change change : plan.changes()) {
            if (change.action() == Action.UPDATE && !replaced.contains(change)) {
                log.println("Applying: update " + change.key());
                provider.update(change.live(), change.desired(), change.diffs());
            }
        }
        for (Change change : plan.changes()) {
            if (change.action() == Action.CREATE || replaced.contains(change)) {
                log.println("Applying: create " + change.key() + (replaced.contains(change) ? " (replacing it)" : ""));
                provider.create(change.desired());
            }
        }
    }
}
