package hudson.plugins.swarm;

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
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.commons.lang.RandomStringUtils;
import org.apache.commons.lang.math.NumberUtils;
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

    @Rule public TemporaryFolder temporaryRemotingFolder = new TemporaryFolder();

    private final OperatingSystem os = new SystemInfo().getOperatingSystem();

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
        Files.write(pidFile.toPath(), "66000".getBytes(StandardCharsets.UTF_8));

        // PID file should be ignored since the process isn't running.
        TestUtils.createSwarmClient(
                j, processDestroyer, temporaryFolder, "-pidFile", pidFile.getAbsolutePath());

        int newPid = readPidFromFile(pidFile);

        // Java Process doesn't provide the PID, so we have to work around it.
        // Find all of our child processes, one of them must be the client we just started,
        // and thus would match the PID in the PID file.
        List<OSProcess> childProcesses = os.getChildProcesses(os.getProcessId(), 0, null);
        assertTrue(
                "PID in PID file must match our new PID",
                childProcesses.stream().anyMatch(proc -> proc.getProcessID() == newPid));
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

    /**
     * @return a dedicated unique PID file object for an as yet non-existent file.
     */
    private static File getPidFile() throws IOException {
        File pidFile = File.createTempFile("swarm-client", ".pid", temporaryFolder.getRoot());
        Files.delete(pidFile.toPath()); // we want the process to create it, here we just want a unique name.
        return pidFile;
    }

    private static int readPidFromFile(File pidFile) throws IOException {
        return NumberUtils.toInt(
                new String(Files.readAllBytes(pidFile.toPath()), StandardCharsets.UTF_8));
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

        try (Writer writer = Files.newBufferedWriter(labelsFile.toPath(), StandardCharsets.UTF_8)) {
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

    @Test
    public void defaultDescription() throws Exception {
        Node node = TestUtils.createSwarmClient(j, processDestroyer, temporaryFolder);
        assertTrue(
                node.getNodeDescription(),
                Pattern.matches("Swarm slave from ([a-zA-Z_0-9-\\.]+)", node.getNodeDescription()));
    }

    @Test
    public void customDescription() throws Exception {
        Node node =
                TestUtils.createSwarmClient(
                        j, processDestroyer, temporaryFolder, "-description", "foobar");
        assertTrue(
                node.getNodeDescription(),
                Pattern.matches(
                        "Swarm slave from ([a-zA-Z_0-9-\\.]+): foobar",
                        node.getNodeDescription()));
    }

    @Test
    public void missingMasterOption() throws Exception {
        File swarmClientJar =
                File.createTempFile("swarm-client", ".jar", temporaryFolder.getRoot());
        TestUtils.download(j.getURL(), swarmClientJar);

        List<String> command = new ArrayList<>();
        command.add(
                System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
        command.add("-Djava.awt.headless=true");
        command.add("-jar");
        command.add(swarmClientJar.toString());

        ProcessBuilder pb = new ProcessBuilder(command);
        File stdout = File.createTempFile("stdout", ".log", temporaryFolder.getRoot());
        pb.redirectOutput(stdout);
        File stderr = File.createTempFile("stderr", ".log", temporaryFolder.getRoot());
        pb.redirectError(stderr);
        Process process = pb.start();
        processDestroyer.record(process);
        process.waitFor();
        assertFalse("Client should fail to start", process.isAlive());
        assertEquals("Exit code should be 1", 1, process.exitValue());
    }

    @Test
    public void workDirEnabledByDefaultWithFsRootAsDefaultPath() throws Exception {
        final File fsRootPath = temporaryRemotingFolder.newFolder("fsrootdir");
        TestUtils.createSwarmClient(
                j, processDestroyer, temporaryFolder, "-fsroot", fsRootPath.getAbsolutePath());

        assertDirectories(
                fsRootPath,
                new File(fsRootPath, "remoting"),
                new File(fsRootPath, "remoting/logs"),
                new File(fsRootPath, "remoting/jarCache"));
    }

    @Test
    public void workDirWithCustomPath() throws Exception {
        final File workDirPath = new File(temporaryRemotingFolder.getRoot(), "customworkdir");
        TestUtils.createSwarmClient(
                j, processDestroyer, temporaryFolder, "-workDir", workDirPath.getAbsolutePath());

        assertDirectories(
                workDirPath,
                new File(workDirPath, "remoting"),
                new File(workDirPath, "remoting/logs"),
                new File(workDirPath, "remoting/jarCache"));
    }

    @Test
    public void disableWorkDirRunsInLegacyMode() throws Exception {
        final File fsRootPath = temporaryRemotingFolder.newFolder("fsrootdir");
        TestUtils.createSwarmClient(
                j,
                processDestroyer,
                temporaryFolder,
                "-fsroot",
                fsRootPath.getAbsolutePath(),
                "-disableWorkDir");

        assertDirectories(fsRootPath);
        assertNoDirectories(
                new File(fsRootPath, "remoting"),
                new File(fsRootPath, "remoting/logs"),
                new File(fsRootPath, "remoting/jarCache"));
    }

    @Test
    public void failIfWorkDirIsMissingDoesNothingIfDirectoryExists() throws Exception {
        final File fsRootPath = temporaryRemotingFolder.newFolder("fsrootdir");
        final File workDirPath = temporaryFolder.newFolder("remoting");
        TestUtils.createSwarmClient(
                j,
                processDestroyer,
                temporaryFolder,
                "-fsroot",
                fsRootPath.getAbsolutePath(),
                "-workDir",
                workDirPath.getParent(),
                "-failIfWorkDirIsMissing");

        assertDirectories(
                fsRootPath,
                workDirPath,
                new File(workDirPath, "logs"),
                new File(workDirPath, "jarCache"));
    }

    @Test
    public void failIfWorkDirIsMissingFailsOnMissingWorkDir() throws Exception {
        final File fsRootPath = temporaryRemotingFolder.newFolder("fsrootdir");
        final File workDirPath = new File(temporaryRemotingFolder.getRoot(), "customworkdir");
        TestUtils.SwarmClientProcessWrapper node =
                TestUtils.runSwarmClient(
                        "should_fail",
                        j,
                        processDestroyer,
                        temporaryFolder,
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

        node.process.waitFor();
        assertEquals("Process fails", 1, node.process.exitValue());

        assertDirectories(fsRootPath);
        assertNoDirectories(
                new File(fsRootPath, "remoting"),
                new File(fsRootPath, "remoting/logs"),
                new File(fsRootPath, "remoting/jarCache"));
    }

    @Test
    public void internalDirIsInWorkDirByDefault() throws Exception {
        final File fsRootPath = temporaryRemotingFolder.newFolder("fsrootdir");
        TestUtils.createSwarmClient(
                j, processDestroyer, temporaryFolder, "-fsroot", fsRootPath.getAbsolutePath());

        assertDirectories(
                fsRootPath,
                new File(fsRootPath, "remoting"),
                new File(fsRootPath, "remoting/logs"),
                new File(fsRootPath, "remoting/jarCache"));
    }

    @Test
    public void internalDirWithCustomPath() throws Exception {
        final File fsRootPath = temporaryRemotingFolder.newFolder("fsrootdir");
        TestUtils.createSwarmClient(
                j,
                processDestroyer,
                temporaryFolder,
                "-fsroot",
                fsRootPath.getAbsolutePath(),
                "-internalDir",
                "custominternaldir");

        assertDirectories(
                fsRootPath,
                new File(fsRootPath, "custominternaldir"),
                new File(fsRootPath, "custominternaldir/logs"),
                new File(fsRootPath, "custominternaldir/jarCache"));
    }

    @Test
    public void jarCacheWithCustomPath() throws Exception {
        final File fsRootPath = temporaryRemotingFolder.newFolder("fsrootdir");
        final File jarCachePath = new File(temporaryRemotingFolder.getRoot(), "customjarcache");
        TestUtils.createSwarmClient(
                j,
                processDestroyer,
                temporaryFolder,
                "-fsroot",
                fsRootPath.getAbsolutePath(),
                "-jar-cache",
                jarCachePath.getPath());

        assertDirectories(
                fsRootPath,
                new File(fsRootPath, "remoting"),
                new File(fsRootPath, "remoting/logs"),
                jarCachePath);
    }

    @After
    public void tearDown() throws IOException {
        try {
            processDestroyer.clean();
        } catch (InterruptedException e) {
            e.printStackTrace(System.err);
        }
        Files.deleteIfExists(getPidFile().toPath());
    }

    private void assertDirectories(File... paths) {
        Arrays.stream(paths)
                .forEach(path -> assertTrue(path.getPath() + " exists", path.isDirectory()));
    }

    private void assertNoDirectories(File... paths) {
        Arrays.stream(paths)
                .forEach(path -> assertFalse(path.getPath() + " not exists", path.isDirectory()));
    }
}
