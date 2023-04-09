package hudson.plugins.swarm;

import static org.junit.Assert.assertEquals;

import com.michelin.cio.hudson.plugins.rolestrategy.RoleBasedAuthorizationStrategy;
import hudson.model.Node;
import hudson.plugins.swarm.test.SwarmClientRule;
import hudson.security.AuthorizationStrategy;
import hudson.security.FullControlOnceLoggedInAuthorizationStrategy;
import hudson.security.GlobalMatrixAuthorizationStrategy;
import hudson.security.ProjectMatrixAuthorizationStrategy;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.apache.commons.lang.RandomStringUtils;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.JenkinsRule;

public class AuthorizationStrategyTest {

    @ClassRule
    public static BuildWatcher buildWatcher = new BuildWatcher();

    @Rule(order = 10)
    public JenkinsRule j = new JenkinsRule();

    @Rule(order = 20)
    public TemporaryFolder temporaryFolder =
            TemporaryFolder.builder().assureDeletion().build();

    @Rule(order = 30)
    public SwarmClientRule swarmClientRule = new SwarmClientRule(() -> j, temporaryFolder);

    @Test
    public void anyoneCanDoAnything() throws Exception {
        AuthorizationStrategy authorizationStrategy = AuthorizationStrategy.UNSECURED;
        swarmClientRule
                .globalSecurityConfigurationBuilder()
                .setSwarmUsername(null)
                .setSwarmPassword(null)
                .setSwarmToken(false)
                .setAuthorizationStrategy(authorizationStrategy)
                .build();
        addRemoveLabelsViaFileWithUniqueIdLong();
    }

    @Test
    public void loggedInUsersCanDoAnythingAnonymousRead() throws Exception {
        FullControlOnceLoggedInAuthorizationStrategy authorizationStrategy =
                new FullControlOnceLoggedInAuthorizationStrategy();
        authorizationStrategy.setAllowAnonymousRead(true);
        swarmClientRule
                .globalSecurityConfigurationBuilder()
                .setAuthorizationStrategy(authorizationStrategy)
                .build();
        addRemoveLabelsViaFileWithUniqueIdLong();
    }

    @Test
    public void loggedInUsersCanDoAnythingNoAnonymousRead() throws Exception {
        FullControlOnceLoggedInAuthorizationStrategy authorizationStrategy =
                new FullControlOnceLoggedInAuthorizationStrategy();
        authorizationStrategy.setAllowAnonymousRead(false);
        swarmClientRule
                .globalSecurityConfigurationBuilder()
                .setAuthorizationStrategy(authorizationStrategy)
                .build();
        addRemoveLabelsViaFileWithUniqueIdLong();
    }

    @Test
    public void matrixBasedSecurity() throws Exception {
        GlobalMatrixAuthorizationStrategy authorizationStrategy = new GlobalMatrixAuthorizationStrategy();
        swarmClientRule
                .globalSecurityConfigurationBuilder()
                .setAuthorizationStrategy(authorizationStrategy)
                .build();
        addRemoveLabelsViaFileWithUniqueIdLong();
    }

    @Test
    public void projectBasedMatrixAuthorizationStrategy() throws Exception {
        ProjectMatrixAuthorizationStrategy authorizationStrategy = new ProjectMatrixAuthorizationStrategy();
        swarmClientRule
                .globalSecurityConfigurationBuilder()
                .setAuthorizationStrategy(authorizationStrategy)
                .build();
        addRemoveLabelsViaFileWithUniqueIdLong();
    }

    @Test
    public void roleBasedStrategy() throws Exception {
        RoleBasedAuthorizationStrategy authorizationStrategy = new RoleBasedAuthorizationStrategy();
        swarmClientRule
                .globalSecurityConfigurationBuilder()
                .setAuthorizationStrategy(authorizationStrategy)
                .build();
        addRemoveLabelsViaFileWithUniqueIdLong();
    }

    /** This test exercises all of the API endpoints, including all permission checks. */
    private void addRemoveLabelsViaFileWithUniqueIdLong() throws Exception {
        Set<String> labelsToRemove = new HashSet<>();
        labelsToRemove.add(RandomStringUtils.randomAlphanumeric(350));
        labelsToRemove.add(RandomStringUtils.randomAlphanumeric(350));
        labelsToRemove.add(RandomStringUtils.randomAlphanumeric(350));

        Set<String> labelsToAdd = new HashSet<>();
        labelsToAdd.add(RandomStringUtils.randomAlphanumeric(350));
        labelsToAdd.add(RandomStringUtils.randomAlphanumeric(350));
        labelsToAdd.add(RandomStringUtils.randomAlphanumeric(350));

        Path labelsFile = Files.createTempFile(temporaryFolder.getRoot().toPath(), "labelsFile", ".txt");

        Node node = swarmClientRule.createSwarmClient(
                "-labelsFile", labelsFile.toAbsolutePath().toString(), "-labels", encode(labelsToRemove));

        String origLabels = node.getLabelString();

        try (Writer writer = Files.newBufferedWriter(labelsFile, StandardCharsets.UTF_8)) {
            writer.write(encode(labelsToAdd));
        }

        // TODO: This is a bit racy, since updates are not atomic.
        while (node.getLabelString().equals(origLabels)
                || decode(node.getLabelString()).equals(decode("swarm"))) {
            Thread.sleep(100L);
        }

        Set<String> expected = new HashSet<>(labelsToAdd);
        expected.add("swarm");
        assertEquals(expected, decode(node.getLabelString()));
    }

    private static String encode(Set<String> labels) {
        return String.join(" ", labels);
    }

    private static Set<String> decode(String labels) {
        return Set.of(labels.split("\\s+"));
    }
}
