package hudson.plugins.swarm.test;

import com.google.common.collect.ImmutableSet;
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

import jenkins.model.Jenkins;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

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

    public GlobalSecurityConfigurationBuilder setAuthorizationStrategy(
            AuthorizationStrategy authorizationStrategy) {
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
            GlobalMatrixAuthorizationStrategy strategy =
                    (GlobalMatrixAuthorizationStrategy) authorizationStrategy;
            // Not needed for testing, but is helpful when debugging test failures.
            strategy.add(Jenkins.ADMINISTER, adminUsername);

            // Needed for the [optional] Jenkins version check as well as CSRF.
            strategy.add(Jenkins.READ, "authenticated");

            // Needed to create and connect the agent.
            strategy.add(Computer.CREATE, swarmUsername);
            strategy.add(Computer.CONNECT, swarmUsername);

            /*
             * The following is necessary because
             * AuthorizationMatrixNodeProperty.NodeListenerImpl#onCreated only applies to
             * ProjectMatrixAuthorizationStrategy.
             */
            if (!(authorizationStrategy instanceof ProjectMatrixAuthorizationStrategy)) {
                // Needed to add/remove labels after the fact.
                strategy.add(Computer.CONFIGURE, swarmUsername);
            }
        } else if (authorizationStrategy instanceof RoleBasedAuthorizationStrategy) {
            Role admin =
                    new Role(
                            "admin",
                            ".*",
                            Collections.singleton(Jenkins.ADMINISTER.getId()),
                            "Jenkins administrators");
            Role readOnly =
                    new Role(
                            "readonly",
                            ".*",
                            Collections.singleton(Jenkins.READ.getId()),
                            "Read-only users");
            Role swarm =
                    new Role(
                            "swarm",
                            ".*",
                            ImmutableSet.of(
                                    Computer.CREATE.getId(),
                                    Computer.CONNECT.getId(),
                                    Computer.CONFIGURE.getId()),
                            "Swarm users");

            SortedMap<Role, Set<String>> roleMap = new TreeMap<>();
            roleMap.put(admin, Collections.singleton(adminUsername));
            roleMap.put(readOnly, Collections.singleton("authenticated"));
            roleMap.put(swarm, Collections.singleton(swarmUsername));

            authorizationStrategy =
                    new RoleBasedAuthorizationStrategy(
                            Collections.singletonMap(
                                    RoleBasedAuthorizationStrategy.GLOBAL, new RoleMap(roleMap)));
        }
        swarmClientRule.j.get().jenkins.setAuthorizationStrategy(authorizationStrategy);

        // Configure CSRF.
        if (csrf) {
            swarmClientRule.j.get().jenkins.setCrumbIssuer(new DefaultCrumbIssuer(false));
        }
    }
}
