package hudson.plugins.swarm;

/** Signals an error in the configuration. */
public class ConfigurationException extends Exception {
    private static final long serialVersionUID = 1L;

    public ConfigurationException(String message) {
        super(message);
    }
}
