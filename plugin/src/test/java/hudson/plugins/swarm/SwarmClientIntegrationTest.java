package hudson.plugins.swarm;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import hudson.Functions;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Node;
import hudson.plugins.swarm.test.ProcessDestroyer;
import hudson.plugins.swarm.test.TestUtils;
import hudson.tasks.BatchFile;
import hudson.tasks.CommandInterpreter;
import hudson.tasks.Shell;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.apache.commons.lang.RandomStringUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.After;
import org.junit.Assume;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import oshi.SystemInfo;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;

public class SwarmClientIntegrationTest {

    @Rule public JenkinsRule j = new JenkinsRule();

    @ClassRule public static BuildWatcher buildWatcher = new BuildWatcher();

    @ClassRule public static TemporaryFolder temporaryFolder = new TemporaryFolder();

    public static OperatingSystem os = new SystemInfo().getOperatingSystem();

    private final ProcessDestroyer processDestroyer = new ProcessDestroyer();

    /** Executes a shell script build on a Swarm Client agent. */
    @Test
    public void buildShellScript() throws Exception {
        Node node = TestUtils.createSwarmClient(j, processDestroyer, temporaryFolder);

        FreeStyleProject project = j.createFreeStyleProject();
        project.setConcurrentBuild(false);
        project.setAssignedNode(node);
        project.getBuildersList().add(echoCommand("ON_SWARM_CLIENT"));

        FreeStyleBuild build = j.buildAndAssertSuccess(project);
        j.assertLogContains("ON_SWARM_CLIENT=true", build);
    }

    @Test
    public void environmentVariables() throws Exception {
        Node node =
                TestUtils.createSwarmClient(
                        j,
                        processDestroyer,
                        temporaryFolder,
                        "-e",
                        "SWARM_VAR_1=foo",
                        "-e",
                        "SWARM_VAR_2=bar");

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
        Node node =
                TestUtils.createSwarmClient(
                        j, processDestroyer, temporaryFolder, "-description", "swarm_ip:127.0.0.1");
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
        Set<String> expected = new HashSet<>(Arrays.asList(labels));
        Node node =
                TestUtils.createSwarmClient(
                        j, processDestroyer, temporaryFolder, "-labels", encode(expected));
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
        File pidFile = getPidFile();
        // Start the first client with a PID file and ensure it's up.
        TestUtils.createSwarmClient(
                j,
                processDestroyer,
                temporaryFolder,
                "-pidFile",
                pidFile.getAbsolutePath());

        int firstClientPid = readPidFromFile(pidFile);

        // Try to start a second and ensure it fails to run.
        // Do not wait for it to come up on the server side.
        TestUtils.SwarmClientProcessWrapper node2 =
                TestUtils.runSwarmClient(
                        "agent_fail",
                        j,
                        processDestroyer,
                        temporaryFolder,
                        "-pidFile",
                        pidFile.getAbsolutePath());

        node2.process.waitFor();
        assertFalse("Second client should fail to start", node2.process.isAlive());
        assertEquals("Exit code", 1, node2.process.exitValue());
        assertTrue(
                "Log message mentions 'already exists' in: " + Files.readAllLines(node2.stderr.toPath()),
                Files.readAllLines(node2.stderr.toPath()).stream()
                        .anyMatch(line -> line.contains("already exists")));

        // now let's ensure that the original process is still OK and so is its PID file
        assertNotNull("Original client should still be running", os.getProcess(firstClientPid));
        assertEquals("PID in PID file should not change", firstClientPid, readPidFromFile(pidFile));
    }

    @Test
    public void pidFileForStaleProcessIsIgnored() throws Exception {
        File pidFile = getPidFile();
        Files.write(pidFile.toPath(), "66000".getBytes());

        // PID file should be ignored since the process isn't running.
        TestUtils.createSwarmClient(
                j, processDestroyer, temporaryFolder, "-pidFile", pidFile.getAbsolutePath());

        int newPid = readPidFromFile(pidFile);

        // Java Process doesn't provide the PID, so we have to work around it.
        // Find all of our child processes, one of them must be the client we just started,
        // and thus would match the PID in the PID file.
        OSProcess[] childProcesses = os.getChildProcesses(os.getProcessId(), 0, null);
        assertTrue(
                "PID in PID file must match our new PID",
                Arrays.stream(childProcesses).anyMatch(proc -> proc.getProcessID() == newPid));
    }

