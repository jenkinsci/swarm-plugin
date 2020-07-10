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

import jenkins.model.Jenkins;
import jenkins.slaves.DefaultJnlpSlaveReceiver;

import org.jenkinsci.remoting.engine.JnlpConnectionState;

import java.io.IOException;

/**
 * {@link ComputerLauncher} for Swarm agents. We extend {@link JNLPLauncher} for compatibility with
 * {@link DefaultJnlpSlaveReceiver#afterProperties(JnlpConnectionState)}.
 */
public class SwarmLauncher extends JNLPLauncher {

    public SwarmLauncher() {
        super(false);
    }

    @Override
    public void afterDisconnect(SlaveComputer computer, TaskListener listener) {
        super.afterDisconnect(computer, listener);

        Slave node = computer.getNode();
        if (node != null) {
            try {
                Jenkins.get().removeNode(node);
            } catch (IOException e) {
                Functions.printStackTrace(
                        e, listener.error("Failed to remove node \"%s\".", node.getNodeName()));
            }
        } else {
            listener.getLogger()
                    .printf(
                            "Node for computer \"%s\" appears to have been removed already.%n",
                            computer);
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
