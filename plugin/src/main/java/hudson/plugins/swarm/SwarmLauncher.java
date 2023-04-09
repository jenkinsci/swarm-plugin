package hudson.plugins.swarm;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Functions;
import hudson.model.Descriptor;
import hudson.model.Slave;
import hudson.model.TaskListener;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.SlaveComputer;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.slaves.DefaultJnlpSlaveReceiver;
import org.jenkinsci.remoting.engine.JnlpConnectionState;

/**
 * {@link ComputerLauncher} for Swarm agents. We extend {@link JNLPLauncher} for compatibility with
 * {@link DefaultJnlpSlaveReceiver#afterProperties(JnlpConnectionState)}.
 */
public class SwarmLauncher extends JNLPLauncher {

    private static final Logger LOGGER = Logger.getLogger(SwarmLauncher.class.getName());

    public SwarmLauncher() {
        super(false);
    }

    @Override
    public void afterDisconnect(SlaveComputer computer, TaskListener listener) {
        super.afterDisconnect(computer, listener);

        Slave node = computer.getNode();
        if (node != null) {
            String nodeName = node.getNodeName();
            try {
                // Don't remove the node object if we've disconnected, if the node doesn't want to
                // be removed
                KeepSwarmClientNodeProperty keepClientProp = node.getNodeProperty(KeepSwarmClientNodeProperty.class);

                // We use the existance of the node property on the node itself as a boolean check
                if (keepClientProp == null) {
                    LOGGER.log(Level.INFO, "Removing Swarm Node for computer [{0}]", nodeName);
                    Jenkins.get().removeNode(node);
                } else {
                    listener.getLogger().printf("Skipping removal of Node for computer [%1$s]", nodeName);
                    LOGGER.log(Level.INFO, "Skipping removal of Node for computer [{0}]", nodeName);
                }
            } catch (IOException e) {
                Functions.printStackTrace(e, listener.error("Failed to remove node [%1$s]", nodeName));
                LOGGER.log(
                        Level.WARNING,
                        String.format(
                                "Failed to remove node [%1$s] %n%2$s",
                                nodeName, Functions.printThrowable(e).trim()));
            }
        } else {
            listener.getLogger().printf("Node for computer [%1$s] appears to have been removed already%n", computer);
        }
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<ComputerLauncher> {

        @NonNull
        @Override
        public String getDisplayName() {
            return "Launch Swarm agent";
        }
    }
}
