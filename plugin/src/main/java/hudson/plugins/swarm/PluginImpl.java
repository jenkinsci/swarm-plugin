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
     * Adds a new swarm slave.
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
            final Hudson hudson = Hudson.getInstance();

            // try to make the name unique. Swarm clients are often repliated VMs, and they may have the same name.
            if(hudson.getNode(name)!=null)
                name = name+'-'+req.getRemoteAddr();

            SwarmSlave slave = new SwarmSlave(name, "Swam slave from "+req.getRemoteHost()+" : "+description,
                    remoteFsRoot, String.valueOf(executors), "swarm "+Util.fixNull(labels));

            // if this still results in a dupliate, so be it
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
