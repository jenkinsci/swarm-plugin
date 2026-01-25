package hudson.plugins.swarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import hudson.Functions;
import hudson.model.Computer;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Node;
import hudson.plugins.swarm.test.SwarmClientRule;
import hudson.slaves.SlaveComputer;
import hudson.tasks.BatchFile;
import hudson.tasks.CommandInterpreter;
import hudson.tasks.Shell;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.commons.lang.RandomStringUtils;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;

public class SwarmClientIntegrationTest {

    @ClassRule
    public static BuildWatcher buildWatcher = new BuildWatcher();

    @Rule(order = 10)
    public JenkinsRule j = new JenkinsRule();

    @Rule(order = 20)
    public TemporaryFolder temporaryFolder =
            TemporaryFolder.builder().assureDeletion().build();

    @Rule(order = 21)
    public TemporaryFolder temporaryRemotingFolder =
            TemporaryFolder.builder().assureDeletion().build();

    @Rule(order = 30)
    public SwarmClientRule swarmClientRule = new SwarmClientRule(() -> j, temporaryFolder, Level.INFO, Level.FINE);

    private static final Logger logger = Logger.getLogger(SwarmClientIntegrationTest.class.getName());

    @Before
    public void configureGlobalSecurity() throws IOException {
        swarmClientRule.globalSecurityConfigurationBuilder().build();
    }

    /** Executes a shell script build on a Swarm agent. */
    @Test
    public void buildShellScript() throws Exception {
        Node node = swarmClientRule.createSwarmClient();

        FreeStyleProject project = j.createFreeStyleProject();
        project.setConcurrentBuild(false);
        project.setAssignedNode(node);
        project.getBuildersList().add(echoCommand("ON_SWARM_CLIENT"));

        FreeStyleBuild build = j.buildAndAssertSuccess(project);
        j.assertLogContains("ON_SWARM_CLIENT=true", build);
    }

    @Test
    public void environmentVariables() throws Exception {
        Node node = swarmClientRule.createSwarmClient("-e", "SWARM_VAR_1=foo", "-e", "SWARM_VAR_2=bar");

        FreeStyleProject project = j.createFreeStyleProject();
        project.setConcurrentBuild(false);
        project.setAssignedNode(node);
        project.getBuildersList().add(echoCommand("SWARM_VAR_1"));
        project.getBuildersList().add(echoCommand("SWARM_VAR_2"));

        FreeStyleBuild build = j.buildAndAssertSuccess(project);
        j.assertLogContains("SWARM_VAR_1=foo", build);
        j.assertLogContains("SWARM_VAR_2=bar", build);
    }

    private static CommandInterpreter echoCommand(String key) {
        return Functions.isWindows()
                ? new BatchFile("echo " + key + "=%" + key + "%")
                : new Shell("echo " + key + "=$" + key);
    }

    /** Ensures that a node can be created with a colon in the description. */
    @Test
    @Issue("JENKINS-39443")
    public void sanitizeDescription() throws Exception {
        Node node = swarmClientRule.createSwarmClient("-description", "swarm_ip:127.0.0.1");
        assertTrue(node.getNodeDescription().endsWith("swarm_ip:127.0.0.1"));
    }

    @Test
    public void addLabelsShort() throws Exception {
        addLabels("foo", "bar", "baz");
    }

    @Test
    public void addLabelsLong() throws Exception {
        addLabels(
                RandomStringUtils.randomAlphanumeric(350),
                RandomStringUtils.randomAlphanumeric(350),
                RandomStringUtils.randomAlphanumeric(350));
    }

    private void addLabels(String... labels) throws Exception {
        Node node = swarmClientRule.createSwarmClient("-labels", encode(Set.of(labels)));
        Set<String> expected = new HashSet<>();
        Collections.addAll(expected, labels);
        expected.add("swarm");
        assertEquals(expected, decode(node.getLabelString()));
    }

    @Test
    public void addRemoveLabelsViaFileWithUniqueIdShort() throws Exception {
        Set<String> labelsToRemove = new HashSet<>();
        labelsToRemove.add("removeFoo");
        labelsToRemove.add("removeBar");
        labelsToRemove.add("removeBaz");

        Set<String> labelsToAdd = new HashSet<>();
        labelsToAdd.add("foo");
        labelsToAdd.add("bar");
        labelsToAdd.add("baz");

        addRemoveLabelsViaFile(labelsToRemove, labelsToAdd, true);
    }

    @Test
    public void addRemoveLabelsViaFileWithUniqueIdLong() throws Exception {
        Set<String> labelsToRemove = new HashSet<>();
        labelsToRemove.add(RandomStringUtils.randomAlphanumeric(350));
        labelsToRemove.add(RandomStringUtils.randomAlphanumeric(350));
        labelsToRemove.add(RandomStringUtils.randomAlphanumeric(350));

        Set<String> labelsToAdd = new HashSet<>();
        labelsToAdd.add(RandomStringUtils.randomAlphanumeric(350));
        labelsToAdd.add(RandomStringUtils.randomAlphanumeric(350));
        labelsToAdd.add(RandomStringUtils.randomAlphanumeric(350));

        addRemoveLabelsViaFile(labelsToRemove, labelsToAdd, true);
    }

