package hudson.plugins.swarm;

import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.Descriptor.FormException;
import hudson.model.Node;
import hudson.model.Slave;
import hudson.model.TaskListener;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.EphemeralNode;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.NodeProperty;
import hudson.slaves.RetentionStrategy;
import hudson.slaves.SlaveComputer;
import java.io.IOException;
import java.util.List;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * {@link Slave} created by ad-hoc local systems.
 * <p>
 * This acts like a JNLP slave, except when the client disconnects, the slave will be deleted.
 *
 * @author Kohsuke Kawaguchi
 */
public class SwarmSlave extends Slave implements EphemeralNode {

    private static final long serialVersionUID = -1527777529814020243L;

    public SwarmSlave(String name, String nodeDescription, String remoteFS, String numExecutors, Mode mode,
                      String label, List<? extends NodeProperty<?>> nodeProperties) throws IOException, FormException {
        super(name, nodeDescription, remoteFS, numExecutors, mode, label,
                SELF_CLEANUP_LAUNCHER, RetentionStrategy.NOOP, nodeProperties);
    }

    @DataBoundConstructor
    public SwarmSlave(String name, String nodeDescription, String remoteFS, String numExecutors, Mode mode,
                      String labelString, ComputerLauncher launcher, RetentionStrategy<?> retentionStrategy,
                      List<? extends NodeProperty<?>> nodeProperties) throws FormException, IOException {
        super(name, nodeDescription, remoteFS, numExecutors, mode, labelString, launcher, retentionStrategy, nodeProperties);
    }

    @Override
    public Node asNode() {
        return this;
    }

    @Extension
    public static final class DescriptorImpl extends SlaveDescriptor {

        @Override
        public String getDisplayName() {
            return "Swarm Slave";
        }

        /**
         * We only create this kind of nodes programmatically.
         */
        @Override
        public boolean isInstantiable() {
            return false;
        }
    }

    /**
     * {@link ComputerLauncher} that destroys itself upon a connection termination.
     */
    private static final JNLPLauncher SELF_CLEANUP_LAUNCHER = new JNLPLauncher() {

        @Override
        public Descriptor<ComputerLauncher> getDescriptor() {
            return new Descriptor<ComputerLauncher>() {
                @Override
                public String getDisplayName() {
                    return "Launch swarm slaves";
                }
            };
        }

        @Override
        public void afterDisconnect(SlaveComputer computer, TaskListener listener) {
            final Slave node = computer.getNode();
            if (node != null) {
                try {
                    Jenkins.getInstance().removeNode(node);
                } catch (IOException e) {
                    e.printStackTrace(listener.error(e.getMessage()));
                }
            } else {
                listener.getLogger().printf("Could not remove node for %s as it appears to have been removed already%n",
                        computer);
            }
        }
    };
}
