package hudson.plugins.swarm;

import hudson.Functions;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Node;
import hudson.plugins.swarm.test.ProcessDestroyer;
import hudson.plugins.swarm.test.TestUtils;
import hudson.tasks.BatchFile;
import hudson.tasks.CommandInterpreter;
import hudson.tasks.Shell;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.BuildWatcher;
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
        tearDown();
    }

    private void tearDown() {
        try {
            processDestroyer.clean();
        } catch (InterruptedException e) {
            e.printStackTrace(System.err);
        }
    }
}
