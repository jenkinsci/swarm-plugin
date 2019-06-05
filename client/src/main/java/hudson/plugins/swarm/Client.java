package hudson.plugins.swarm;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.lang.math.NumberUtils;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.NamedOptionDef;
import org.kohsuke.args4j.spi.FieldSetter;
import org.kohsuke.args4j.spi.OptionHandler;
import oshi.SystemInfo;
import oshi.software.os.OSProcess;

/**
 * Swarm client.
 * <p>
 * Discovers nearby Jenkins via UDP broadcast, and picks eligible one randomly and
 * joins it.
 *
 * @author Kohsuke Kawaguchi
 */
public class Client {

    private static final Logger logger = Logger.getLogger(Client.class.getPackage().getName());

    private final Options options;

    //TODO: Cleanup the encoding issue
    public static void main(String... args) throws InterruptedException, IOException {
        Options options = new Options();
        Client client = new Client(options);
        CmdLineParser p = new CmdLineParser(options);
        try {
            p.parseArgument(args);
        } catch (CmdLineException e) {
            logger.log(Level.SEVERE, "CmdLineException occurred during parseArgument", e);
            p.printUsage(System.out);
            System.exit(1);
        }

        logArguments(p);

        if (options.help) {
            p.printUsage(System.out);
            System.exit(0);
        }

        if (options.pidFile != null) {
            // This will return a string like 12345@hostname, so we need to do some string manipulation
            // to get the actual process identifier.
            // In Java 9, this can be replaced with: ProcessHandle.current().getPid();
            String pidName = ManagementFactory.getRuntimeMXBean().getName();
            String[] pidNameParts = pidName.split("@");
            String pid = pidNameParts[0];
            File pidFile = new File(options.pidFile);
            if (pidFile.exists()) {
                int oldPid =
                        NumberUtils.toInt(
                                new String(Files.readAllBytes(pidFile.toPath()), UTF_8), 0);
                // check if this process is running
                if (oldPid > 0) {
                    OSProcess oldProcess = new SystemInfo().getOperatingSystem().getProcess(oldPid);
                    if (oldProcess != null) {
                        logger.severe(
                                String.format(
                                        "Refusing to start because PID file '%s' already exists and the previous process %d (%s) is still running.",
                                        pidFile.getAbsolutePath(),
                                        oldPid,
                                        oldProcess.getCommandLine()));
                        System.exit(1);
                    } else {
                        logger.fine(
                                String.format(
                                        "Ignoring PID file '%s' because the previous process %d is no longer running.",
                                        pidFile.getAbsolutePath(), oldPid));
                    }
                }
            }
            pidFile.deleteOnExit();
            try {
                Files.write(pidFile.toPath(), pid.getBytes(UTF_8));
            } catch (IOException exception) {
                logger.severe("Failed writing PID file: " + options.pidFile);
                System.exit(1);
            }
        }

        // Check to see if passwordEnvVariable is set, if so pull down the
        // password from the env and set as password.
        if (options.passwordEnvVariable != null) {
            options.password = System.getenv(options.passwordEnvVariable);
        }
        // read pass from file if no other password was specified
        if (options.password == null && options.passwordFile != null) {
            options.password = new String(Files.readAllBytes(Paths.get(options.passwordFile)), UTF_8).trim();
        }


        // Only look up the hostname if we have not already specified
        // name of the slave. Also in certain cases this lookup might fail.
        // E.g.
        // Querying a external DNS server which might not be informed
        // of a newly created slave from a DHCP server.
        //
        // From https://docs.oracle.com/javase/8/docs/api/java/net/InetAddress.html#getCanonicalHostName--
        //
        // "Gets the fully qualified domain name for this IP
        // address. Best effort method, meaning we may not be able to
        // return the FQDN depending on the underlying system
        // configuration."
        if (options.name == null) {
            try {
                client.options.name = InetAddress.getLocalHost().getCanonicalHostName();
            } catch (IOException e) {
                logger.severe("Failed to lookup the canonical hostname of this slave, please check system settings.");
                logger.severe("If not possible to resolve please specify a node name using the '-name' option");
                System.exit(1);
            }
        }

        SwarmClient swarmClient = new SwarmClient(options);
        client.run(swarmClient, args); // pass the command line arguments along so that the LabelFileWatcher thread can have them
    }

