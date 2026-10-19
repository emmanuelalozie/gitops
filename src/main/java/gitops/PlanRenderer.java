package gitops;

import com.fasterxml.jackson.databind.JsonNode;
import gitops.Plan.Action;
import gitops.Plan.Change;
import gitops.Plan.FieldDiff;

/** Formats a plan for a terminal or for a pull request comment. */
public final class PlanRenderer {

    private PlanRenderer() {
    }

    public static String render(Plan plan, String format) {
        return switch (format) {
            case "text" -> text(plan);
            case "markdown" -> markdown(plan);
            default -> throw new IllegalArgumentException("Unknown format '" + format + "', expected text or markdown");
        };
    }

    public static String text(Plan plan) {
        StringBuilder out = new StringBuilder();
        out.append("Plan for target '").append(plan.target()).append("' (").append(plan.description()).append(")\n");
        for (Change change : plan.changes()) {
            out.append("  ").append(symbol(change.action())).append(' ')
                    .append(change.action().name().toLowerCase()).append(' ').append(change.key()).append('\n');
            for (FieldDiff diff : change.diffs()) {
                out.append("      ").append(diff.field()).append(": ")
                        .append(value(diff.live())).append(" -> ").append(value(diff.desired())).append('\n');
            }
        }
        if (!plan.unmanaged().isEmpty()) {
            out.append("  ").append(plan.unmanaged().size())
                    .append(" live resource(s) not in Git, left alone because prune is off: ")
                    .append(String.join(", ", plan.unmanaged())).append('\n');
        }
        out.append(plan.hasChanges() ? summary(plan) : "No changes. Live state matches Git.").append('\n');
        return out.toString();
    }

    public static String markdown(Plan plan) {
        StringBuilder out = new StringBuilder();
        out.append("### Plan for `").append(plan.target()).append("` (").append(plan.description()).append(")\n\n");
        if (!plan.hasChanges()) {
            out.append("No changes. Live state matches Git.\n");
        } else {
            out.append("**").append(summary(plan)).append("**\n\n");
            out.append("| Action | Resource | Changes |\n|---|---|---|\n");
            for (Change change : plan.changes()) {
                out.append("| ").append(symbol(change.action())).append(' ').append(change.action().name().toLowerCase())
                        .append(" | `").append(change.key()).append("` | ").append(details(change)).append(" |\n");
            }
        }
        if (!plan.unmanaged().isEmpty()) {
            out.append("\n").append(plan.unmanaged().size())
                    .append(" live resource(s) are not in Git and are left alone because prune is off.\n");
        }
        return out.toString();
    }

    private static String details(Change change) {
        return switch (change.action()) {
            case CREATE -> "new resource";
            case DELETE -> "not in Git";
            case UPDATE -> String.join("<br>", change.diffs().stream()
                    .map(d -> "`" + d.field() + "`: " + escape(value(d.live())) + " → " + escape(value(d.desired())))
                    .toList());
        };
    }

    private static String summary(Plan plan) {
        return "Plan: " + plan.count(Action.CREATE) + " to create, " + plan.count(Action.UPDATE) + " to update, "
                + plan.count(Action.DELETE) + " to delete.";
    }

    private static char symbol(Action action) {
        return switch (action) {
            case CREATE -> '+';
            case UPDATE -> '~';
            case DELETE -> '-';
        };
    }

    private static String value(JsonNode node) {
        return node == null ? "(unset)" : node.toString();
    }

    private static String escape(String text) {
        return text.replace("|", "\\|");
    }
}
