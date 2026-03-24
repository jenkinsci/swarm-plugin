package hudson.plugins.swarm;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.Descriptor.FormException;
import hudson.model.Node;
import hudson.model.Slave;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.EphemeralNode;
import hudson.slaves.NodeProperty;
import hudson.slaves.RetentionStrategy;
import java.io.IOException;
import java.util.List;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * {@link Slave} created by ad-hoc local systems.
 *
 * <p>This acts like an inbound agent, except when the client disconnects, the agent will be
 * deleted.
 *
 * @author Kohsuke Kawaguchi
 */
public class SwarmSlave extends Slave implements EphemeralNode {

    private static final long serialVersionUID = -1527777529814020243L;

    @DataBoundConstructor
    public SwarmSlave(
            String name,
            String nodeDescription,
            String remoteFS,
            int numExecutors,
            Mode mode,
            String labelString,
            ComputerLauncher launcher,
            RetentionStrategy<?> retentionStrategy,
            List<? extends NodeProperty<?>> nodeProperties)
            throws FormException, IOException {
        super(name, remoteFS, launcher);
        setNodeDescription(nodeDescription);
        setMode(mode);
        setLabelString(labelString);
        setRetentionStrategy(retentionStrategy);
        setNodeProperties(nodeProperties);
        setNumExecutors(numExecutors);
    }

    @Override
    public Node asNode() {
        return this;
    }

    @Extension
    public static final class DescriptorImpl extends SlaveDescriptor {

        @Override
        @NonNull
        public String getDisplayName() {
            return "Swarm agent";
        }

        /** We only create this kind of nodes programmatically. */
        @Override
        public boolean isInstantiable() {
            return false;
        }
    }

    @Extension
    public static final class DefaultSwarmSlaveFactory implements SwarmSlaveFactory {
        @Override
        public Slave createSlave(
                String name,
                String nodeDescription,
                String remoteFS,
                int numExecutors,
                Node.Mode mode,
                String labelString,
                List<? extends NodeProperty<?>> nodeProperties)
                throws IOException, Descriptor.FormException {
            return new SwarmSlave(
                    name,
                    nodeDescription,
                    remoteFS,
                    numExecutors,
                    mode,
                    labelString,
                    SELF_CLEANUP_LAUNCHER,
                    RetentionStrategy.NOOP,
                    nodeProperties);
        }
    }

    /** {@link ComputerLauncher} that destroys itself upon a connection termination. */
    private static final ComputerLauncher SELF_CLEANUP_LAUNCHER = new SwarmLauncher();
}
