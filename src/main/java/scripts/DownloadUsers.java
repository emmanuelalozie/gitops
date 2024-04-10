package scripts;

class DownloadStoreConfig {

    public static void main(String[] args) {
        DownloadStoreConfig script = new DownloadStoreConfig();
        script.run();
    }

    private void run() {
        String response = FetchUsers.fetchUserData();
        System.out.println(response);
        downloadUserData(response);
    }

    private void downloadUserData(String response) {
        // The JSON response is a list of users
        // We can save each user as a separate Properties file
        // The file name will be the user's name


    }
}
