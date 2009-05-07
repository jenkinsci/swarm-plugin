package hudson.plugins.swarm;

/**
 * Indicates a graful error reporting that doesn't need the stack dump.
 *
 * @author Kohsuke Kawaguchi
 */
public class RetryException extends Exception {
    public RetryException(String message) {
        super(message);
    }

    public RetryException(String message, Throwable cause) {
        super(message, cause);
    }
}
