package hudson.plugins.swarm;

/**
 * Indicates a graful error reporting that doesn't need the stack dump.
 *
 * @author Kohsuke Kawaguchi
 */
public class RetryException extends Exception {

    private static final long serialVersionUID = -9058647821506211062L;

    public RetryException(String message) {
        super(message);
    }

    public RetryException(String message, Throwable cause) {
        super(message, cause);
    }
}