    @Test
    public void addRemoveLabelsViaFileWithoutUniqueIdShort() throws Exception {
        Set<String> labelsToRemove = new HashSet<>();
        labelsToRemove.add("removeFoo");
        labelsToRemove.add("removeBar");
        labelsToRemove.add("removeBaz");

        Set<String> labelsToAdd = new HashSet<>();
        labelsToAdd.add("foo");
        labelsToAdd.add("bar");
        labelsToAdd.add("baz");

        addRemoveLabelsViaFile(labelsToRemove, labelsToAdd, false);
    }

    @Test
    public void addRemoveLabelsViaFileWithoutUniqueIdLong() throws Exception {
        Set<String> labelsToRemove = new HashSet<>();
        labelsToRemove.add(RandomStringUtils.randomAlphanumeric(350));
        labelsToRemove.add(RandomStringUtils.randomAlphanumeric(350));
        labelsToRemove.add(RandomStringUtils.randomAlphanumeric(350));

        Set<String> labelsToAdd = new HashSet<>();
        labelsToAdd.add(RandomStringUtils.randomAlphanumeric(350));
        labelsToAdd.add(RandomStringUtils.randomAlphanumeric(350));
        labelsToAdd.add(RandomStringUtils.randomAlphanumeric(350));

        addRemoveLabelsViaFile(labelsToRemove, labelsToAdd, false);
    }

    @Test
    public void pidFilePreventsStart() throws Exception {
        Assume.assumeFalse("TODO Windows container agents cannot run this test", Functions.isWindows());
        Path pidFile = getPidFile();
        // Start the first client with a PID file and ensure it's up.
        swarmClientRule.createSwarmClient("-pidFile", pidFile.toAbsolutePath().toString());

        long firstClientPid = readPidFromFile(pidFile);

        // Try to start a second and ensure it fails to run.
        startFailingSwarmClient(
                j.getURL(), "agent_fail", "-pidFile", pidFile.toAbsolutePath().toString());

        // Now ensure that the original process is still OK and so is its PID file.
        Optional<ProcessHandle> firstClient = ProcessHandle.of(firstClientPid);
        assertTrue("Original client should still be running", firstClient.isPresent());
        assertTrue("Original client should still be running", firstClient.get().isAlive());
        assertEquals("PID in PID file should not change", firstClientPid, readPidFromFile(pidFile));
    }

    @Test
    public void pidFileForOtherProcessIsIgnored() throws Exception {
        Assume.assumeFalse("TODO Windows container agents cannot run this test", Functions.isWindows());
        Path pidFile = getPidFile();
        Process sleep = new ProcessBuilder().command("sleep", "60").start();
        try {
            Files.writeString(pidFile, Long.toString(sleep.pid()), StandardCharsets.US_ASCII);

            // PID file should be ignored since the process command doesn't match our own.
            swarmClientRule.createSwarmClient(
                    "-pidFile", pidFile.toAbsolutePath().toString());

            long newPid = readPidFromFile(pidFile);

            assertTrue(
                    "PID in PID file must match our new PID",
                    ProcessHandle.current().descendants().anyMatch(proc -> proc.pid() == newPid));
        } finally {
            sleep.destroyForcibly();
            sleep.waitFor();
        }
    }

    @Test
    public void pidFileForStaleProcessIsIgnored() throws Exception {
        Assume.assumeFalse("TODO Windows container agents cannot run this test", Functions.isWindows());
        Path pidFile = getPidFile();
        Files.writeString(pidFile, "66000", StandardCharsets.US_ASCII);

        // PID file should be ignored since the process isn't running.
        swarmClientRule.createSwarmClient("-pidFile", pidFile.toAbsolutePath().toString());

        long newPid = readPidFromFile(pidFile);

        assertTrue(
                "PID in PID file must match our new PID",
                ProcessHandle.current().descendants().anyMatch(proc -> proc.pid() == newPid));
    }

    /** Ensures that a node can be created with the WebSocket protocol. */
    @Test
    @Issue("JENKINS-61969")
    public void webSocket() throws Exception {
        Node node = swarmClientRule.createSwarmClient("-webSocket");

        FreeStyleProject project = j.createFreeStyleProject();
        project.setConcurrentBuild(false);
        project.setAssignedNode(node);
        project.getBuildersList().add(echoCommand("ON_SWARM_CLIENT"));

        FreeStyleBuild build = j.buildAndAssertSuccess(project);
        j.assertLogContains("ON_SWARM_CLIENT=true", build);
    }

    @Test
    public void webSocketHeaders() throws IOException, InterruptedException {
        swarmClientRule.createSwarmClient("-webSocket", "-webSocketHeader", "WS_HEADER=HEADER_VALUE");
    }

