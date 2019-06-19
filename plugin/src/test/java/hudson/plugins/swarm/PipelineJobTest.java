package hudson.plugins.swarm;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import hudson.Functions;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.plugins.swarm.test.ProcessDestroyer;
import hudson.plugins.swarm.test.TestUtils;
import java.io.File;
import java.io.FileOutputStream;
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
import org.jvnet.hudson.test.RestartableJenkinsRule;

public class PipelineJobTest {

    @Rule
    public RestartableJenkinsRule story =
            new RestartableJenkinsRule.Builder().withReusedPort().build();

    @ClassRule public static BuildWatcher buildWatcher = new BuildWatcher();

    @ClassRule public static TemporaryFolder temporaryFolder = new TemporaryFolder();

    private final ProcessDestroyer processDestroyer = new ProcessDestroyer();

    /** Executes a shell script build on a Swarm Client agent. */
    @Test
    public void buildShellScript() {
        story.then(
                s -> {
                    Node node =
                            TestUtils.createSwarmClient(story.j, processDestroyer, temporaryFolder);

                    WorkflowJob project = story.j.createProject(WorkflowJob.class);
                    project.setConcurrentBuild(false);
                    project.setDefinition(new CpsFlowDefinition(getFlow(node, 0), true));

                    WorkflowRun build = story.j.buildAndAssertSuccess(project);
                    story.j.assertLogContains("ON_SWARM_CLIENT=true", build);
                    tearDown();
                });
    }

    /**
     * Executes a shell script build on a Swarm Client agent that has disconnected while the Jenkins
     * master is still running.
     */
    @Test
    public void buildShellScriptAfterDisconnect() {
        story.then(
                s -> {
                    Node node =
                            TestUtils.createSwarmClient(
                                    story.j,
                                    processDestroyer,
                                    temporaryFolder,
                                    "-disableClientsUniqueId");

                    WorkflowJob project = story.j.createProject(WorkflowJob.class);
                    project.setConcurrentBuild(false);
                    project.setDefinition(new CpsFlowDefinition(getFlow(node, 1), true));

                    WorkflowRun build = project.scheduleBuild2(0).waitForStart();
                    SemaphoreStep.waitForStart("wait-0/1", build);
                    tearDown();

                    TestUtils.createSwarmClient(
                            node.getNodeName(),
                            story.j,
                            processDestroyer,
                            temporaryFolder,
                            "-disableClientsUniqueId");
                    SemaphoreStep.success("wait-0/1", null);
                    story.j.assertBuildStatusSuccess(story.j.waitForCompletion(build));
                    story.j.assertLogContains("ON_SWARM_CLIENT=true", build);
                    tearDown();
                });
    }

    /** Same as the preceding test, but waits in "sh" rather than "node." */
    @Test
    public void buildShellScriptAcrossDisconnect() {
        Assume.assumeFalse(
                "TODO not sure how to write a corresponding batch script", Functions.isWindows());
        story.then(
                s -> {
                    Node node =
                            TestUtils.createSwarmClient(
                                    story.j,
                                    processDestroyer,
                                    temporaryFolder,
                                    "-disableClientsUniqueId");

                    WorkflowJob project = story.j.createProject(WorkflowJob.class);
                    File f1 = new File(story.j.jenkins.getRootDir(), "f1");
                    File f2 = new File(story.j.jenkins.getRootDir(), "f2");
                    new FileOutputStream(f1).close();
                    project.setConcurrentBuild(false);

                    String script = "node('" + node.getNodeName() + "') {\n"
                            + "  sh '"
                            + "touch \"" + f2 + "\";"
                            + "while [ -f \"" + f1 + "\" ]; do sleep 1; done;"
                            + "echo finished waiting;"
                            + "rm \"" + f2 + "\""
                            + "'\n"
                            + "echo 'OK, done'\n"
                            + "}";
                    project.setDefinition(new CpsFlowDefinition(script, true));

                    WorkflowRun build = project.scheduleBuild2(0).waitForStart();
                    while (!f2.isFile()) {
                        Thread.sleep(100);
                    }
                    assertTrue(build.isBuilding());
                    Computer computer = node.toComputer();
                    assertNotNull(computer);
                    tearDown();
                    while (computer.isOnline()) {
                        Thread.sleep(100);
                    }

                    TestUtils.createSwarmClient(
                            node.getNodeName(),
                            story.j,
                            processDestroyer,
                            temporaryFolder,
                            "-disableClientsUniqueId");
                    while (computer.isOffline()) {
                        Thread.sleep(100);
                    }
                    assertTrue(f2.isFile());
                    assertTrue(f1.delete());
                    while (f2.isFile()) {
                        Thread.sleep(100);
                    }

                    story.j.assertBuildStatusSuccess(story.j.waitForCompletion(build));
                    story.j.assertLogContains("finished waiting", build);
                    story.j.assertLogContains("OK, done", build);
                    tearDown();
                });
    }

    /**
     * Starts a Jenkins job on a Swarm Client agent, restarts Jenkins while the job is running, and
     * verifies that the job continues running on the same agent after Jenkins has been restarted.
     */
    @Test
    public void buildShellScriptAfterRestart() {
        story.then(
                s -> {
                    // "-deleteExistingClients" is needed so that the Swarm Client can connect
                    // after the restart.
                    Node node =
                            TestUtils.createSwarmClient(
                                    story.j,
                                    processDestroyer,
                                    temporaryFolder,
                                    "-deleteExistingClients");

                    WorkflowJob project = story.j.createProject(WorkflowJob.class, "test");
                    project.setConcurrentBuild(false);
                    project.setDefinition(new CpsFlowDefinition(getFlow(node, 1), true));

                    WorkflowRun build = project.scheduleBuild2(0).waitForStart();
                    SemaphoreStep.waitForStart("wait-0/1", build);
                });
        story.then(
                s -> {
                    SemaphoreStep.success("wait-0/1", null);
                    WorkflowJob project =
                            story.j.jenkins.getItemByFullName("test", WorkflowJob.class);
                    assertNotNull(project);
                    WorkflowRun build = project.getBuildByNumber(1);
                    story.j.assertBuildStatusSuccess(story.j.waitForCompletion(build));
                    story.j.assertLogContains("ON_SWARM_CLIENT=true", build);
                    tearDown();
                });
    }

    private static String getFlow(Node node, int numSemaphores) {
        StringBuilder sb = new StringBuilder();
        sb.append("node('" + node.getNodeName() + "') {\n");
        for (int i = 0; i < numSemaphores; i++) {
            sb.append("  semaphore 'wait-" + i + "'\n");
        }
        // TODO: Once JENKINS-41854 is fixed, remove the next two lines.
        sb.append("}\n");
        sb.append("node('" + node.getNodeName() + "') {\n");
        sb.append(
                "  isUnix() ? sh('echo ON_SWARM_CLIENT=$ON_SWARM_CLIENT') : bat('echo ON_SWARM_CLIENT=%ON_SWARM_CLIENT%')");
        sb.append("}\n");

        return sb.toString();
    }

    private void tearDown() {
        try {
            processDestroyer.clean();
        } catch (InterruptedException e) {
            e.printStackTrace(System.err);
        }
    }
}
