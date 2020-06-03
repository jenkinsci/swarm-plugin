package hudson.plugins.swarm;

import static org.junit.Assert.assertNotNull;

import hudson.model.Node;
import hudson.plugins.swarm.test.SwarmClientRule;

import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.RestartableJenkinsRule;

public class PipelineJobRestartTest {

    @ClassRule public static BuildWatcher buildWatcher = new BuildWatcher();

    /** Static so that it can be used with {@link #swarmClientRule}. */
    private static final RestartableJenkinsRule holder =
            new RestartableJenkinsRule.Builder().withReusedPort().build();

    @Rule public RestartableJenkinsRule story = holder;

    /** A {@link ClassRule} for compatibility with {@link RestartableJenkinsRule}. */
    @ClassRule(order = 20)
    public static TemporaryFolder temporaryFolder =
            TemporaryFolder.builder().assureDeletion().build();

    /** A {@link ClassRule} for compatibility with {@link RestartableJenkinsRule}. */
    @ClassRule(order = 30)
    public static SwarmClientRule swarmClientRule =
            new SwarmClientRule(() -> holder.j, temporaryFolder);

    /**
     * Starts a Jenkins job on a Swarm agent, restarts Jenkins while the job is running, and
     * verifies that the job continues running on the same agent after Jenkins has been restarted.
     */
    @Test
    public void buildShellScriptAfterRestart() {
        story.then(
                s -> {
                    // "-deleteExistingClients" is needed so that the Swarm Client can connect
                    // after the restart.
                    Node node = swarmClientRule.createSwarmClient("-deleteExistingClients");

                    WorkflowJob project = story.j.createProject(WorkflowJob.class, "test");
                    project.setConcurrentBuild(false);
                    project.setDefinition(
                            new CpsFlowDefinition(PipelineJobTest.getFlow(node, 1), true));

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
                });
    }
}
