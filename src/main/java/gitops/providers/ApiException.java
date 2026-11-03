package gitops.providers;

/** A live system answered a request with an error status. */
public class ApiException extends RuntimeException {

    private final String method;
    private final String url;
    private final int status;
    private final String body;

    public ApiException(String method, String url, int status, String body) {
        super(method + " " + url + " failed with HTTP " + status + (body == null || body.isBlank() ? "" : ": " + body));
        this.method = method;
        this.url = url;
        this.status = status;
        this.body = body == null ? "" : body;
    }

    public String method() {
        return method;
    }

    public String url() {
        return url;
    }

    public int status() {
        return status;
    }

    public String body() {
        return body;
    }
}
