package hudson.plugins.swarm;

import org.apache.commons.lang.math.NumberUtils;
import org.apache.hc.core5.http.ParseException;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.NamedOptionDef;
import org.kohsuke.args4j.spi.FieldSetter;
import org.kohsuke.args4j.spi.OptionHandler;

import oshi.SystemInfo;
import oshi.software.os.OSProcess;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Client {

    private static final Logger logger = Logger.getLogger(Client.class.getName());

    // TODO: Cleanup the encoding issue
    public static void main(String... args) throws InterruptedException {
        Options options = new Options();
        CmdLineParser parser = new CmdLineParser(options);
        try {
            parser.parseArgument(args);
        } catch (CmdLineException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }

        logArguments(parser);

        if (options.help) {
            parser.printUsage(System.out);
            System.exit(0);
        }

        validateOptions(options);

        // Pass the command line arguments along so that the LabelFileWatcher thread can have them.
        run(new SwarmClient(options), options, args);
    }

    private static void validateOptions(Options options) {
        if (options.pidFile != null) {
            /*
             * This will return a string like 12345@hostname, so we need to do some string
             * manipulation to get the actual process identifier. In Java 9, this can be replaced
             * with: ProcessHandle.current().getPid();
             */
            String pidName = ManagementFactory.getRuntimeMXBean().getName();
            String[] pidNameParts = pidName.split("@");
            String pid = pidNameParts[0];
            Path pidFile = Paths.get(options.pidFile);
            if (Files.exists(pidFile)) {
                int oldPid;
                try {
                    oldPid =
                            NumberUtils.toInt(
                                    new String(Files.readAllBytes(pidFile), StandardCharsets.UTF_8),
                                    0);
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to read PID file " + pidFile, e);
                }
                // check if this process is running
                if (oldPid > 0) {
                    OSProcess oldProcess = new SystemInfo().getOperatingSystem().getProcess(oldPid);
                    if (oldProcess != null) {
                        throw new RuntimeException(
                                String.format(
                                        "Refusing to start because PID file '%s' already exists"
                                                + " and the previous process %d (%s) is still"
                                                + " running.",
                                        pidFile.toAbsolutePath(),
                                        oldPid,
                                        oldProcess.getCommandLine()));
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
                Files.write(pidFile, pid.getBytes(StandardCharsets.UTF_8));
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
                        new String(
                                        Files.readAllBytes(Paths.get(options.passwordFile)),
                                        StandardCharsets.UTF_8)
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
         * From https://docs.oracle.com/javase/8/docs/api/java/net/InetAddress.html#getCanonicalHostName--
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
}
