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

    /** The steps of an apply, in the order they run. */
    public enum Step {
        DELETE, DELETE_TO_REPLACE, UPDATE, CREATE, CREATE_REPLACEMENT;

        public String id() {
            return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        }
    }

    /** Follows an apply as it happens, so a terminal or the desktop app can show progress. */
    public interface Listener {
        default void starting(Change change, Step step) {
        }

        default void finished(Change change, Step step) {
        }

        /** The step failed; the apply stops and the exception is rethrown after this. */
        default void failed(Change change, Step step, RuntimeException error) {
        }
    }

    private Applier() {
    }

    public static void apply(Plan plan, Provider provider, PrintStream log) {
        apply(plan, provider, printing(log));
    }

    public static void apply(Plan plan, Provider provider, Listener listener) {
        List<Change> replaced = plan.changes().stream()
                .filter(c -> c.action() == Action.UPDATE && provider.requiresReplace(c.diffs()))
                .toList();

        for (Change change : plan.changes()) {
            if (change.action() == Action.DELETE) {
                run(change, Step.DELETE, listener, () -> provider.delete(change.live()));
            } else if (replaced.contains(change)) {
                run(change, Step.DELETE_TO_REPLACE, listener, () -> provider.delete(change.live()));
            }
        }
        for (Change change : plan.changes()) {
            if (change.action() == Action.UPDATE && !replaced.contains(change)) {
                run(change, Step.UPDATE, listener, () -> provider.update(change.live(), change.desired(), change.diffs()));
            }
        }
        for (Change change : plan.changes()) {
            if (change.action() == Action.CREATE) {
                run(change, Step.CREATE, listener, () -> provider.create(change.desired()));
            } else if (replaced.contains(change)) {
                run(change, Step.CREATE_REPLACEMENT, listener, () -> provider.create(change.desired()));
            }
        }
    }

    private static void run(Change change, Step step, Listener listener, Runnable action) {
        listener.starting(change, step);
        try {
            action.run();
        } catch (RuntimeException e) {
            listener.failed(change, step, e);
            throw e;
        }
        listener.finished(change, step);
    }

    /** The terminal output: one line per step. */
    public static Listener printing(PrintStream log) {
        return new Listener() {
            @Override
            public void starting(Change change, Step step) {
                log.println(switch (step) {
                    case DELETE -> "Applying: delete " + change.key();
                    case DELETE_TO_REPLACE -> "Applying: delete " + change.key() + " (to replace it)";
                    case UPDATE -> "Applying: update " + change.key();
                    case CREATE -> "Applying: create " + change.key();
                    case CREATE_REPLACEMENT -> "Applying: create " + change.key() + " (replacing it)";
                });
            }
        };
    }
}
