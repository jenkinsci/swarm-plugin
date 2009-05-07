package hudson.plugins.swarm;

import hudson.Plugin;
import hudson.Util;
import hudson.model.Descriptor.FormException;
import hudson.model.Hudson;
import hudson.model.Node;
import hudson.security.ACL;
import org.acegisecurity.context.SecurityContextHolder;
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
     * Adds a new slave.
     */
    public void doCreateSlave(StaplerRequest req, StaplerResponse rsp, @QueryParameter String name, @QueryParameter String description, @QueryParameter int executors,
                              @QueryParameter String remoteFsRoot, @QueryParameter String labels, @QueryParameter String secret) throws IOException, FormException {

        // only allow nearby nodes to connect
        if(!UDPFragmentImpl.all().get(UDPFragmentImpl.class).secret.toString().equals(secret)) {
            rsp.setStatus(SC_FORBIDDEN);
            return;
        }

        // this is used by swarm clients that otherwise have no access to the system,
        // so bypass the regular security check, and only rely on secret.
        SecurityContextHolder.getContext().setAuthentication(ACL.SYSTEM);
        try {
            SwarmSlave slave = new SwarmSlave(name, "Swam slave from "+req.getRemoteHost()+" : "+description,
                    remoteFsRoot, String.valueOf(executors), "swarm "+Util.fixNull(labels));

            // overwrite the node, even if it already exists
            final Hudson hudson = Hudson.getInstance();
            synchronized (hudson) {
                Node n = hudson.getNode(name);
                if(n!=null) hudson.removeNode(n);
                hudson.addNode(slave);
            }
        } catch (FormException e) {
            e.printStackTrace();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
