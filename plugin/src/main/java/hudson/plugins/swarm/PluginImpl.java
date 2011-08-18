package hudson.plugins.swarm;

import hudson.Plugin;
import hudson.Util;
import hudson.model.Descriptor.FormException;
import hudson.model.Node;
import hudson.slaves.SlaveComputer;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import static javax.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import java.io.IOException;

/**
 * Exposes an entry point to add a new swarm slave.
 *
 * @author Kohsuke Kawaguchi
 */
public class PluginImpl extends Plugin {

    /**
     * Adds a new swarm slave.
     */
    public void doCreateSlave(StaplerRequest req, StaplerResponse rsp, @QueryParameter String name, @QueryParameter String description, @QueryParameter int executors,
            @QueryParameter String remoteFsRoot, @QueryParameter String labels, @QueryParameter String secret) throws IOException, FormException {

        // only allow nearby nodes to connect
        if (!UDPFragmentImpl.all().get(UDPFragmentImpl.class).secret.toString().equals(secret)) {
            rsp.setStatus(SC_FORBIDDEN);
            return;
        }

        try {
            final Jenkins jenkins = Jenkins.getInstance();

            jenkins.checkPermission(SlaveComputer.CREATE);
            
            // try to make the name unique. Swarm clients are often repliated VMs, and they may have the same name.
            if (jenkins.getNode(name) != null) {
                name = name + '-' + req.getRemoteAddr();
            }

            SwarmSlave slave = new SwarmSlave(name, "Swarm slave from " + req.getRemoteHost() + " : " + description,
                    remoteFsRoot, String.valueOf(executors), "swarm " + Util.fixNull(labels));

            // if this still results in a dupliate, so be it
            synchronized (jenkins) {
                Node n = jenkins.getNode(name);
                if (n != null) {
                    jenkins.removeNode(n);
                }
                jenkins.addNode(slave);
            }
        } catch (FormException e) {
            e.printStackTrace();
        }
    }
}