    @Test
    public void pidFileDeletedOnExit() throws Exception {
        Assume.assumeFalse(
                "TODO The PID file doesn't seem to be deleted on exit on Windows",
                Functions.isWindows());

        File pidFile = getPidFile();
        TestUtils.SwarmClientProcessWrapper node =
                TestUtils.runSwarmClient(
                        "agentDeletePid",
                        j,
                        processDestroyer,
                        temporaryFolder,
                        "-pidFile",
                        pidFile.getAbsolutePath());

        while (!pidFile.exists()) {
            Thread.sleep(1000); // ensure the process writes the PID
        }
        assertTrue("PID file created", pidFile.exists());
        node.process.destroy();
        node.process.waitFor();
        assertFalse("Client should exit on kill", node.process.isAlive());
        assertFalse("PID file removed", pidFile.exists());
    }


    @Test
    public void gracefulShutdownOnKill() throws Exception {
        TestUtils.SwarmClientProcessWrapper node =
                TestUtils.runSwarmClient(
                        "agentDeletePid",
                        j,
                        processDestroyer,
                        temporaryFolder);

        TestUtils.waitForNode("agentDeletePid", j);


        // Now run a job that will hold up the node.
        WorkflowJob project = j.createProject(WorkflowJob.class);
        project.setDefinition(new CpsFlowDefinition("sleep(10)", true));
        WorkflowRun build = project.scheduleBuild2(0).waitForStart();

        node.process.destroy();
        node.process.waitFor();

        j.assertBuildStatusSuccess(j.waitForCompletion(build));
    }

    /**
     * @return a dedicated unique PID file object for an as yet non-existent file.
     * @throws IOException
     */
    private static File getPidFile() throws IOException {
        File pidFile = File.createTempFile("swarm-client", ".pid", temporaryFolder.getRoot());
        pidFile.delete(); // we want the process to create it, here we just want a unique name.
        return pidFile;
    }

    private static int readPidFromFile(File pidFile) throws IOException {
        return NumberUtils.toInt(new String(Files.readAllBytes(pidFile.toPath()), UTF_8));
    }

    private void addRemoveLabelsViaFile(
            Set<String> labelsToRemove, Set<String> labelsToAdd, boolean withUniqueId)
            throws Exception {
        File labelsFile = File.createTempFile("labelsFile", ".txt", temporaryFolder.getRoot());

        Node node;
        if (withUniqueId) {
            node =
                    TestUtils.createSwarmClient(
                            j,
                            processDestroyer,
                            temporaryFolder,
                            "-labelsFile",
                            labelsFile.getAbsolutePath(),
                            "-labels",
                            encode(labelsToRemove));
        } else {
            node =
                    TestUtils.createSwarmClient(
                            j,
                            processDestroyer,
                            temporaryFolder,
                            "-disableClientsUniqueId",
                            "-labelsFile",
                            labelsFile.getAbsolutePath(),
                            "-labels",
                            encode(labelsToRemove));
        }

        String origLabels = node.getLabelString();

        try (Writer writer = new FileWriter(labelsFile)) {
            writer.write(encode(labelsToAdd));
        }

        // TODO: This is a bit racy, since updates are not atomic.
        while (node.getLabelString().equals(origLabels)
                || decode(node.getLabelString()).equals(decode("swarm"))) {
            Thread.sleep(1000);
        }

        Set<String> expected = new HashSet<>(labelsToAdd);
        expected.add("swarm");
        assertEquals(expected, decode(node.getLabelString()));
    }

    private static String encode(Set<String> labels) {
        return String.join(" ", labels);
    }

    private static Set<String> decode(String labels) {
        return new HashSet<>(Arrays.asList(labels.split("\\s+")));
    }

    @After
    public void tearDown() throws IOException {
        try {
            processDestroyer.clean();
        } catch (InterruptedException e) {
            e.printStackTrace(System.err);
        }
        getPidFile().delete();
    }
}
