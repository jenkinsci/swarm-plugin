package hudson.plugins.swarm.test;

import com.michelin.cio.hudson.plugins.rolestrategy.Role;
import com.michelin.cio.hudson.plugins.rolestrategy.RoleBasedAuthorizationStrategy;
import com.michelin.cio.hudson.plugins.rolestrategy.RoleMap;
import hudson.model.Computer;
import hudson.model.User;
import hudson.security.AuthorizationStrategy;
import hudson.security.GlobalMatrixAuthorizationStrategy;
import hudson.security.HudsonPrivateSecurityRealm;
import hudson.security.ProjectMatrixAuthorizationStrategy;
import hudson.security.csrf.DefaultCrumbIssuer;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.matrixauth.AuthorizationType;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;

/**
 * A helper class that configures the test Jenkins controller with various realistic global security
 * configurations.
 */
public class GlobalSecurityConfigurationBuilder {

    private final SwarmClientRule swarmClientRule;

    private String adminUsername = "admin";
    private String adminPassword = "admin";
    private String swarmUsername = "swarm";
    private String swarmPassword = "honeycomb";
    private boolean swarmToken = true;
    private boolean csrf = true;
    private AuthorizationStrategy authorizationStrategy = new ProjectMatrixAuthorizationStrategy();

    public GlobalSecurityConfigurationBuilder(SwarmClientRule swarmClientRule) {
        this.swarmClientRule = swarmClientRule;
    }

    public GlobalSecurityConfigurationBuilder setAdminUsername(String adminUsername) {
        this.adminUsername = adminUsername;
        return this;
    }

    public GlobalSecurityConfigurationBuilder setAdminPassword(String adminPassword) {
        this.adminPassword = adminPassword;
        return this;
    }

    public GlobalSecurityConfigurationBuilder setSwarmUsername(String swarmUsername) {
        this.swarmUsername = swarmUsername;
        return this;
    }

    public GlobalSecurityConfigurationBuilder setSwarmPassword(String swarmPassword) {
        this.swarmPassword = swarmPassword;
        return this;
    }

    public GlobalSecurityConfigurationBuilder setSwarmToken(boolean swarmToken) {
        this.swarmToken = swarmToken;
        return this;
    }

    public GlobalSecurityConfigurationBuilder setCsrf(boolean csrf) {
        this.csrf = csrf;
        return this;
    }

    public GlobalSecurityConfigurationBuilder setAuthorizationStrategy(AuthorizationStrategy authorizationStrategy) {
        this.authorizationStrategy = authorizationStrategy;
        return this;
    }

    public void build() throws IOException {
        // Configure the security realm.
        HudsonPrivateSecurityRealm realm = new HudsonPrivateSecurityRealm(false, false, null);

        realm.createAccount(adminUsername, adminPassword);

        User swarmUser = null;
        if (swarmUsername != null && swarmPassword != null) {
            swarmUser = realm.createAccount(swarmUsername, swarmPassword);
        }
        swarmClientRule.swarmUsername = swarmUsername;
        swarmClientRule.swarmPassword = swarmPassword;

        if (swarmToken) {
            String token = swarmClientRule.j.get().createApiToken(swarmUser);
            swarmUser.save();
            swarmClientRule.swarmPassword = token;
        }

        swarmClientRule.j.get().jenkins.setSecurityRealm(realm);

        // Configure the authorization strategy.
        if (authorizationStrategy instanceof GlobalMatrixAuthorizationStrategy) {
            GlobalMatrixAuthorizationStrategy strategy = (GlobalMatrixAuthorizationStrategy) authorizationStrategy;
            // Not needed for testing, but is helpful when debugging test failures.
            strategy.add(Jenkins.ADMINISTER, new PermissionEntry(AuthorizationType.USER, adminUsername));

            // Needed for the [optional] Jenkins version check as well as CSRF.
            strategy.add(Jenkins.READ, new PermissionEntry(AuthorizationType.GROUP, "authenticated"));

            // Needed to create and connect the agent.
            strategy.add(Computer.CREATE, new PermissionEntry(AuthorizationType.USER, swarmUsername));
            strategy.add(Computer.CONNECT, new PermissionEntry(AuthorizationType.USER, swarmUsername));

            /*
             * The following is necessary because
             * AuthorizationMatrixNodeProperty.NodeListenerImpl#onCreated only applies to
             * ProjectMatrixAuthorizationStrategy.
             */
            if (!(authorizationStrategy instanceof ProjectMatrixAuthorizationStrategy)) {
                // Needed to add/remove labels after the fact.
                strategy.add(Computer.CONFIGURE, new PermissionEntry(AuthorizationType.USER, swarmUsername));
            }
        } else if (authorizationStrategy instanceof RoleBasedAuthorizationStrategy) {
            Role admin = new Role("admin", ".*", Set.of(Jenkins.ADMINISTER.getId()), "Jenkins administrators");
            Role readOnly = new Role("readonly", ".*", Set.of(Jenkins.READ.getId()), "Read-only users");
            Role swarm = new Role(
                    "swarm",
                    ".*",
                    Set.of(Computer.CREATE.getId(), Computer.CONNECT.getId(), Computer.CONFIGURE.getId()),
                    "Swarm users");

            SortedMap<Role, Set<com.michelin.cio.hudson.plugins.rolestrategy.PermissionEntry>> roleMap =
                    new TreeMap<>();
            roleMap.put(
                    admin,
                    Set.of(new com.michelin.cio.hudson.plugins.rolestrategy.PermissionEntry(
                            com.michelin.cio.hudson.plugins.rolestrategy.AuthorizationType.USER, adminUsername)));
            roleMap.put(
                    readOnly,
                    Set.of(new com.michelin.cio.hudson.plugins.rolestrategy.PermissionEntry(
                            com.michelin.cio.hudson.plugins.rolestrategy.AuthorizationType.GROUP, "authenticated")));
            roleMap.put(
                    swarm,
                    Set.of(new com.michelin.cio.hudson.plugins.rolestrategy.PermissionEntry(
                            com.michelin.cio.hudson.plugins.rolestrategy.AuthorizationType.USER, swarmUsername)));

            authorizationStrategy = new RoleBasedAuthorizationStrategy(
                    Map.of(RoleBasedAuthorizationStrategy.GLOBAL, new RoleMap(roleMap)));
        }
        swarmClientRule.j.get().jenkins.setAuthorizationStrategy(authorizationStrategy);

        // Configure CSRF.
        if (csrf) {
            swarmClientRule.j.get().jenkins.setCrumbIssuer(new DefaultCrumbIssuer(false));
        }
    }
}
