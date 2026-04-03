package hudson.plugins.swarm;

import hudson.model.Node;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodePropertyDescriptor;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * {@link NodeProperty} that sets additional environment variables.
 *
 * @since 1.286
 */
public class KeepSwarmClientNodeProperty extends NodeProperty<Node> {

    @DataBoundConstructor
    public KeepSwarmClientNodeProperty() {}

    @Override
    public NodePropertyDescriptor getDescriptor() {
        return new NodePropertyDescriptor(KeepSwarmClientNodeProperty.class) {};
    }
}
