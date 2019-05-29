package hudson.plugins.swarm;

import hudson.UDPBroadcastFragment;
import hudson.Extension;

import java.net.SocketAddress;
import java.util.UUID;

/**
 * Adds a secret pass-phrase to UDP broadcast, to allow nearby nodes to register themselves as slaves.
 *
 * @author Kohsuke Kawaguchi
 */
@Extension
public class UDPFragmentImpl extends UDPBroadcastFragment {

    /**
     * This is the pass phrase that allows swarm slaves to create a new slave object on Jenkins.
     */
    public final UUID secret = UUID.randomUUID();

    public void buildFragment(StringBuilder buf, SocketAddress sender) {
        buf.append("<swarm>").append(secret).append("</swarm>");
    }
}
