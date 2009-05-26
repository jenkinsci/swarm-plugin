package hudson.plugins.swarm;

import hudson.Extension;
import hudson.model.Descriptor.FormException;
import hudson.model.Hudson;
import hudson.model.Node;
import hudson.model.Slave;
import hudson.model.TaskListener;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.EphemeralNode;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.NodeProperty;
import hudson.slaves.RetentionStrategy;
import hudson.slaves.SlaveComputer;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.IOException;
import java.util.Collections;

/**
 * {@link Slave} created by ad-hoc local systems.
 *
 * <p>
 * This acts like a JNLP slave, except when the client disconnects, the slave will be deleted.
 *
 * @author Kohsuke Kawaguchi
 */
public class SwarmSlave extends Slave implements EphemeralNode {
    @DataBoundConstructor
    public SwarmSlave(String name, String nodeDescription, String remoteFS, String numExecutors, String label) throws IOException, FormException {
        super(name, nodeDescription, remoteFS, numExecutors, Mode.NORMAL, label,
                SELF_CLEANUP_LAUNCHER, RetentionStrategy.NOOP, Collections.<NodeProperty<?>>emptyList());
    }

    public Node asNode() {
        return this;
    }

    @Extension
    public static final class DescriptorImpl extends SlaveDescriptor {
        public String getDisplayName() {
            return "Swarm Slave";
        }

        /**
         * We only create this kind of nodes programatically.
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
        public void afterDisconnect(SlaveComputer computer, TaskListener listener) {
            try {
                Hudson.getInstance().removeNode(computer.getNode());
            } catch (IOException e) {
                e.printStackTrace(listener.error(e.getMessage()));
            }
        }
    };
}