    @Test
    public void webSocketHeadersFailsIfNoWebSocketArgument() throws IOException, InterruptedException {
        startFailingSwarmClient(j.getURL(), "should_fail", "-webSocketHeader", "WS_HEADER=HEADER_VALUE");
    }

    @Test
    public void pidFileDeletedOnExit() throws Exception {
        Assume.assumeFalse("TODO The PID file doesn't seem to be deleted on exit on Windows", Functions.isWindows());

        Path pidFile = getPidFile();
        swarmClientRule.createSwarmClient("-pidFile", pidFile.toAbsolutePath().toString());

        while (!Files.isRegularFile(pidFile)) {
            // Ensure the process writes the PID.
            Thread.sleep(100L);
        }

        assertTrue("PID file created", Files.isRegularFile(pidFile));
        swarmClientRule.tearDown();
        assertFalse("PID file removed", Files.isRegularFile(pidFile));
    }

    /**
     * @return a dedicated unique PID file object for an as yet non-existent file.
     */
    private Path getPidFile() throws IOException {
        Path pidFile = Files.createTempFile(temporaryFolder.getRoot().toPath(), "swarm-client", ".pid");
        // We want the process to create it, here we just want a unique name.
        Files.delete(pidFile);
        return pidFile;
    }

    private static long readPidFromFile(Path pidFile) throws IOException {
        return Long.parseLong(Files.readString(pidFile, StandardCharsets.US_ASCII));
    }

    private void addRemoveLabelsViaFile(Set<String> labelsToRemove, Set<String> labelsToAdd, boolean withUniqueId)
            throws Exception {
        Path labelsFile = Files.createTempFile(temporaryFolder.getRoot().toPath(), "labelsFile", ".txt");

        Node node;
        if (withUniqueId) {
            node = swarmClientRule.createSwarmClient(
                    "-labelsFile", labelsFile.toAbsolutePath().toString(), "-labels", encode(labelsToRemove));
        } else {
            node = swarmClientRule.createSwarmClient(
                    "-disableClientsUniqueId",
                    "-labelsFile",
                    labelsFile.toAbsolutePath().toString(),
                    "-labels",
                    encode(labelsToRemove));
        }

        String origLabels = node.getLabelString();

        Files.writeString(labelsFile, encode(labelsToAdd), StandardCharsets.UTF_8);

        // TODO: This is a bit racy, since updates are not atomic.
        while (node.getLabelString().equals(origLabels)
                || decode(node.getLabelString()).equals(decode("swarm"))) {
            Thread.sleep(100L);
        }

        Set<String> expected = new HashSet<>(labelsToAdd);
        expected.add("swarm");
        assertEquals(expected, decode(node.getLabelString()));
    }

    private static String encode(Set<String> labels) {
        return String.join(" ", labels);
    }

    private static Set<String> decode(String labels) {
        return Set.of(labels.split("\\s+"));
    }

    @Test
    public void defaultDescription() throws Exception {
        Node node = swarmClientRule.createSwarmClient();
        assertTrue(
                node.getNodeDescription(),
                Pattern.matches("Swarm agent from ([a-zA-Z_0-9-.]+)", node.getNodeDescription()));
    }

    @Test
    public void customDescription() throws Exception {
        Node node = swarmClientRule.createSwarmClient("-description", "foobar");
        assertTrue(
                node.getNodeDescription(),
                Pattern.matches("Swarm agent from ([a-zA-Z_0-9-.]+): foobar", node.getNodeDescription()));
    }

    @Test
    public void missingUrlOption() throws Exception {
        startFailingSwarmClient(null, "test");
    }

    @Test
    public void workDirEnabledByDefaultWithFsRootAsDefaultPath() throws Exception {
        File fsRootPath = temporaryRemotingFolder.newFolder("fsrootdir");
        swarmClientRule.createSwarmClient("-fsroot", fsRootPath.getAbsolutePath());

        assertDirectories(
                fsRootPath,
                new File(fsRootPath, "remoting"),
                new File(fsRootPath, "remoting/logs"),
                new File(fsRootPath, "remoting/jarCache"));
    }

    @Test
    public void workDirWithCustomPath() throws Exception {
        File workDirPath = new File(temporaryRemotingFolder.getRoot(), "customworkdir");
        swarmClientRule.createSwarmClient("-workDir", workDirPath.getAbsolutePath());

        assertDirectories(
                workDirPath,
                new File(workDirPath, "remoting"),
                new File(workDirPath, "remoting/logs"),
                new File(workDirPath, "remoting/jarCache"));
    }

    @Test
    public void disableWorkDirRunsInLegacyMode() throws Exception {
        File fsRootPath = temporaryRemotingFolder.newFolder("fsrootdir");
        swarmClientRule.createSwarmClient("-fsroot", fsRootPath.getAbsolutePath(), "-disableWorkDir");

        assertDirectories(fsRootPath);
        assertNoDirectories(
                new File(fsRootPath, "remoting"),
                new File(fsRootPath, "remoting/logs"),
                new File(fsRootPath, "remoting/jarCache"));
    }

