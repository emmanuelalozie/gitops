package scripts;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.commons.configuration2.io.FileHandler;
import lombok.SneakyThrows;

class DownloadUsers {

    public static void main(String[] args) {
        DownloadUsers script = new DownloadUsers();
        script.run();
    }

    private void run() {
        String response = FetchUsers.fetchUserData();
        System.out.println(response);
        downloadUserData(response);
    }

    @SneakyThrows
    private void downloadUserData(String response) {

        // Convert the JSON response to a JSONArray
        JSONArray users = new JSONArray(response);

        for (int i = 0; i < users.length(); i++) {
            
            JSONObject user = users.getJSONObject(i);

            // Get the userName, remove spaces and convert to camelCase
            String userName = user.getString("username").replaceAll(" ", "");
            String formattedUserName = userName.toLowerCase();
            Integer userId = user.getInt("id");
            String userEmail = user.getString("email");
            String userPhone = user.getString("phone");

            String filePath = "src/main/resources/users/" + formattedUserName + ".properties";
            File file = new File(filePath);

            // Ensure parent directories exist and file is created if it does not exist
            if (!file.exists()) {
                file.getParentFile().mkdirs(); // Create parent directories
                file.createNewFile(); // Create the file
            }
            
            PropertiesConfiguration config = new PropertiesConfiguration();
            FileHandler handler = new FileHandler(config);
            // FileHandler checks if file already exists, if not it creates a new file
            handler.setFile(file);

            // Store properties in the file
            config.setProperty("name", userName);
            config.setProperty("id", userId);
            config.setProperty("email", userEmail);
            config.setProperty("phone", userPhone);

            // Save the properties to the file
            handler.save();
        }       

    }
}
