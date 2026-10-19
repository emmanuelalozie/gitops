package gitops.providers;

/** A live system answered a request with an error status. */
public class ApiException extends RuntimeException {

    private final int status;

    public ApiException(String request, int status, String body) {
        super(request + " failed with HTTP " + status + (body == null || body.isBlank() ? "" : ": " + body));
        this.status = status;
    }

    public int status() {
        return status;
    }
}
