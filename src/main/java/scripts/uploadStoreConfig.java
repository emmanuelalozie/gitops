
class uploadStoreConfig {

    private static script uploadStoreConfig = new script();

    public static void main(String[] args) {
        // loginAdmin()
        uploadStoreConfig.run(); 
    }

    public static void run() {
        // uploadStoreConfig()
        String configFile = System.getEnv("CONFIG_FILE");
        if (configFilePath == null) {
            System.out.println("ERROR: CONFIG_FILE not set");
            System.exit(1);
        }
        makeAPICall();
    }

    public static void makeAPICall(String configFile) {
        String configuration = "";
        try {
            InputStream inputStream = uploadStoreConfig.class.getResourceAsStream("/storeConfiguration.json");
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
            StringBuilder stringBuilder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                stringBuilder.append(line);
            }
            configuration = stringBuilder.toString();
        } catch (IOException e) {
            System.out.println("Error reading storeConfiguration.json file: " + e.getMessage());
        }
        response = .given()
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .body(configuration)
            .when()
            .post("https://api.yourdomain.com/v1/store")
            .then()
            .extract().response();

    }

    
}
