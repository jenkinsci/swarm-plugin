package hudson.plugins.swarm;

import static org.junit.Assert.assertEquals;
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
import java.io.Writer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.apache.commons.lang.RandomStringUtils;
import org.junit.After;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;

public class SwarmClientIntegrationTest {

    @Rule public JenkinsRule j = new JenkinsRule();

    @ClassRule public static BuildWatcher buildWatcher = new BuildWatcher();

    @ClassRule public static TemporaryFolder temporaryFolder = new TemporaryFolder();

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
    public void tearDown() {
        try {
            processDestroyer.clean();
        } catch (InterruptedException e) {
            e.printStackTrace(System.err);
        }
    }
}
