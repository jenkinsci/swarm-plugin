package hudson.plugins.swarm.test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import hudson.model.Computer;
import hudson.model.Node;
import hudson.security.ProjectMatrixAuthorizationStrategy;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.input.Tailer;
import org.apache.commons.io.input.TailerListenerAdapter;
import org.jenkinsci.plugins.matrixauth.AuthorizationMatrixNodeProperty;
import org.jenkinsci.plugins.workflow.support.concurrent.Timeout;
import org.junit.rules.ExternalResource;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.RestartableJenkinsRule;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A rule for starting the Swarm client. The rule does nothing before running the test method. The
 * caller is expected to start the Swarm client using either {@link #createSwarmClient} or {@link
 * #createSwarmClientWithName}. Once the Swarm client has been started, the caller may explicitly
 * terminate it with {@link #tearDown()}. Only one instance of the Swarm client may be running at a
 * time. If an instance is running at the end of the test method, it will be automatically torn
 * down. Automatic tear down will also take place if the test times out.
 *
 * <p>Should work in combination with {@link JenkinsRule} or {@link RestartableJenkinsRule}.
 */
public class SwarmClientRule extends ExternalResource {

    private static final Logger logger = Logger.getLogger(SwarmClientRule.class.getName());

    /** A {@link Supplier} for compatibility with {@link RestartableJenkinsRule}. */
    final Supplier<JenkinsRule> j;

    /**
     * Used for storing the Swarm Client JAR file as well as for the client's Remoting working
     * directory.
     */
    private final TemporaryFolder temporaryFolder;

    /** Whether or not the client is currently active. */
    private boolean isActive = false;

    /** The username to use when connecting to the Jenkins master. */
    String swarmUsername;

    /** The password or API token to use when connecting to the Jenkins master. */
    String swarmPassword;

    /**
     * The {@link Computer} object corresponding to the agent within Jenkins, if the client is
     * active.
     */
    private Computer computer;

    /** A {@link Tailer} for watching the client's standard out stream, if the client is active. */
    private Tailer stdoutTailer;

    /**
     * A {@link Thread} for running the {@link Tailer} for the client's standard out stream, if the
     * client is active.
     */
    private Thread stdoutThread;

    /**
     * A {@link Tailer} for watching the client's standard error stream, if the client is active.
     */
    private Tailer stderrTailer;

    /**
     * A {@link Thread} for running the {@link Tailer} for the client's standard error stream, if
     * the client is active.
     */
    private Thread stderrThread;

    /** The {@link Process} corresponding to the client, if the client is active. */
    private Process process;

    public SwarmClientRule(Supplier<JenkinsRule> j, TemporaryFolder temporaryFolder) {
        this.j = j;
        this.temporaryFolder = temporaryFolder;
    }

    public GlobalSecurityConfigurationBuilder globalSecurityConfigurationBuilder() {
        return new GlobalSecurityConfigurationBuilder(this);
    }

    /**
     * Create a new Swarm agent on the local host and wait for it to come online before returning.
     * The agent will be named automatically.
     */
    public synchronized Node createSwarmClient(String... args)
            throws InterruptedException, IOException {
        if (isActive) {
            throw new IllegalStateException(
                    "You must first tear down the existing Swarm client with \"tearDown()\" before"
                            + " creating a new one.");
        }

        String agentName = "agent" + j.get().jenkins.getNodes().size();
        return createSwarmClientWithName(agentName, args);
    }

    /**
     * Create a new Swarm agent on the local host and wait for it to come online before returning.
     *
     * @param agentName The proposed name of the agent.
     * @param args The arguments to pass to the client.
     */
    public synchronized Node createSwarmClientWithName(String agentName, String... args)
            throws InterruptedException, IOException {
        if (isActive) {
            throw new IllegalStateException(
                    "You must first tear down the existing Swarm client with \"tearDown()\" before"
                            + " creating a new one.");
        }

        // Download the Swarm client JAR from the Jenkins master.
        Path swarmClientJar =
                Files.createTempFile(temporaryFolder.getRoot().toPath(), "swarm-client", ".jar");
        download(swarmClientJar);

        // Create the password file.
        Path passwordFile = null;
        if (swarmPassword != null) {
            passwordFile =
                    Files.createTempFile(temporaryFolder.getRoot().toPath(), "password", null);
            Files.write(passwordFile, swarmPassword.getBytes(StandardCharsets.UTF_8));
        }

        // Form the list of command-line arguments.
        List<String> command =
                getCommand(
                        swarmClientJar,
                        j.get().getURL(),
                        agentName,
                        swarmUsername,
                        passwordFile != null ? passwordFile.toString() : null,
                        args);

        logger.log(Level.INFO, "Starting client process.");
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(temporaryFolder.newFolder());
            pb.environment().put("ON_SWARM_CLIENT", "true");

            // Redirect standard out to a file and start a thread to tail its contents.
            Path stdout =
                    Files.createTempFile(temporaryFolder.getRoot().toPath(), "stdout", ".log");
            pb.redirectOutput(stdout.toFile());
            stdoutTailer =
                    new Tailer(
                            stdout.toFile(), new SwarmClientTailerListener("Standard out"), 200L);
            stdoutThread = new Thread(stdoutTailer);
            stdoutThread.setDaemon(true);
            stdoutThread.start();

            // Redirect standard error to a file and start a thread to tail its contents.
            Path stderr =
                    Files.createTempFile(temporaryFolder.getRoot().toPath(), "stderr", ".log");
            pb.redirectError(stderr.toFile());
            stderrTailer =
                    new Tailer(
                            stderr.toFile(), new SwarmClientTailerListener("Standard error"), 200L);
            stderrThread = new Thread(stderrTailer);
            stderrThread.setDaemon(true);
            stderrThread.start();

            // Start the process.
            process = pb.start();
        } finally {
            isActive = true;
        }

        logger.log(Level.INFO, "Waiting for \"{0}\" to come online.", agentName);
        computer = waitOnline(agentName);
        assertNotNull(computer);
        assertTrue(computer.isOnline());
        Node node = computer.getNode();
        if (j.get().jenkins.getAuthorizationStrategy()
                instanceof ProjectMatrixAuthorizationStrategy) {
            assertNotNull(node.getNodeProperty(AuthorizationMatrixNodeProperty.class));
        }
        assertNotNull(node);

        logger.log(Level.INFO, "\"{0}\" is now online.", node.getNodeName());
        return node;
    }

    /**
     * A helper method to get the command-line arguments to start the client.
     *
     * @param swarmClientJar The path to the client JAR.
     * @param url The URL of the Jenkins master, if one is being provided to the client.
     * @param agentName The proposed name of the agent.
     * @param args Any other desired arguments.
     */
    public static List<String> getCommand(
            Path swarmClientJar,
            URL url,
            String agentName,
            String username,
            String passwordFile,
            String... args) {
        List<String> command = new ArrayList<>();
        command.add(
                System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
        command.add("-Djava.awt.headless=true");
        command.add("-Dhudson.plugins.swarm.LabelFileWatcher.labelFileWatcherIntervalMillis=100");
        command.add("-jar");
        command.add(swarmClientJar.toString());
        if (url != null) {
            command.add("-master");
            command.add(url.toString());
        }
        command.add("-name");
        command.add(agentName);
        if (username != null) {
            command.add("-username");
            command.add(username);
        }
        if (passwordFile != null) {
            command.add("-passwordFile");
            command.add(passwordFile);
        }
        Collections.addAll(command, args);
        return command;
    }

    /** Download the Swarm Client from the given Jenkins URL into the given temporary directory. */
    public void download(Path output) throws IOException {
        URL input;
        try {
            input =
                    j.get()
                            .getURL()
                            .toURI()
                            .resolve(new URI(null, "swarm/swarm-client.jar", null))
                            .toURL();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
        logger.log(
                Level.INFO,
                "Downloading Swarm client from \"{0}\" to \"{1}\".",
                new Object[] {input, output});
        FileUtils.copyURLToFile(input, output.toFile());

        assertTrue(Files.isRegularFile(output));
        assertThat(Files.size(output), greaterThan(1000L));
    }

    /** Wait for the agent with the given name to come online against the given Jenkins instance. */
    private Computer waitOnline(String agentName) throws InterruptedException {
        try (Timeout t = Timeout.limit(60, TimeUnit.SECONDS)) {
            Computer result = getComputer(agentName);
            while (result == null) {
                Thread.sleep(500L);
                result = getComputer(agentName);
            }

            while (!result.isOnline()) {
                Thread.sleep(500L);
            }

            return result;
        }
    }

    /**
     * Gets the computer corresponding to the proposed agent name. At this point we do not yet know
     * the final agent name, so we have to keep iterating until we find the agent that starts with
     * the proposed name.
     */
    private Computer getComputer(String agentName) {
        List<Computer> candidates = new ArrayList<>();
        for (Computer candidate : j.get().jenkins.getComputers()) {
            if (candidate.getName().equals(agentName)) {
                return candidate;
            } else if (candidate.getName().startsWith(agentName + '-')) {
                candidates.add(candidate);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        assertEquals(candidates.size(), 1);
        return candidates.get(0);
    }

    public synchronized void tearDown() {
        if (!isActive) {
            throw new IllegalStateException(
                    "Must first create a Swarm client before attempting to tear it down.");
        }
        boolean interrupted = false;
        try {
            // Stop the process.
            if (process != null) {
                try {
                    process.destroy();
                    assertTrue(process.waitFor(30, TimeUnit.SECONDS));
                    logger.log(
                            Level.INFO,
                            "Swarm client exited with exit value {0}.",
                            process.exitValue());
                } catch (IllegalThreadStateException e) {
                    e.printStackTrace();
                    // ignore
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    interrupted = true;
                } finally {
                    process = null;
                }
            }

            // Stop tailing standard out.
            if (stdoutTailer != null) {
                try {
                    stdoutTailer.stop();
                } finally {
                    stdoutTailer = null;
                }
            }
            if (stdoutThread != null) {
                try {
                    stdoutThread.join(30000L);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    interrupted = true;
                } finally {
                    stdoutThread = null;
                }
            }

            // Stop tailing standard error.
            if (stderrTailer != null) {
                try {
                    stderrTailer.stop();
                } finally {
                    stderrTailer = null;
                }
            }
            if (stderrThread != null) {
                try {
                    stderrThread.join(30000L);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    interrupted = true;
                } finally {
                    stderrThread = null;
                }
            }

            // Wait for the agent to be disconnected from the master
            if (computer != null) {
                try (Timeout t = Timeout.limit(60, TimeUnit.SECONDS)) {
                    while (computer.isOnline()) {
                        Thread.sleep(500L);
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    interrupted = true;
                } finally {
                    computer = null;
                }
            }
        } finally {
            isActive = false;
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Override to tear down your specific external resource. */
    @Override
    protected synchronized void after() {
        if (isActive) {
            tearDown();
        }
    }

    static class SwarmClientTailerListener extends TailerListenerAdapter {
        final String prefix;

        SwarmClientTailerListener(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public void handle(String line) {
            logger.log(Level.INFO, prefix + ": {0}", line);
        }
    }
}
