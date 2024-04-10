package scripts;

import org.json.JSONArray;
import org.json.JSONObject;

import lombok.SneakyThrows;

import java.io.File;
import java.util.Properties;
import java.io.FileInputStream;

public class CompareUserData {

        public static void main(String[] args) {
            CompareUserData script = new CompareUserData();
            script.run();
        }
    
        private void run() {
            compareUserData();
        }
    
        @SneakyThrows
        private void compareUserData() {
            System.out.println("Comparing user data...");
            String response = FetchUsers.fetchUserData();
            JSONArray users = new JSONArray(response);

            for (int i = 0; i < users.length(); i++) {
                JSONObject user = users.getJSONObject(i);
                String userName = user.getString("username").replaceAll(" ", "");
                String formattedUserName = userName.toLowerCase();
                Integer userId = user.getInt("id");
                String userEmail = user.getString("email");
                String userPhone = user.getString("phone");
    
                String filePath = "src/main/resources/users/" + formattedUserName + ".properties";
                File file = new File(filePath);
    
                if (!file.exists()) {
                    System.out.println("User data file for " + userName + " does not exist.");
                    continue;
                }

                Properties properties = new Properties();
                properties.load(new FileInputStream(file));
                String storedUserName = properties.getProperty("name");
                Integer storedUserId = Integer.parseInt(properties.getProperty("id"));
                String storedUserEmail = properties.getProperty("email");
                String storedUserPhone = properties.getProperty("phone");


                // Compare the stored user data with the fetched user data
                if (!userName.equals(storedUserName)) {
                    System.out.println("User name mismatch for " + userName);
                }
                else if (!userId.equals(storedUserId)) {
                    System.out.println("User ID mismatch for " + userName);
                }
                else if (!userEmail.equals(storedUserEmail)) {
                    System.out.println("User email mismatch for " + userName);
                }
                else if (!userPhone.equals(storedUserPhone)) {
                    System.out.println("User phone mismatch for " + userName);
                }
                else {
                    System.out.println("User data for " + userName + " is up to date.");}
            }
        }

}
