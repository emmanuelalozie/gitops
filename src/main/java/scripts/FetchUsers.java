package scripts;

import static io.restassured.RestAssured.given;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import lombok.SneakyThrows;

public class FetchUsers {

    public static void main(String[] args) {
        FetchUsers script = new FetchUsers();
        script.run();
    }

    private void run() {
        String response = fetchUserData();
        System.out.println(response);
    }
    
    @SneakyThrows
    public static String fetchUserData() {

        RestAssured.baseURI = "https://jsonplaceholder.typicode.com";

        Response response = given()
                .when()
                .get("/users")
                .then()
                .assertThat()
                .statusCode(200)
                .extract().response();

        String responseBody = response.getBody().asString();
        return responseBody;

    }
}
