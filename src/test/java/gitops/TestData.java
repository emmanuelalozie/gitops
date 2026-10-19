package gitops;

import com.fasterxml.jackson.databind.node.ObjectNode;

final class TestData {

    private TestData() {
    }

    static ObjectNode user(int id, String username, String email) {
        return Json.MAPPER.createObjectNode()
                .put("id", id).put("username", username).put("email", email).put("phone", "555-0100");
    }

    static ObjectNode label(String name, String color) {
        return Json.MAPPER.createObjectNode().put("name", name).put("color", color);
    }
}