    @Test
    public void failIfWorkDirIsMissingDoesNothingIfDirectoryExists() throws Exception {
        File fsRootPath = temporaryRemotingFolder.newFolder("fsrootdir");
        File workDirPath = temporaryFolder.newFolder("remoting");
        swarmClientRule.createSwarmClient(
                "-fsroot",
                fsRootPath.getAbsolutePath(),
                "-workDir",
                workDirPath.getParent(),
                "-failIfWorkDirIsMissing");

        assertDirectories(fsRootPath, workDirPath, new File(workDirPath, "logs"), new File(workDirPath, "jarCache"));
    }

    @Test
    public void failIfWorkDirIsMissingFailsOnMissingWorkDir() throws Exception {
        File fsRootPath = temporaryRemotingFolder.newFolder("fsrootdir");
        File workDirPath = new File(temporaryRemotingFolder.getRoot(), "customworkdir");
        startFailingSwarmClient(
                j.getURL(),
                "should_fail",
                "-fsroot",
                fsRootPath.getAbsolutePath(),
                "-workDir",
                workDirPath.getAbsolutePath(),
                "-retry",
                "0",
                "-retryInterval",
                "0",
                "-maxRetryInterval",
                "0",
                "-failIfWorkDirIsMissing");

        assertDirectories(fsRootPath);
        assertNoDirectories(
                new File(fsRootPath, "remoting"),
                new File(fsRootPath, "remoting/logs"),
                new File(fsRootPath, "remoting/jarCache"));
    }

    @Test
    public void internalDirIsInWorkDirByDefault() throws Exception {
        File fsRootPath = temporaryRemotingFolder.newFolder("fsrootdir");
        swarmClientRule.createSwarmClient("-fsroot", fsRootPath.getAbsolutePath());

        assertDirectories(
                fsRootPath,
                new File(fsRootPath, "remoting"),
                new File(fsRootPath, "remoting/logs"),
                new File(fsRootPath, "remoting/jarCache"));
    }

    @Test
    public void internalDirWithCustomPath() throws Exception {
        File fsRootPath = temporaryRemotingFolder.newFolder("fsrootdir");
        swarmClientRule.createSwarmClient("-fsroot", fsRootPath.getAbsolutePath(), "-internalDir", "custominternaldir");

        assertDirectories(
                fsRootPath,
                new File(fsRootPath, "custominternaldir"),
                new File(fsRootPath, "custominternaldir/logs"),
                new File(fsRootPath, "custominternaldir/jarCache"));
    }

    @Test
    public void jarCacheWithCustomPath() throws Exception {
        File fsRootPath = temporaryRemotingFolder.newFolder("fsrootdir");
        File jarCachePath = new File(temporaryRemotingFolder.getRoot(), "customjarcache");
        swarmClientRule.createSwarmClient(
                "-fsroot", fsRootPath.getAbsolutePath(), "-jar-cache", jarCachePath.getPath());

        assertDirectories(
                fsRootPath, new File(fsRootPath, "remoting"), new File(fsRootPath, "remoting/logs"), jarCachePath);
    }

    @Test
    public void metricsPrometheus() throws Exception {
        swarmClientRule.createSwarmClient("-prometheusPort", "9999");

        // Fetch the metrics page from the client
        StringBuilder content = new StringBuilder();
        try (InputStream is = new URL("http://localhost:9999/prometheus").openStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            String inputLine;
            while ((inputLine = reader.readLine()) != null) {
                content.append(inputLine);
            }
        }

        // Assert that a non-zero length string was read
        assertTrue(content.length() > 0);
        // Assert that we got at least one known Prometheus metric
        assertTrue(content.toString().contains("process_cpu_usage"));
    }

    @Test
    public void configFromYaml() throws Exception {
        final Path pwFile = temporaryFolder.newFile("pw").toPath();
        Files.writeString(pwFile, "honeycomb", StandardCharsets.UTF_8);
        final Path config = temporaryFolder.newFile("swarm.yml").toPath();
        Files.writeString(
                config,
                "name: node-yml\n"
                        + "disableClientsUniqueId: true\n"
                        + "description: yaml config node\n"
                        + "url: "
                        + j.getURL()
                        + "\n"
                        + "username: swarm\n"
                        + "passwordFile: "
                        + pwFile.toAbsolutePath()
                        + "\n"
                        + "executors: 5\n"
                        + "retry: 0\n",
                StandardCharsets.UTF_8);

        Node node = swarmClientRule.createSwarmClientWithoutDefaultArgs(
                "node-yml", "-config", config.toAbsolutePath().toString());
        assertEquals("node-yml", node.getNodeName());
        assertEquals(5, node.getNumExecutors());
        assertTrue(node.getNodeDescription().endsWith("yaml config node"));
    }

    @Test
    public void configFromYamlFailsIfConfigFileNotFound() throws Exception {
        startFailingSwarmClient(null, null, "-config", "_not_existing_file_");
    }

