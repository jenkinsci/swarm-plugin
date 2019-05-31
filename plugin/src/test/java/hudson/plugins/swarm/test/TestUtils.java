package hudson.plugins.swarm.test;

import static org.hamcrest.Matchers.greaterThan;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import hudson.model.Computer;
import hudson.model.Node;
import java.io.File;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import jenkins.model.Jenkins;
import org.apache.commons.io.FileUtils;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.JenkinsRule;

/** Utilities for testing the Swarm Plugin */
public class TestUtils {

    /** Download the Swarm Client from the given Jenkins URL into the given temporary directory. */
    private static void download(URL jenkinsUrl, File output) throws Exception {
        URL input =
                jenkinsUrl.toURI().resolve(new URI(null, "swarm/swarm-client.jar", null)).toURL();
        FileUtils.copyURLToFile(input, output);

        assertTrue(output.isFile());
        assertThat(output.length(), greaterThan(1000L));
    }

    /** Wait for the agent with the given name to come online against the given Jenkins instance. */
    private static Computer waitOnline(String agentName, Jenkins jenkins)
            throws InterruptedException {
        Computer computer = jenkins.getComputer(agentName);
        while (computer == null) {
            Thread.sleep(100);
            computer = jenkins.getComputer(agentName);
        }

        while (!computer.isOnline()) {
            Thread.sleep(100);
        }

        return computer;
    }

    /**
     * Create a new Swarm Client agent on the local host and wait for it to come online before
     * returning.
     */
    public static Node createSwarmClient(
            JenkinsRule j,
            ProcessDestroyer processDestroyer,
            TemporaryFolder temporaryFolder,
            String... args)
            throws Exception {
        String agentName = "agent" + j.jenkins.getNodes().size();
        return createSwarmClient(agentName, j, processDestroyer, temporaryFolder, args);
    }

    /**
     * Create a new Swarm Client agent on the local host and wait for it to come online before
     * returning.
     */
    public static Node createSwarmClient(
            String agentName,
            JenkinsRule j,
            ProcessDestroyer processDestroyer,
            TemporaryFolder temporaryFolder,
            String... args)
            throws Exception {
        File swarmClientJar =
                File.createTempFile("swarm-client", ".jar", temporaryFolder.getRoot());
        download(j.getURL(), swarmClientJar);

        List<String> command = new ArrayList<>();
        command.add(
                System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
        command.add("-Djava.awt.headless=true");
        command.add("-jar");
        command.add(swarmClientJar.toString());
        command.add("-name");
        command.add(agentName);
        command.add("-master");
        command.add(j.getURL().toString());
        // "-disableClientsUniqueId" is needed so that we can call getComputer() in the waitOnline()
        // method.
        command.add("-disableClientsUniqueId");
        Collections.addAll(command, args);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().put("ON_SWARM_CLIENT", "true");
        File stdout = File.createTempFile("stdout", ".log", temporaryFolder.getRoot());
        pb.redirectOutput(stdout);
        File stderr = File.createTempFile("stderr", ".log", temporaryFolder.getRoot());
        pb.redirectError(stderr);
        Process process = pb.start();
        processDestroyer.record(process);

        Computer computer = waitOnline(agentName, j.jenkins);
        assertNotNull(computer);
        assertTrue(computer.isOnline());
        Node node = computer.getNode();
        assertNotNull(node);
        return node;
    }
}
