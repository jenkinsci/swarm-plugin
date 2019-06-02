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
        Set<String> expected = new HashSet<>();
        expected.add("foo");
        expected.add("bar");
        Node node =
                TestUtils.createSwarmClient(
                        j, processDestroyer, temporaryFolder, "-labels", encode(expected));
        expected.add("swarm");
        assertEquals(expected, decode(node.getLabelString()));
    }

    @Test
    public void addLabelsLong() throws Exception {
        Set<String> expected = new HashSet<>();
        expected.add(RandomStringUtils.randomAlphanumeric(500));
        expected.add(RandomStringUtils.randomAlphanumeric(500));
        Node node =
                TestUtils.createSwarmClient(
                        j, processDestroyer, temporaryFolder, "-labels", encode(expected));
        expected.add("swarm");
        assertEquals(expected, decode(node.getLabelString()));
    }

    @Test
    public void addRemoveLabelsViaFileShort() throws Exception {
        File labelsFile = File.createTempFile("labelsFile", ".txt", temporaryFolder.getRoot());

        Set<String> toBeRemoved = new HashSet<>();
        toBeRemoved.add("toBeRemoved1");
        toBeRemoved.add("toBeRemoved2");
        // TODO: Due to JENKINS-45295, this is busted unless we use "-disableClientsUniqueId".
        Node node =
                TestUtils.createSwarmClient(
                        j,
                        processDestroyer,
                        temporaryFolder,
                        "-disableClientsUniqueId",
                        "-labelsFile",
                        labelsFile.getAbsolutePath(),
                        "-labels",
                        encode(toBeRemoved));

        String origLabels = node.getLabelString();

        Set<String> expected = new HashSet<>();
        expected.add("foo");
        expected.add("bar");
        try (Writer writer = new FileWriter(labelsFile)) {
            writer.write(encode(expected));
        }

        while (node.getLabelString().equals(origLabels)) {
            Thread.sleep(100);
        }

        expected.add("swarm");
        assertEquals(expected, decode(node.getLabelString()));
    }

    @Test
    public void addRemoveLabelsViaFileLong() throws Exception {
        File labelsFile = File.createTempFile("labelsFile", ".txt", temporaryFolder.getRoot());

        Set<String> toBeRemoved = new HashSet<>();
        toBeRemoved.add(RandomStringUtils.randomAlphanumeric(500));
        toBeRemoved.add(RandomStringUtils.randomAlphanumeric(500));
        // TODO: Due to JENKINS-45295, this is busted unless we use "-disableClientsUniqueId".
        Node node =
                TestUtils.createSwarmClient(
                        j,
                        processDestroyer,
                        temporaryFolder,
                        "-disableClientsUniqueId",
                        "-labelsFile",
                        labelsFile.getAbsolutePath(),
                        "-labels",
                        encode(toBeRemoved));

        String origLabels = node.getLabelString();

        Set<String> expected = new HashSet<>();
        expected.add(RandomStringUtils.randomAlphanumeric(500));
        expected.add(RandomStringUtils.randomAlphanumeric(500));
        try (Writer writer = new FileWriter(labelsFile)) {
            writer.write(encode(expected));
        }

        while (node.getLabelString().equals(origLabels)) {
            Thread.sleep(100);
        }

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