    @Test
    public void configFromYamlFailsIfNoUrl() throws Exception {
        final Path config = temporaryFolder.newFile("swarm.yml").toPath();
        Files.writeString(config, "name: node-without-url\n", StandardCharsets.UTF_8);
        startFailingSwarmClient(null, null, "-config", config.toAbsolutePath().toString());
    }

    @Test
    public void configFromYamlFailsIfUsedWithOtherOption() throws Exception {
        final Path pwFile = temporaryFolder.newFile("pw").toPath();
        Files.writeString(pwFile, "honeycomb", StandardCharsets.UTF_8);
        final Path config = temporaryFolder.newFile("swarm.yml").toPath();
        Files.writeString(
                config,
                "name: failing-node\n"
                        + "url: "
                        + j.getURL()
                        + "\n"
                        + "username: swarm\n"
                        + "passwordFile: "
                        + pwFile.toAbsolutePath()
                        + "\n",
                StandardCharsets.UTF_8);
        startFailingSwarmClient(
                j.getURL(),
                "failing-node",
                "-webSocket",
                "-config",
                config.toAbsolutePath().toString());
    }

    @After
    public void tearDown() throws IOException {
        Files.deleteIfExists(getPidFile());
    }

    private static void assertDirectories(File... paths) {
        Stream.of(paths).forEach(path -> assertTrue(path.getPath() + " exists", path.isDirectory()));
    }

    private static void assertNoDirectories(File... paths) {
        Stream.of(paths).forEach(path -> assertFalse(path.getPath() + " not exists", path.isDirectory()));
    }

    /**
     * This is a subset of {@link SwarmClientRule#createSwarmClientWithName(String, String...)}. It
     * does not wait for the agent to be added on the Jenkins controller, nor does it check for
     * whether a Swarm client is already running. Clients started with this method are expected to
     * fail to start.
     */
    private void startFailingSwarmClient(URL url, String agentName, String... args)
            throws IOException, InterruptedException {
        // Download the Swarm client JAR from the Jenkins controller.
        Path swarmClientJar = Files.createTempFile(temporaryFolder.getRoot().toPath(), "swarm-client", ".jar");
        swarmClientRule.download(swarmClientJar);

        // Form the list of command-line arguments.
        List<String> command = SwarmClientRule.getCommand(swarmClientJar, url, agentName, null, null, args);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(temporaryFolder.newFolder());
        pb.environment().put("ON_SWARM_CLIENT", "true");

        // Redirect standard out to a file.
        Path stdout = Files.createTempFile(temporaryFolder.getRoot().toPath(), "stdout", ".log");
        pb.redirectOutput(stdout.toFile());

        // Redirect standard out to a file.
        Path stderr = Files.createTempFile(temporaryFolder.getRoot().toPath(), "stderr", ".log");
        pb.redirectError(stderr.toFile());

        // Start the process.
        Process process = pb.start();

        // Ensure the process failed to start.
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        assertFalse(process.isAlive());
        assertEquals(1, process.exitValue());
    }

    @Test
    public void keepDisconnectedClients() throws Exception {
        SwarmSlave swarmNode = (SwarmSlave) swarmClientRule.createSwarmClientWithName(
                "keepagent", "-keepDisconnectedClients", "-deleteExistingClients", "-disableClientsUniqueId");

        assertNotNull(swarmNode);
        assertNotNull(swarmNode.getNodeProperty(KeepSwarmClientNodeProperty.class));

        swarmClientRule.tearDown();

        // Check that the agent was not removed
        Node node = j.getInstance().getNode("keepagent");
        assertNotNull(node);

        // Properly cleanup everything
        swarmClientRule.tearDownAll();

        // Verify the cleanup worked
        assertEquals(j.getInstance().getNodes().size(), 0);
    }

    @Test
    public void removeDisconnectedClients() throws Exception {
        SwarmSlave swarmNode = (SwarmSlave) swarmClientRule.createSwarmClientWithName(
                "deleteagent", "-deleteExistingClients", "-disableClientsUniqueId");

        assertNotNull(swarmNode);
        assertNull(swarmNode.getNodeProperty(KeepSwarmClientNodeProperty.class));

        swarmClientRule.tearDown();

        // Check that the agent was successfully removed
        Node node = j.getInstance().getNode("deleteagent");
        assertNull(node);

        // Verify the cleanup worked
        assertEquals(j.getInstance().getNodes().size(), 0);
    }

