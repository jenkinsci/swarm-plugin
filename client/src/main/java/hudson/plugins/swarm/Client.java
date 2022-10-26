package hudson.plugins.swarm;

import org.apache.hc.core5.http.ParseException;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.NamedOptionDef;
import org.kohsuke.args4j.spi.FieldSetter;
import org.kohsuke.args4j.spi.OptionHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Client {

    private static final Logger logger = Logger.getLogger(Client.class.getName());
    private static final String NON_FATAL_JNLP_AGENT_ENDPOINT_RESOLUTION_EXCEPTIONS =
            "hudson.remoting.Engine.nonFatalJnlpAgentEndpointResolutionExceptions";

    // TODO: Cleanup the encoding issue
    public static void main(String... args) throws InterruptedException {
        Options options = new Options();
        CmdLineParser parser = new CmdLineParser(options);
        try {
            parser.parseArgument(args);
        } catch (CmdLineException e) {
            fail(e.getMessage());
        }

        logArguments(parser);

        if (options.help) {
            parser.printUsage(System.out);
            System.exit(0);
        }

        if (options.config != null) {
            if (hasConflictingOptions(parser)) {
                fail("'-config' can not be used with other options.");
            }
            logger.log(Level.INFO, "Load configuration from {0}", options.config.getPath());

            try (InputStream is = Files.newInputStream(options.config.toPath())) {
                options = new YamlConfig().loadOptions(is);
            } catch (InvalidPathException | IOException | ConfigurationException e) {
                fail(e.getMessage());
            }
        }

        try {
            validateOptions(options);
        } catch (RuntimeException e) {
            fail(e.getMessage());
        }

        // Pass the command line arguments along so that the LabelFileWatcher thread can have them.
        run(new SwarmClient(options), options, args);
    }

    private static boolean hasConflictingOptions(CmdLineParser parser) {
        return parser.getOptions().stream()
                .anyMatch(
                        oh ->
                                !getKey(oh).equals("-config")
                                        && !isDefaultOption(
                                                getKey(oh),
                                                getValue(oh),
                                                new CmdLineParser(new Options())));
    }

    private static void validateOptions(Options options) {
        if (options.url == null) {
            throw new IllegalArgumentException("Missing 'url' option.");
        }
        if (options.pidFile != null) {
            ProcessHandle current = ProcessHandle.current();
            Path pidFile = Paths.get(options.pidFile);
            if (Files.exists(pidFile)) {
                long oldPid;
                try {
                    oldPid = Long.parseLong(Files.readString(pidFile, StandardCharsets.US_ASCII));
                } catch (NumberFormatException e) {
                    oldPid = 0;
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to read PID file " + pidFile, e);
                }
                // check if this process is running
                if (oldPid > 0) {
                    Optional<ProcessHandle> oldProcess = ProcessHandle.of(oldPid);
                    if (oldProcess.isPresent() && oldProcess.get().isAlive()) {
                        // If the old process is running, then compare its path to the path of this
                        // process as a quick sanity check. If they are the same, we can assume that
                        // it is probably another Swarm Client instance, in which case the service
                        // should not be started. However, if the previous Swarm Client instance
                        // failed to exit cleanly (because of a crash/reboot/etc.) and another
                        // process is now using that PID, then we should consider the PID file stale
                        // and continue execution.
                        String curCommand = current.info().command().orElse(null);
                        String oldCommand = oldProcess.get().info().command().orElse(null);
                        if (curCommand != null && curCommand.equals(oldCommand)) {
                            throw new IllegalStateException(
                                    String.format(
                                            "Refusing to start because PID file '%s' already exists"
                                                    + " and the previous process %d (%s) is still"
                                                    + " running.",
                                            pidFile.toAbsolutePath(),
                                            oldPid,
                                            oldProcess.get().info().commandLine().orElse("")));
                        } else {
                            logger.fine(
                                    String.format(
                                            "Ignoring stale PID file '%s' because the process %d is"
                                                    + " not a Swarm Client.",
                                            pidFile.toAbsolutePath(), oldPid));
                        }
                    } else {
                        logger.fine(
                                String.format(
                                        "Ignoring PID file '%s' because the previous process %d is"
                                                + " no longer running.",
                                        pidFile.toAbsolutePath(), oldPid));
                    }
                }
            }
            pidFile.toFile().deleteOnExit();
            try {
                Files.writeString(pidFile, Long.toString(current.pid()), StandardCharsets.US_ASCII);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to write PID file " + options.pidFile, e);
            }
        }

        // Check to see if passwordEnvVariable is set, if so pull down the
        // password from the env and set as password.
        if (options.passwordEnvVariable != null) {
            options.password = System.getenv(options.passwordEnvVariable);
        }
        // read pass from file if no other password was specified
        if (options.password == null && options.passwordFile != null) {
            try {
                options.password =
                        Files.readString(Paths.get(options.passwordFile), StandardCharsets.UTF_8)
                                .trim();
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read password from file", e);
            }
        }

        /*
         * Only look up the hostname if we have not already specified name of the agent. In certain
         * cases this lookup might fail (e.g., querying an external DNS server which might not be
         * informed of a newly created agent from a DHCP server).
         *
         * From https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/net/InetAddress.html#getCanonicalHostName()
         *
         * "Gets the fully qualified domain name for this IP address. Best effort method, meaning we
         * may not be able to return the FQDN depending on the underlying system configuration."
         */
        if (options.name == null) {
            try {
                options.name = InetAddress.getLocalHost().getCanonicalHostName();
            } catch (UnknownHostException e) {
                logger.severe(
                        "Failed to look up the canonical hostname of this agent. Check the system"
                                + " DNS settings.");
                logger.severe(
                        "If it is not possible to resolve this host, specify a name using the"
                                + " \"-name\" option.");
                throw new UncheckedIOException("Failed to set hostname", e);
            }
        }
    }

    /**
     * Run the Swarm client.
     *
     * <p>This method never returns.
     */
    static void run(SwarmClient swarmClient, Options options, String... args)
            throws InterruptedException {
        logger.info("Connecting to Jenkins controller");
        URL url = swarmClient.getUrl();

        // wait until we get the ACK back
        int retry = 0;
        while (true) {
            try {
                logger.info("Attempting to connect to " + url);

                /*
                 * Create a new Swarm agent. After this method returns, the value of the name field
                 * has been set to the name returned by the server, which may or may not be the name
                 * we originally requested.
                 */
                swarmClient.createSwarmAgent(url);

                /*
                 * Set up the label file watcher thread. If the label file changes, this thread
                 * takes action to restart the client. Note that this must be done after we create
                 * the Swarm agent, since only then has the server returned the name we must use
                 * when doing label operations.
                 */
                if (options.labelsFile != null) {
                    logger.info("Setting up LabelFileWatcher");
                    LabelFileWatcher l =
                            new LabelFileWatcher(url, options, swarmClient.getName(), args);
                    Thread labelFileWatcherThread = new Thread(l, "LabelFileWatcher");
                    labelFileWatcherThread.setDaemon(true);
                    labelFileWatcherThread.start();
                }

                /*
                 * Prevent Remoting from killing the process on JNLP agent endpoint resolution
                 * exceptions.
                 */
                if (System.getProperty(NON_FATAL_JNLP_AGENT_ENDPOINT_RESOLUTION_EXCEPTIONS)
                        == null) {
                    System.setProperty(NON_FATAL_JNLP_AGENT_ENDPOINT_RESOLUTION_EXCEPTIONS, "true");
                }

                /*
                 * Note that any instances of InterruptedException or RuntimeException thrown
                 * internally by the next two lines get wrapped in RetryException.
                 */
                List<String> jnlpArgs = swarmClient.getJnlpArgs(url);
                swarmClient.connect(jnlpArgs, url);
                if (options.noRetryAfterConnected) {
                    logger.warning("Connection closed, exiting...");
                    swarmClient.exitWithStatus(0);
                }
            } catch (IOException | ParseException | RetryException e) {
                logger.log(Level.SEVERE, "An error occurred", e);
            }

            int waitTime =
                    options.retryBackOffStrategy.waitForRetry(
                            retry++, options.retryInterval, options.maxRetryInterval);
            if (options.retry >= 0) {
                if (retry >= options.retry) {
                    logger.severe("Retry limit reached, exiting...");
                    swarmClient.exitWithStatus(1);
                } else {
                    logger.warning("Remaining retries: " + (options.retry - retry));
                }
            }

            // retry
            logger.info("Retrying in " + waitTime + " seconds");
            swarmClient.sleepSeconds(waitTime);
        }
    }

    private static void logArguments(CmdLineParser parser) {
        Options defaultOptions = new Options();
        CmdLineParser defaultParser = new CmdLineParser(defaultOptions);

        StringBuilder sb = new StringBuilder("Client invoked with: ");
        for (OptionHandler<?> argument : parser.getArguments()) {
            logValue(sb, argument, null);
        }
        for (OptionHandler<?> option : parser.getOptions()) {
            logValue(sb, option, defaultParser);
        }
        logger.info(sb.toString());
    }

    private static void logValue(
            StringBuilder sb, OptionHandler<?> handler, CmdLineParser defaultParser) {
        String key = getKey(handler);
        Object value = getValue(handler);

        if (handler.option.help()) {
            return;
        }

        if (defaultParser != null && isDefaultOption(key, value, defaultParser)) {
            return;
        }

        sb.append(key);
        sb.append(' ');
        if (key.equals("-username") || key.startsWith("-password")) {
            sb.append("*****");
        } else {
            sb.append(value);
        }
        sb.append(' ');
    }

    private static String getKey(OptionHandler<?> optionHandler) {
        if (optionHandler.option instanceof NamedOptionDef) {
            NamedOptionDef namedOptionDef = (NamedOptionDef) optionHandler.option;
            return namedOptionDef.name();
        } else {
            return optionHandler.option.toString();
        }
    }

    private static Object getValue(OptionHandler<?> optionHandler) {
        FieldSetter setter = optionHandler.setter.asFieldSetter();
        return setter == null ? null : setter.getValue();
    }

    private static boolean isDefaultOption(String key, Object value, CmdLineParser defaultParser) {
        for (OptionHandler<?> defaultOption : defaultParser.getOptions()) {
            String defaultKey = getKey(defaultOption);
            if (defaultKey.equals(key)) {
                Object defaultValue = getValue(defaultOption);
                if (defaultValue == null && value == null) {
                    return true;
                }
                return defaultValue != null && defaultValue.equals(value);
            }
        }
        return false;
    }

    private static void fail(String message) {
        System.err.println(message);
        System.exit(1);
    }
}
