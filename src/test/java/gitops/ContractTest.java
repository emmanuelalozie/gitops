package gitops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import gitops.providers.ApiException;
import gitops.providers.UsersProvider;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static gitops.TestData.user;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The JSON the desktop app reads. Each case must match its schema and the golden file in
 * desktop/src/fixtures, which the app's own tests read too, so changing the format breaks both builds.
 * After a deliberate change, regenerate the files: ./gradlew test -Dgitops.updateFixtures=true
 */
class ContractTest {

    private static final Path FIXTURES = Path.of("desktop/src/fixtures");

    private static JsonNode schema(String name) throws IOException {
        try (InputStream in = ContractTest.class.getResourceAsStream("/schemas/output/" + name)) {
            return Json.MAPPER.readTree(in);
        }
    }

    private static void assertMatchesFixture(String name, JsonNode actual) throws IOException {
        Path file = FIXTURES.resolve(name);
        if (Boolean.getBoolean("gitops.updateFixtures")) {
            Json.write(file, actual);
        }
        assertThat(file).as("missing fixture; create it with -Dgitops.updateFixtures=true").exists();
        assertThat(Json.read(file)).as("%s (regenerate with -Dgitops.updateFixtures=true)", file).isEqualTo(actual);
    }

    private static ObjectNode samplePlan() {
        ObjectNode bret = user(1, "Bret", "new@b.io");
        ObjectNode moved = user(7, "Elwyn.Skiles", "e@b.io");
        Plan plan = Planner.plan("dev", "users @ http://127.0.0.1:3000", Kind.USERS, true,
                List.of(bret, moved, user(5, "Moriah.Stanton", "m@b.io")),
                List.of(user(1, "Bret", "old@b.io"), user(72, "Elwyn.Skiles", "e@b.io"), user(5, "Moriah.Stantonite", "m@b.io")));
        return PlanJson.toJson(plan, Kind.USERS, new UsersProvider("http://127.0.0.1:3000"));
    }

    @Test
    void planMatchesItsSchemaAndTheFixture() throws IOException {
        ObjectNode plan = samplePlan();
        Schema schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(Json.compact(schema("plan.v1.schema.json")), InputFormat.JSON);

        assertThat(schema.validate(Json.compact(plan), InputFormat.JSON)).isEmpty();
        assertMatchesFixture("plan.json", plan);
    }

    @Test
    void errorMatchesTheFixture() throws IOException {
        ObjectNode error = EngineError.toJson(new ApiException("PATCH", "http://127.0.0.1:3000/users/1", 500, "boom"));

        assertMatchesFixture("error.json", error);
    }
}