    @Test
    public void keepAliveSendsProbes() throws Exception {
        String agentName = "keep-alive-agent";
        Node agentNode = swarmClientRule.createSwarmClientWithName(
                agentName, "-deleteExistingClients", "-disableClientsUniqueId", "-keepAliveInterval", "2");
        assertNotNull("Agent should be created before the test case", agentNode);

        // Note: swarmClientRule.waitOnline() is included as part of creation
        Node node = j.getInstance().getNode(agentName);
        assertNotNull("Agent should be seen by server as connected before the test case", node);

        assertEquals(
                "Agent seen by server as connected and the one we created should be the same one", agentNode, node);

        logger.log(Level.INFO, "TEST-CASE keepAliveProbes(): Sleep for 10 seconds, see if there are client probes");
        Thread.sleep(10000);
        logger.log(Level.INFO, "TEST-CASE keepAliveProbes(): The sleep is over, one way or another...");
        assertNotNull("Agent should be still connected", j.getInstance().getNode(agentName));

        assertTrue(
                "Agent logs should contain start of the KeepAliveThread",
                swarmClientRule.logContains("Setting up KeepAliveThread for 2 second probes"));
        // Requires FINE logging verbosity of the client JVM, or louder:
        assertTrue(
                "Agent logs should contain a few probes",
                swarmClientRule.logContains("Checking if agent is still registered"));
        assertTrue(
                "Agent logs should contain a few probes",
                swarmClientRule.logContains("OK: agent is still registered on the controller"));

        j.getInstance().removeNode(node);
        Thread.sleep(2000);
        assertTrue(
                "Agent logs should contain end of the KeepAliveThread",
                swarmClientRule.logContains("Stopped KeepAliveThread"));
        // Reconnects after 10 sec by default, should be quiet now
        assertTrue(
                "Agent logs should contain a started retry attempt",
                swarmClientRule.logContains("Retrying in 10 seconds"));

        logger.log(Level.INFO, "TEST-CASE keepAliveProbes(): Remove agent to finish the test case");
        swarmClientRule.tearDown();
        // This tends to report just "h.p.swarm.test.SwarmClientRule#tearDown: Swarm client exited with exit value 1."
        // and nothing from agent itself, at least on platforms like Windows (no signal-sending for graceful shutdown)
    }

    @Test
    public void keepAliveReconnectsGraceful() throws Exception {
        String agentName = "keep-alive-agent";
        Node agentNode = swarmClientRule.createSwarmClientWithName(
                agentName, "-deleteExistingClients", "-disableClientsUniqueId", "-keepAliveInterval", "2");
        assertNotNull("Agent should be created before the test case", agentNode);

        // Note: swarmClientRule.waitOnline() is included as part of creation
        Node node = j.getInstance().getNode(agentName);
        assertNotNull("Agent should be seen by server as connected before the test case", node);

        assertEquals(
                "Agent seen by server as connected and the one we created should be the same one", agentNode, node);

        // Manually remove the node from Jenkins
        logger.log(
                Level.INFO,
                "TEST-CASE keepAliveReconnectsGraceful(): Gracefully removing node as seen by the server (Remoting may close the Channel correctly; keep-alive should not interfere)");
        j.getInstance().removeNode(node);
        assertNull(
                "Agent should have been removed from the Jenkins controller as part of the test case",
                j.getInstance().getNode(agentName));

        // Wait for the agent to reconnect.
        // It should check every 2 seconds, find it's missing, and reconnect.
        // More likely though, the removeNode() activity involved protocol
        // good-byes so the agent knows it should reconnect due to that.
        long start = System.currentTimeMillis();
        logger.log(
                Level.INFO,
                "TEST-CASE keepAliveReconnectsGraceful(): Waiting up to 30 seconds for swarm agent node to reconnect to the server and be seen again");
        while (j.getInstance().getNode(agentName) == null && System.currentTimeMillis() - start < 30000) {
            Thread.sleep(1000);
        }

        logger.log(Level.INFO, "TEST-CASE keepAliveReconnectsGraceful(): The sleep is over, one way or another...");
        assertNotNull("Agent should have reconnected", j.getInstance().getNode(agentName));

        assertTrue(
                "Agent logs should contain start of the KeepAliveThread",
                swarmClientRule.logContains("Setting up KeepAliveThread"));
        assertTrue(
                "Agent logs should contain end of the KeepAliveThread",
                swarmClientRule.logContains("Stopped KeepAliveThread"));
        // Might not live long enough to send keep-alives so not checking for that yet

        logger.log(
                Level.INFO,
                "TEST-CASE keepAliveProbes(): Sleep for 5 seconds, see if there are client probes after reconnection");
        Thread.sleep(5000);
        // These entries MIGHT be from before reconnection if the test rig is slow, but shouldn't be often:
        assertTrue(
                "Agent logs should contain a few probes",
                swarmClientRule.logContains("Checking if agent is still registered"));
        assertTrue(
                "Agent logs should contain a few probes",
                swarmClientRule.logContains("OK: agent is still registered on the controller"));

        logger.log(Level.INFO, "TEST-CASE keepAliveReconnectsGraceful(): Remove agent to finish the test case");
        swarmClientRule.tearDown();
    }

