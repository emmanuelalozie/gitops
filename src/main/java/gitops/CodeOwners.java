package gitops;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Per-target reviewers: "owners" in a target.json (GitHub users or teams, such as "@alice" or "@org/platform")
 * become a generated block in .github/CODEOWNERS for that target's folder. With "require_code_owner_reviews" on
 * in branch protection, a change to config/prod/ then needs one of prod's owners to approve. The block goes last,
 * since in CODEOWNERS the last matching line wins. {@code ./gradlew codeowners} writes it; lint says when it's stale.
 */
public final class CodeOwners {

    static final String BEGIN = "# BEGIN gitops: generated from \"owners\" in config/*/target.json; change them there and run ./gradlew codeowners";
    static final String END = "# END gitops";
    static final Pattern OWNER = Pattern.compile("@[A-Za-z0-9](?:[A-Za-z0-9-]*)(?:/[A-Za-z0-9._-]+)?|[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    private CodeOwners() {
    }

    public static Path file(Path repo) {
        return repo.resolve(".github").resolve("CODEOWNERS");
    }

    /** The owners a target lists (empty when none). */
    public static List<String> owners(Target target) {
        List<String> owners = new ArrayList<>();
        target.settings().path("owners").forEach(o -> owners.add(o.asText()));
        return owners;
    }

    /** The generated lines, or "" when no target has owners. */
    public static String block(List<Target> targets) {
        StringBuilder lines = new StringBuilder();
        for (Target target : targets) {
            List<String> owners = owners(target);
            if (!owners.isEmpty()) {
                lines.append("/config/").append(target.name()).append("/ ").append(String.join(" ", owners)).append('\n');
            }
        }
        return lines.isEmpty() ? "" : BEGIN + "\n" + lines + END + "\n";
    }

    /** The CODEOWNERS text with the generated block replaced (or added at the end, or removed when empty). */
    static String render(String existing, String block) {
        int begin = existing.indexOf(BEGIN);
        int end = existing.indexOf(END, Math.max(begin, 0));
        String rest = existing;
        if (begin >= 0 && end > begin) {
            rest = existing.substring(0, begin) + existing.substring(Math.min(existing.length(), end + END.length() + 1));
        }
        rest = rest.replaceAll("\\n+$", "");
        if (block.isEmpty()) {
            return rest.isEmpty() ? "" : rest + "\n";
        }
        return (rest.isEmpty() ? "" : rest + "\n\n") + block;
    }

    public static boolean upToDate(Path repo, List<Target> targets) {
        String block = block(targets);
        String existing = read(repo);
        return render(existing, block).equals(existing.isEmpty() && block.isEmpty() ? "" : existing);
    }

    /** Writes the block; returns whether the file changed. */
    public static boolean write(Path repo, List<Target> targets) {
        String existing = read(repo);
        String updated = render(existing, block(targets));
        if (updated.equals(existing)) {
            return false;
        }
        try {
            Files.createDirectories(file(repo).getParent());
            Files.writeString(file(repo), updated);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return true;
    }

    private static String read(Path repo) {
        try {
            return Files.isRegularFile(file(repo)) ? Files.readString(file(repo)) : "";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
