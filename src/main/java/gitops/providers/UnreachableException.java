package gitops.providers;

/** A live system could not be reached at all: refused connection, unknown host, timeout. */
public class UnreachableException extends RuntimeException {

    private final String method;
    private final String url;

    public UnreachableException(String method, String url, Exception cause) {
        super("can't connect to the live system: " + method + " " + url + " ("
                + (cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage()) + "). Is it running?",
                cause);
        this.method = method;
        this.url = url;
    }

    public String method() {
        return method;
    }

    public String url() {
        return url;
    }
}