    @Test
    public void keepAliveReconnectsRemoved() throws Exception {
        String agentName = "keep-alive-agent";
        Node agentNode = swarmClientRule.createSwarmClientWithName(
                agentName, "-deleteExistingClients", "-disableClientsUniqueId", "-keepAliveInterval", "2");
        assertNotNull("Agent should be created before the test case", agentNode);

        // Note: swarmClientRule.waitOnline() is included as part of creation
        Node node = j.getInstance().getNode(agentName);
        assertNotNull("Agent should be seen by server as connected before the test case", node);

        assertEquals(
                "Agent seen by server as connected and the one we created should be the same one", agentNode, node);

        assertTrue("Agent node class is (derived from) SwarmSlave", node instanceof SwarmSlave);

        // Manually remove the node from Jenkins in a way that it does not trigger a graceful disconnect via dialog
        logger.log(
                Level.INFO,
                "TEST-CASE keepAliveReconnectsRemoved(): Unilaterally removing node as seen by the server (without graceful good-byes)");
        Computer computer = node.toComputer();
        assertNotNull("Agent computer object is not null", computer);
        assertTrue("Agent computer class is (derived from) SlaveComputer", computer instanceof SlaveComputer);
        SlaveComputer agentComputer = ((SlaveComputer) computer);

        // Prevent eventually called SlaveComputer.closeChannel() from sending
        // the disconnection messages; alas, the channel is well protected.
        // Use of reflection may be an evil, but a more portable one than
        // pausing the other JVM, or bringing up temporary firewalls.
        // Maybe a better option could be to mock the class so this test runner
        // can toggle how AbstractCIBase.killComputer() acts or not, but in
        // the end, mocking uses reflection too. So here goes the evil hack:
        Field fieldChannel = agentComputer.getClass().getDeclaredField("channel");
        fieldChannel.setAccessible(true);
        fieldChannel.set(agentComputer, null);

        j.getInstance().removeNode(node);
        logger.log(
                Level.INFO, "TEST-CASE keepAliveReconnectsRemoved(): Checking that node is no longer seen by server");
        assertNull(
                "Agent should have been removed from the Jenkins controller as part of the test case",
                j.getInstance().getNode(agentName));

        // Wait for the agent to reconnect.
        // It should check every 2 seconds, find it's missing, and reconnect.
        long start = System.currentTimeMillis();
        logger.log(
                Level.INFO,
                "TEST-CASE keepAliveReconnectsRemoved(): Waiting up to 30 seconds for swarm agent node to reconnect to the server and be seen again");
        while (j.getInstance().getNode(agentName) == null && System.currentTimeMillis() - start < 30000) {
            Thread.sleep(1000);
        }

        logger.log(Level.INFO, "TEST-CASE keepAliveReconnectsRemoved(): The sleep is over, one way or another...");
        // Jenkins controller side of the test log should only say:
        //   26.976 [id=154]	INFO	j.s.DefaultJnlpSlaveReceiver#channelClosed:
        //     IOHub#1: Worker[channel:java.nio.channels.SocketChannel
        //     [connected local=/127.0.0.1:55060 remote=kubernetes.docker.internal/127.0.0.1:55066]] /
        //     Computer.threadPoolForRemoting [#7] for keep-alive-agent terminated:
        //     java.nio.channels.ClosedChannelException
        assertTrue(
                "Agent logs should contain start of the KeepAlive check",
                swarmClientRule.logContains("Checking if agent is still registered on the controller"));
        assertTrue(
                "Agent logs should report self-inflicted reconnection",
                swarmClientRule.logContains(
                        "WARNING: Agent is no longer registered on the controller. Interrupting connection to trigger reconnection"));
        assertTrue(
                "Agent logs should report failure to connect (due to self-inflicted interruption)",
                swarmClientRule.logContains("hudson.plugins.swarm.RetryException: Failed to establish connection to"));
        assertTrue(
                "Agent logs should contain end of the KeepAliveThread",
                swarmClientRule.logContains("Stopped KeepAliveThread"));
        assertTrue(swarmClientRule.logContains("Retrying in 10 seconds"));

        assertNotNull("Agent should have reconnected", j.getInstance().getNode(agentName));

        // Note: we get the agent listed a few seconds before it deems the connection finished.
        // Let it complete, to avoid noise that looks like breakage in the log.
        logger.log(
                Level.INFO,
                "TEST-CASE keepAliveReconnectsRemoved(): Server says that agent reestablished the connection; waiting a bit for it to complete, for a clean finish of the test case");
        Thread.sleep(5000);

        // Just curious... (so no assertion):
        Node node2 = j.getInstance().getNode(agentName);
        logger.log(
                Level.INFO,
                "TEST-CASE keepAliveReconnectsRemoved(): Is the new node object same as old? " + (node2 == agentNode));
        Computer computer2 = node2.toComputer();
        logger.log(
                Level.INFO,
                "TEST-CASE keepAliveReconnectsRemoved(): Is the new computer object same as old? "
                        + (computer2 == computer));

        logger.log(Level.INFO, "TEST-CASE keepAliveReconnectsRemoved(): Remove agent to finish the test case");
        swarmClientRule.tearDown();
    }