    public Client(Options options) {
        this.options = options;
    }

    /**
     * Finds a Jenkins master that supports swarming, and join it.
     * <p>
     * This method never returns.
     */
    public void run(SwarmClient swarmClient, String... args) throws InterruptedException {
        logger.info("Discovering Jenkins master");

        // The Jenkins that we are trying to connect to.
        Candidate target;

        // wait until we get the ACK back
        int retry = 0;
        while (true) {
            try {
                if (options.master == null) {
                    logger.info("No Jenkins master supplied on command line, performing auto-discovery");
                    target = swarmClient.discoverFromBroadcast();
                } else {
                    target = swarmClient.discoverFromMasterUrl();
                }

                if (options.password == null && options.username == null) {
                    swarmClient.verifyThatUrlIsHudson(target);
                }

                logger.info(
                        "Attempting to connect to "
                                + target.url
                                + " "
                                + target.secret
                                + " with ID "
                                + swarmClient.getHash());

                /*
                 * Create a new swarm slave. After this method returns, the value of the name field
                 * has been set to the name returned by the server, which may or may not be the name
                 * we originally requested.
                 */
                swarmClient.createSwarmSlave(target);

                /*
                 * Set up the label file watcher thread. If the label file changes, this thread
                 * takes action to restart the client. Note that this must be done after we create
                 * the swarm slave, since only then has the server returned the name we must use
                 * when doing label operations.
                 */
                if (options.labelsFile != null) {
                    logger.info("Setting up LabelFileWatcher");
                    LabelFileWatcher l =
                            new LabelFileWatcher(target, options, swarmClient.getName(), args);
                    Thread labelFileWatcherThread = new Thread(l, "LabelFileWatcher");
                    labelFileWatcherThread.setDaemon(true);
                    labelFileWatcherThread.start();
                }

                swarmClient.connect(target);
                if (options.noRetryAfterConnected) {
                    logger.warning("Connection closed, exiting...");
                    swarmClient.exitWithStatus(0);
                }
            } catch (IOException e) {
                logger.log(Level.SEVERE, "IOException occurred", e);
                e.printStackTrace();
            } catch (RetryException e) {
                logger.log(Level.SEVERE, "RetryException occurred", e);
                if (e.getCause() != null) {
                    e.getCause().printStackTrace();
                }
            }

            int waitTime = options.retryBackOffStrategy.waitForRetry(retry++, options.retryInterval, options.maxRetryInterval);
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
        for (OptionHandler argument : parser.getArguments()) {
            logValue(sb, argument, null);
        }
        for (OptionHandler option : parser.getOptions()) {
            logValue(sb, option, defaultParser);
        }
        logger.info(sb.toString());
    }

    private static void logValue(
            StringBuilder sb, OptionHandler handler, CmdLineParser defaultParser) {
        String key = getKey(handler);
        Object value = getValue(handler);

        if (key.equals("-help")) {
            return;
        }

        if (defaultParser != null && isDefaultOption(key, value, defaultParser)) {
            return;
        }

        sb.append(key);
        sb.append(' ');
        if (key.equals("-username") || key.equals("-password")) {
            sb.append("*****");
        } else {
            sb.append(value);
        }
        sb.append(' ');
    }

    private static String getKey(OptionHandler optionHandler) {
        if (optionHandler.option instanceof NamedOptionDef) {
            NamedOptionDef namedOptionDef = (NamedOptionDef) optionHandler.option;
            return namedOptionDef.name();
        } else {
            return optionHandler.option.toString();
        }
    }

    private static Object getValue(OptionHandler optionHandler) {
        FieldSetter setter = optionHandler.setter.asFieldSetter();
        return setter == null ? null : setter.getValue();
    }

    private static boolean isDefaultOption(String key, Object value, CmdLineParser defaultParser) {
        for (OptionHandler defaultOption : defaultParser.getOptions()) {
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
