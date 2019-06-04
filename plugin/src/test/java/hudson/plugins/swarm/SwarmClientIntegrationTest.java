package hudson.plugins.swarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static java.nio.charset.StandardCharsets.UTF_8;

import hudson.Functions;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Node;
import hudson.plugins.swarm.test.ProcessDestroyer;
import hudson.plugins.swarm.test.TestUtils;
import hudson.tasks.BatchFile;
import hudson.tasks.CommandInterpreter;
import hudson.tasks.Shell;
import org.apache.commons.lang.RandomStringUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.junit.After;
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

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;



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
        CommandInterpreter command =
                Functions.isWindows()
                        ? new BatchFile("echo ON_SWARM_CLIENT=%ON_SWARM_CLIENT%")
                        : new Shell("echo ON_SWARM_CLIENT=$ON_SWARM_CLIENT");
        project.getBuildersList().add(command);

        FreeStyleBuild build = j.buildAndAssertSuccess(project);
        j.assertLogContains("ON_SWARM_CLIENT=true", build);
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
        // Start the first client with a pid file and ensure it's up.
        Node node1 = TestUtils.createSwarmClient(
                j,
                processDestroyer,
                temporaryFolder,
                "-pidFile",
                pidFile.getAbsolutePath());


        int firstClientPid = readPidFromFile(pidFile);

        // Try to start a second and ensure it fails to run.
        // Do not wait for it to come up on the server side.
        TestUtils.SwarmClientProcessWrapper node2 = TestUtils.runSwarmClient(
                "agent_fail",
                j,
                processDestroyer,
                temporaryFolder,
                "-pidFile",
                pidFile.getAbsolutePath());

        node2.process.waitFor();
        assertFalse("Second client should fail to start", node2.process.isAlive());
        assertEquals("Exit code", 1, node2.process.exitValue());
        assertTrue("Log message mentions 'already exists'",
                Files.readAllLines(node2.stderr.toPath()).stream().anyMatch(line -> line.contains("already exists")));

        //now let's ensure that the original process is still OK and so is its pid file
        assertNotNull("Original client should still be running", os.getProcess(firstClientPid));
        assertEquals("Pid in PID file should not change", firstClientPid, readPidFromFile(pidFile));
    }

    @Test
    public void pidFileForStaleProcessIsIgnored() throws Exception {
        File pidFile = getPidFile();
        Files.write(pidFile.toPath(), "66000".getBytes());

        // Pid file should be ignored since the process isn't running.
        TestUtils.createSwarmClient(
                j,
                processDestroyer,
                temporaryFolder,
                "-pidFile",
                pidFile.getAbsolutePath());

        int newPid = readPidFromFile(pidFile);

        // Java Process doesn't provide the pid, so we have to work around it.
        // Find all of our child processes, one of them must be the client we just started,
        // and thus would match the pid in the pidFile.
        OSProcess[] childProcesses = os.getChildProcesses(os.getProcessId(), 0, null);
        assertTrue("Pid in pidFile must match our new pid",
                Arrays.stream(childProcesses).anyMatch(proc -> proc.getProcessID() == newPid));
    }

    @Test
    public void pidFileDeletedOnExit() throws Exception {
        File pidFile = getPidFile();
        TestUtils.SwarmClientProcessWrapper node = TestUtils.runSwarmClient(
                "agentDeletePid",
                j,
                processDestroyer,
                temporaryFolder,
                "-pidFile",
                pidFile.getAbsolutePath());

        while(!pidFile.exists()) {
            Thread.sleep(1000); //ensure the process writes the pid
        }
        assertTrue("Pid file created", pidFile.exists());
        node.process.destroy();
        node.process.waitFor();
        assertFalse("Client should exit on kill", node.process.isAlive());
        //wait for pid file to disappear
        while(pidFile.exists()) {
            Thread.sleep(1000); //ensure the process deletes the pid on exit
        }
        assertFalse("Pid file removed", pidFile.exists());
    }

    /**
     * @return a dedicated unique pid file object for an as yet non-existent file.
     * @throws IOException
     */
    public File getPidFile() throws IOException {
        File pidFile = File.createTempFile("swarm-client", ".pid", temporaryFolder.getRoot());
        pidFile.delete(); //we want the process to create it, here we just want a unique name.
        return pidFile;
    }

    public int readPidFromFile(File pidFile) throws IOException {
        return NumberUtils.toInt(
                new String(Files.readAllBytes(pidFile.toPath()), UTF_8));
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