    /** An agent may be not seen in "$JENKINS_URL/computers" but still have a Node in the list */
    @Test
    public void keepAliveReconnectsDissociatedComputer() throws Exception {
        String agentName = "keep-alive-agent";
        Node agentNode = swarmClientRule.createSwarmClientWithName(
                agentName, "-deleteExistingClients", "-disableClientsUniqueId", "-keepAliveInterval", "2");
        assertNotNull("Agent should be created before the test case", agentNode);

        // Note: swarmClientRule.waitOnline() is included as part of creation
        Node node = j.getInstance().getNode(agentName);
        assertNotNull("Agent should be seen by server as connected before the test case", node);

        assertEquals(
                "Agent seen by server as connected and the one we created should be the same one", agentNode, node);

        assertTrue("Agent node class is (derived from) SwarmSlave", node instanceof SwarmSlave);

        // Manually remove the node from Jenkins in a way that it does not trigger a graceful disconnect via dialog
        logger.log(
                Level.INFO,
                "TEST-CASE keepAliveReconnectsDissociated(): Unilaterally removing computer object from node (without graceful good-byes)");

        Computer computer = node.toComputer();
        assertNotNull("Agent computer object is not null", computer);
        assertTrue("Agent computer class is (derived from) SlaveComputer", computer instanceof SlaveComputer);
        SlaveComputer agentComputer = ((SlaveComputer) computer);

        Field fieldComputers = jenkins.model.Jenkins.class.getDeclaredField("computers");
        fieldComputers.setAccessible(true);
        Object fieldComputersRaw = fieldComputers.get(j.getInstance());
        assertTrue("Jenkins.instance.computers class is a Map", fieldComputersRaw instanceof Map);
        // FIXME: Check Map key and value classes?
        //  Or just let the test crash on discrepancies?
        Map<Node, Computer> computers = (Map<Node, Computer>) fieldComputersRaw;
        computers.remove(agentNode);

        logger.log(
                Level.INFO, "TEST-CASE keepAliveReconnectsDissociated(): Checking that node is still seen by server");
        assertNotNull(
                "Agent should not have been removed from the Jenkins controller as part of the test case",
                j.getInstance().getNode(agentName));
        logger.log(
                Level.INFO, "TEST-CASE keepAliveReconnectsDissociated(): Checking that node no longer has a Computer");
        assertNull(
                "Agent should have null computer association",
                j.getInstance().getNode(agentName).toComputer());

        // Wait for the agent to reconnect.
        // It should check every 2 seconds, find it's missing, and reconnect.
        long start = System.currentTimeMillis();
        logger.log(
                Level.INFO,
                "TEST-CASE keepAliveReconnectsDissociated(): Waiting up to 30 seconds for swarm agent node to reconnect to the server and be seen again");
        while ((j.getInstance().getNode(agentName) == null
                        || j.getInstance().getNode(agentName).toComputer() == null)
                && System.currentTimeMillis() - start < 30000) {
            Thread.sleep(1000);
        }

        logger.log(Level.INFO, "TEST-CASE keepAliveReconnectsDissociated(): The sleep is over, one way or another...");
        // Jenkins controller side of the test log should only say:
        //   26.976 [id=154]	INFO	j.s.DefaultJnlpSlaveReceiver#channelClosed:
        //     IOHub#1: Worker[channel:java.nio.channels.SocketChannel
        //     [connected local=/127.0.0.1:55060 remote=kubernetes.docker.internal/127.0.0.1:55066]] /
        //     Computer.threadPoolForRemoting [#7] for keep-alive-agent terminated:
        //     java.nio.channels.ClosedChannelException
        assertTrue(
                "Agent logs should contain start of the KeepAlive check",
                swarmClientRule.logContains("Checking if agent is still registered on the controller"));
        logger.log(
                Level.INFO,
                "TEST-CASE keepAliveReconnectsDissociated(): Check if the agent noticed it was disconnected");
        assertTrue(
                "Agent logs should report self-inflicted reconnection",
                swarmClientRule.logContains(
                        "WARNING: Agent is no longer registered on the controller. Interrupting connection to trigger reconnection"));
        assertTrue(
                "Agent logs should report failure to connect (due to self-inflicted interruption)",
                swarmClientRule.logContains("hudson.plugins.swarm.RetryException: Failed to establish connection to"));
        assertTrue(
                "Agent logs should contain end of the KeepAliveThread",
                swarmClientRule.logContains("Stopped KeepAliveThread"));
        assertTrue(swarmClientRule.logContains("Retrying in 10 seconds"));

        assertNotNull("Agent should have reconnected", j.getInstance().getNode(agentName));

        // Note: we get the agent listed a few seconds before it deems the connection finished.
        // Let it complete, to avoid noise that looks like breakage in the log.
        logger.log(
                Level.INFO,
                "TEST-CASE keepAliveReconnectsDissociated(): Server says that agent reestablished the connection; waiting a bit for it to complete, for a clean finish of the test case");
        Thread.sleep(5000);

        logger.log(Level.INFO, "TEST-CASE keepAliveReconnectsDissociated(): Remove agent to finish the test case");
        swarmClientRule.tearDown();
    }
}
