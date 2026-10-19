package gitops.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gitops.Json;
import io.restassured.http.ContentType;
import io.restassured.http.Method;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

import static io.restassured.RestAssured.given;

/** Thin RestAssured wrapper: JSON in, JSON out, and an exception on any non-2xx status. */
final class Http {

    private final String baseUrl;
    private final Map<String, String> headers;

    Http(String baseUrl, Map<String, String> headers) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.headers = headers;
    }

    RequestSpecification spec() {
        return given().baseUri(baseUrl).headers(headers).accept(ContentType.JSON);
    }

    Response send(RequestSpecification spec, Method method, String path, JsonNode body, Object... pathParams) {
        if (body != null) {
            spec.contentType(ContentType.JSON).body(Json.compact(body));
        }
        Response response = spec.request(method, path, pathParams);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ApiException(method + " " + baseUrl + expand(path, pathParams), response.statusCode(), response.asString());
        }
        return response;
    }

    Response send(Method method, String path, JsonNode body, Object... pathParams) {
        return send(spec(), method, path, body, pathParams);
    }

    static List<ObjectNode> objects(Response response) {
        JsonNode node = Json.parse(response.asString());
        if (!node.isArray()) {
            throw new IllegalStateException("Expected a JSON array but got: " + node);
        }
        List<ObjectNode> objects = new ArrayList<>();
        node.forEach(n -> objects.add((ObjectNode) n));
        return objects;
    }

    private static String expand(String path, Object... params) {
        String result = path;
        for (Object param : params) {
            result = result.replaceFirst("\\{[^}]+}", Matcher.quoteReplacement(String.valueOf(param)));
        }
        return result;
    }
}
