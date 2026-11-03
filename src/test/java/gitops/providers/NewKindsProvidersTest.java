package gitops.providers;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import gitops.Applier;
import gitops.Json;
import gitops.Kind;
import gitops.Plan;
import gitops.Planner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The generic REST provider and GitHub Actions variables. */
class NewKindsProvidersTest {

    @RegisterExtension
    static WireMockExtension api = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private static final ObjectNode SCHEMA = (ObjectNode) Json.parse("""
            {"type": "object", "properties": {"id": {"type": "integer"}, "sku": {"type": "string"}, "quantity": {"type": "integer"}}}""");
    private static final Kind ITEMS = Kind.rest("items", "sku", "id", SCHEMA);

    private static ObjectNode item(int id, String sku, int quantity) {
        return Json.MAPPER.createObjectNode().put("id", id).put("sku", sku).put("quantity", quantity);
    }

    @Test
    void restPatchesOnlyChangedFieldsAndUsesTheIdInUrls() {
        api.stubFor(patch("/api/items/7").willReturn(okJson("{}")));
        RestProvider provider = new RestProvider(api.baseUrl(), "/api/items", "id", "PATCH", null);
        Plan.Change change = Planner.plan("t", "t", ITEMS, true, List.of(item(7, "WID-001", 120)),
                List.of(item(7, "WID-001", 118))).changes().get(0);

        provider.update(change.live(), change.desired(), change.diffs());

        api.verify(patchRequestedFor(urlEqualTo("/api/items/7")).withRequestBody(equalToJson("{\"quantity\": 120}"))
                .withoutHeader("Authorization"));
    }

    @Test
    void restCanPutWholeResourcesAndSendsItsOwnTokenOnly() {
        api.stubFor(put("/items/7").willReturn(okJson("{}")));
        RestProvider provider = new RestProvider(api.baseUrl(), "/items/", "id", "put", "secret");
        Plan.Change change = Planner.plan("t", "t", ITEMS, true, List.of(item(7, "WID-001", 120)),
                List.of(item(7, "WID-001", 118))).changes().get(0);

        provider.update(change.live(), change.desired(), change.diffs());

        api.verify(putRequestedFor(urlEqualTo("/items/7"))
                .withRequestBody(equalToJson("{\"id\": 7, \"sku\": \"WID-001\", \"quantity\": 120}"))
                .withHeader("Authorization", equalTo("Bearer secret")));
    }

    @Test
    void restReplacesAResourceWhoseIdChanges() {
        api.stubFor(delete("/items/1").willReturn(okJson("{}")));
        api.stubFor(post("/items").willReturn(jsonResponse("{}", 201)));
        RestProvider provider = new RestProvider(api.baseUrl(), "/items", "id", "PATCH", null);
        Plan plan = Planner.plan("t", "t", ITEMS, true, List.of(item(2, "WID-001", 1)), List.of(item(1, "WID-001", 1)));

        Applier.apply(plan, provider, System.out);

        api.verify(deleteRequestedFor(urlEqualTo("/items/1")));
        api.verify(postRequestedFor(urlEqualTo("/items")).withRequestBody(matchingJsonPath("$.id", equalTo("2"))));
    }

    @Test
    void restRefusesBadSettings() {
        assertThatThrownBy(() -> new RestProvider(api.baseUrl(), "items", "id", "PATCH", null)).hasMessageContaining("start with /");
        assertThatThrownBy(() -> new RestProvider(api.baseUrl(), "/items", "id", "POST", null)).hasMessageContaining("PATCH or PUT");
    }

    @Test
    void restKindsAreDefinedSafely() {
        assertThat(ITEMS.uniqueFields()).containsExactly("id");
        assertThat(ITEMS.type()).isEqualTo("rest");
        assertThat(Kind.rest("items", "id", "id", SCHEMA).uniqueFields()).isEmpty();
        assertThatThrownBy(() -> Kind.rest("Items!", "sku", "id", SCHEMA)).hasMessageContaining("folder name");
        assertThatThrownBy(() -> Kind.rest("labels", "sku", "id", SCHEMA)).hasMessageContaining("built-in");
        assertThatThrownBy(() -> Kind.rest("items", "code", "id", SCHEMA)).hasMessageContaining("key field 'code'");
    }

    @Test
    void actionsVariablesArePagedAndChangedByName() {
        StringBuilder page1 = new StringBuilder("{\"total_count\": 31, \"variables\": [");
        for (int i = 0; i < 30; i++) {
            page1.append(i == 0 ? "" : ",").append("{\"name\": \"VAR_").append(i).append("\", \"value\": \"v\", \"created_at\": \"x\"}");
        }
        api.stubFor(get(urlPathEqualTo("/repos/o/r/actions/variables")).withQueryParam("page", equalTo("1"))
                .willReturn(okJson(page1.append("]}").toString())));
        api.stubFor(get(urlPathEqualTo("/repos/o/r/actions/variables")).withQueryParam("page", equalTo("2"))
                .willReturn(okJson("{\"total_count\": 31, \"variables\": [{\"name\": \"REGION\", \"value\": \"eu\"}]}")));
        api.stubFor(patch("/repos/o/r/actions/variables/REGION").willReturn(okJson("{}")));
        GitHubActionsVariablesProvider provider = new GitHubActionsVariablesProvider(api.baseUrl(), "o/r", "t");

        List<ObjectNode> live = provider.list();
        assertThat(live).hasSize(31);
        assertThat(live.get(30).toString()).isEqualTo("{\"name\":\"REGION\",\"value\":\"eu\"}");

        Plan plan = Planner.plan("t", "t", Kind.ACTIONS_VARIABLES, false,
                List.of(Json.MAPPER.createObjectNode().put("name", "REGION").put("value", "us")), live);
        Applier.apply(plan, provider, System.out);
        api.verify(patchRequestedFor(urlEqualTo("/repos/o/r/actions/variables/REGION"))
                .withRequestBody(equalToJson("{\"name\": \"REGION\", \"value\": \"us\"}")));
    }
}
