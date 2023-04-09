package hudson.plugins.swarm;

import static org.junit.Assert.assertNotNull;

import hudson.model.Node;
import hudson.plugins.swarm.test.SwarmClientRule;
import java.util.concurrent.TimeUnit;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.support.steps.ExecutorStepExecution;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.JenkinsSessionRule;

public class PipelineJobRestartTest {

    @ClassRule
    public static BuildWatcher buildWatcher = new BuildWatcher();

    @Rule(order = 10)
    public JenkinsSessionRule sessions = new JenkinsSessionRule();

    /** For use with {@link #swarmClientRule} */
    private JenkinsRule holder;

    @Rule(order = 20)
    public TemporaryFolder temporaryFolder =
            TemporaryFolder.builder().assureDeletion().build();

    @Rule(order = 30)
    public SwarmClientRule swarmClientRule = new SwarmClientRule(() -> holder, temporaryFolder);

    /**
     * Starts a Jenkins job on a Swarm agent, restarts Jenkins while the job is running, and
     * verifies that the job continues running on the same agent after Jenkins has been restarted.
     */
    @Test
    public void buildShellScriptAfterRestart() throws Throwable {
        // Extend the timeout to make flaky tests less flaky.
        ExecutorStepExecution.TIMEOUT_WAITING_FOR_NODE_MILLIS = TimeUnit.MINUTES.toMillis(1);

        sessions.then(r -> {
            holder = r;
            swarmClientRule.globalSecurityConfigurationBuilder().build();

            // "-deleteExistingClients" is needed so that the Swarm Client can connect
            // after the restart.
            Node node = swarmClientRule.createSwarmClient("-deleteExistingClients");

            WorkflowJob project = r.createProject(WorkflowJob.class, "test");
            project.setConcurrentBuild(false);
            project.setDefinition(new CpsFlowDefinition(PipelineJobTest.getFlow(node, 1), true));

            WorkflowRun build = project.scheduleBuild2(0).waitForStart();
            SemaphoreStep.waitForStart("wait-0/1", build);
        });
        sessions.then(r -> {
            holder = r;
            SemaphoreStep.success("wait-0/1", null);
            WorkflowJob project = r.jenkins.getItemByFullName("test", WorkflowJob.class);
            assertNotNull(project);
            WorkflowRun build = project.getBuildByNumber(1);
            r.assertBuildStatusSuccess(r.waitForCompletion(build));
            r.assertLogContains("ON_SWARM_CLIENT=true", build);
        });
    }
}
