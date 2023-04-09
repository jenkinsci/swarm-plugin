package hudson.plugins.swarm;

import hudson.Extension;
import hudson.model.Node;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodePropertyDescriptor;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * {@link NodeProperty} that sets additional environment variables.
 *
 * @since 1.286
 */
public class KeepSwarmClientNodeProperty extends NodeProperty<Node> {

    @DataBoundConstructor
    public KeepSwarmClientNodeProperty() {}

    @Extension
    @Symbol("keepSwarmClient")
    public static class DescriptorImpl extends NodePropertyDescriptor {

        @Override
        public String getDisplayName() {
            return "Keep Swarm client node after agent disconnect";
        }
    }
}
