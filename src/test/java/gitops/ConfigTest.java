package gitops;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static gitops.TestData.user;
import static org.assertj.core.api.Assertions.assertThat;

class ConfigTest {

    @TempDir
    Path root;

    private Target usersTarget() throws Exception {
        Files.createDirectories(root.resolve("dev/users"));
        Files.writeString(root.resolve("dev/target.json"), """
                {"kind": "users", "baseUrl": "http://localhost:3000", "prune": true}""");
        return Target.load(root, "dev");
    }

    @Test
    void committedConfigIsValid() {
        for (Target target : Target.loadAll(Path.of("config"))) {
            assertThat(ConfigValidator.validate(target)).as(target.name()).isEmpty();
        }
    }

    @Test
    void fileNamesAreDerivedFromTheKey() {
        assertThat(ConfigStore.fileName("Leopoldo_Corkery")).isEqualTo("leopoldo_corkery.json");
        assertThat(ConfigStore.fileName("Elwyn.Skiles")).isEqualTo("elwyn.skiles.json");
        assertThat(ConfigStore.fileName("good first issue")).isEqualTo("good-first-issue.json");
    }

    @Test
    void replaceAllKeepsOnlySchemaFieldsAndRemovesStaleFiles() throws Exception {
        Target target = usersTarget();
        Files.writeString(target.resourceDir().resolve("old.json"), "{}");
        var live = user(1, "Bret", "a@b.io");
        live.put("website", "hildegard.org");

        target.store().replaceAll(List.of(live));

        assertThat(target.store().files()).extracting(p -> p.getFileName().toString()).containsExactly("bret.json");
        assertThat(target.store().load()).containsExactly(user(1, "Bret", "a@b.io"));
    }

    @Test
    void validatorReportsSchemaNamingAndUniquenessProblems() throws Exception {
        Target target = usersTarget();
        Path dir = target.resourceDir();
        Json.write(dir.resolve("bret.json"), user(1, "Bret", "a@b.io"));
        Json.write(dir.resolve("wrong-name.json"), user(1, "Antonette", "c@d.io"));
        Files.writeString(dir.resolve("samantha.json"), """
                {"id": "three", "username": "Samantha", "email": "not-an-email", "phone": "1", "extra": true}""");
        Files.writeString(dir.resolve("broken.json"), "{ not json");

        List<String> errors = ConfigValidator.validate(target);

        assertThat(errors).anyMatch(e -> e.contains("wrong-name.json: file name must be antonette.json"));
        assertThat(errors).anyMatch(e -> e.contains("id 1 is already used by"));
        assertThat(errors).anyMatch(e -> e.contains("samantha.json") && e.contains("integer"));
        assertThat(errors).anyMatch(e -> e.contains("samantha.json") && e.contains("email"));
        assertThat(errors).anyMatch(e -> e.contains("samantha.json") && e.contains("extra"));
        assertThat(errors).anyMatch(e -> e.contains("broken.json is not valid JSON"));
    }

    @Test
    void replaceAllRefusesKeysThatShareAFileName() throws Exception {
        Target target = usersTarget();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> target.store().replaceAll(
                        List.of(user(1, "Bret", "a@b.io"), user(2, "bret", "c@d.io"))))
                .hasMessageContaining("bret.json");
        assertThat(target.store().files()).isEmpty();
    }

    @Test
    void validatorRequiresTheResourceFolder() throws Exception {
        Files.createDirectories(root.resolve("empty"));
        Files.writeString(root.resolve("empty/target.json"), "{\"kind\": \"users\", \"baseUrl\": \"http://x\"}");

        assertThat(ConfigValidator.validate(Target.load(root, "empty"))).anyMatch(e -> e.contains("does not exist"));
    }

    @Test
    void baseUrlOverrideIsPerTarget() throws Exception {
        assertThat(usersTarget().baseUrlVariable()).isEqualTo("GITOPS_DEV_BASE_URL");
    }

    @Test
    void validatorRequiresTheEndpointSetting() throws Exception {
        Files.createDirectories(root.resolve("x"));
        Files.writeString(root.resolve("x/target.json"), "{\"kind\": \"labels\"}");

        assertThat(ConfigValidator.validate(Target.load(root, "x")))
                .anyMatch(e -> e.contains("must set \"repository\""));
    }
}
