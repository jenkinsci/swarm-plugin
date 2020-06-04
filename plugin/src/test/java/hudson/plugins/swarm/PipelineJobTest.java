package hudson.plugins.swarm;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import hudson.Functions;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.plugins.swarm.test.SwarmClientRule;

import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.Assume;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.File;
import java.io.FileOutputStream;

public class PipelineJobTest {

    @ClassRule public static BuildWatcher buildWatcher = new BuildWatcher();

    @Rule(order = 10)
    public JenkinsRule j = new JenkinsRule();

    @Rule(order = 20)
    public TemporaryFolder temporaryFolder = TemporaryFolder.builder().assureDeletion().build();

    @Rule(order = 30)
    public SwarmClientRule swarmClientRule = new SwarmClientRule(() -> j, temporaryFolder);

    /** Executes a shell script build on a Swarm agent. */
    @Test
    public void buildShellScript() throws Exception {
        Node node = swarmClientRule.createSwarmClient();

        WorkflowJob project = j.createProject(WorkflowJob.class);
        project.setConcurrentBuild(false);
        project.setDefinition(new CpsFlowDefinition(getFlow(node, 0), true));

        WorkflowRun build = j.buildAndAssertSuccess(project);
        j.assertLogContains("ON_SWARM_CLIENT=true", build);
    }

    /**
     * Executes a shell script build on a Swarm agent that has disconnected while the Jenkins master
     * is still running.
     */
    @Test
    public void buildShellScriptAfterDisconnect() throws Exception {
        Node node = swarmClientRule.createSwarmClient("-disableClientsUniqueId");

        WorkflowJob project = j.createProject(WorkflowJob.class);
        project.setConcurrentBuild(false);
        project.setDefinition(new CpsFlowDefinition(getFlow(node, 1), true));

        WorkflowRun build = project.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-0/1", build);
        swarmClientRule.tearDown();

        swarmClientRule.createSwarmClientWithName(node.getNodeName(), "-disableClientsUniqueId");
        SemaphoreStep.success("wait-0/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(build));
        j.assertLogContains("ON_SWARM_CLIENT=true", build);
    }

    /** The same as the preceding test, but waits in "sh" rather than "node." */
    @Test
    public void buildShellScriptAcrossDisconnect() throws Exception {
        Assume.assumeFalse(
                "TODO not sure how to write a corresponding batch script", Functions.isWindows());
        Node node = swarmClientRule.createSwarmClient("-disableClientsUniqueId");

        WorkflowJob project = j.createProject(WorkflowJob.class);
        File f1 = new File(j.jenkins.getRootDir(), "f1");
        File f2 = new File(j.jenkins.getRootDir(), "f2");
        new FileOutputStream(f1).close();
        project.setConcurrentBuild(false);

        String script =
                "node('"
                        + node.getNodeName()
                        + "') {\n"
                        + "  sh '"
                        + "touch \""
                        + f2
                        + "\";"
                        + "while [ -f \""
                        + f1
                        + "\" ]; do sleep 1; done;"
                        + "echo finished waiting;"
                        + "rm \""
                        + f2
                        + "\""
                        + "'\n"
                        + "echo 'OK, done'\n"
                        + "}";
        project.setDefinition(new CpsFlowDefinition(script, true));

        WorkflowRun build = project.scheduleBuild2(0).waitForStart();
        while (!f2.isFile()) {
            Thread.sleep(100L);
        }
        assertTrue(build.isBuilding());
        Computer computer = node.toComputer();
        assertNotNull(computer);
        swarmClientRule.tearDown();
        while (computer.isOnline()) {
            Thread.sleep(100L);
        }

        swarmClientRule.createSwarmClientWithName(node.getNodeName(), "-disableClientsUniqueId");
        while (computer.isOffline()) {
            Thread.sleep(100L);
        }
        assertTrue(f2.isFile());
        assertTrue(f1.delete());
        while (f2.isFile()) {
            Thread.sleep(100L);
        }

        j.assertBuildStatusSuccess(j.waitForCompletion(build));
        j.assertLogContains("finished waiting", build);
        j.assertLogContains("OK, done", build);
    }

    static String getFlow(Node node, int numSemaphores) {
        StringBuilder sb = new StringBuilder();
        sb.append("node('").append(node.getNodeName()).append("') {\n");
        for (int i = 0; i < numSemaphores; i++) {
            sb.append("  semaphore 'wait-").append(i).append("'\n");
        }
        sb.append(
                "  isUnix() ? sh('echo ON_SWARM_CLIENT=$ON_SWARM_CLIENT') : bat('echo"
                        + " ON_SWARM_CLIENT=%ON_SWARM_CLIENT%')");
        sb.append("}\n");

        return sb.toString();
    }
}
