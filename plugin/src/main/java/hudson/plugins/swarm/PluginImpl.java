package hudson.plugins.swarm;

import com.google.common.collect.Lists;
import hudson.Plugin;
import hudson.Util;
import hudson.model.Computer;
import hudson.model.Descriptor.FormException;
import hudson.model.Node;
import hudson.slaves.SlaveComputer;
import hudson.tools.ToolDescriptor;
import hudson.tools.ToolInstallation;
import hudson.tools.ToolLocationNodeProperty;
import hudson.tools.ToolLocationNodeProperty.ToolLocation;
import jenkins.model.Jenkins;
import org.apache.commons.lang.ArrayUtils;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import javax.servlet.ServletOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.Properties;

import static javax.servlet.http.HttpServletResponse.SC_CONFLICT;
import static javax.servlet.http.HttpServletResponse.SC_FORBIDDEN;

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
            @QueryParameter String remoteFsRoot, @QueryParameter String labels, @QueryParameter String secret, @QueryParameter Node.Mode mode, 
            @QueryParameter(fixEmpty = true) String hash) throws IOException {

        if (!getSwarmSecret().equals(secret)) {
            rsp.setStatus(SC_FORBIDDEN);
            return;
        }

        try {
            final Jenkins jenkins = Jenkins.getInstance();

            jenkins.checkPermission(SlaveComputer.CREATE);
            
            String[] toolLocations = req.getParameterValues("toolLocation");
            List<ToolLocationNodeProperty> nodeProperties = Lists.newArrayList();
            if (!ArrayUtils.isEmpty(toolLocations)) {
            	List<ToolLocation> parsedToolLocations = parseToolLocations(toolLocations);
				nodeProperties = Lists.newArrayList(new ToolLocationNodeProperty(parsedToolLocations));
            }
    		
            if (hash == null && jenkins.getNode(name) != null) {
                // this is a legacy client, they won't be able to pick up the new name, so throw them away
                // perhaps they can find another master to connect to
                rsp.setStatus(SC_CONFLICT);
                rsp.setContentType("text/plain; UTF-8");
                rsp.getWriter().printf(
                        "A slave called '%s' already exists and legacy clients do not support name disambiguation%n",
                        name);
                return;
            }
            if (hash != null) {
                // try to make the name unique. Swarm clients are often replicated VMs, and they may have the same name.
                name = name + '-' + hash;
            }
            // check for existing connections
            {
                Node n = jenkins.getNode(name);
                if (n != null) {
                    Computer c = n.toComputer();
                    if (c != null && c.isOnline()) {
                        // this is an existing connection, we'll only cause issues if we trample over an online connection
                        
                        rsp.setStatus(SC_CONFLICT);
                        rsp.setContentType("text/plain; UTF-8");
                        rsp.getWriter().printf("A slave called '%s' is already created and on-line%n", name);
                        return;
                    }
                }
            }

			SwarmSlave slave = new SwarmSlave(name, "Swarm slave from " + req.getRemoteHost() + " : " + description,
                    remoteFsRoot, String.valueOf(executors), mode, "swarm " + Util.fixNull(labels), nodeProperties);

            // if this still results in a duplicate, so be it
            synchronized (jenkins) {
                Node n = jenkins.getNode(name);
                if (n != null) {
                    jenkins.removeNode(n);
                }
                jenkins.addNode(slave);
            }
            rsp.setContentType("text/plain; charset=iso-8859-1");
            Properties props = new Properties();
            props.put("name", name);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            props.store(bos, "");
            byte[] response = bos.toByteArray();
            rsp.setContentLength(response.length);
            ServletOutputStream outputStream = rsp.getOutputStream();
            outputStream.write(response);
            outputStream.flush();
        } catch (FormException e) {
            e.printStackTrace();
        }
    }

	private List<ToolLocation> parseToolLocations(String[] toolLocations) {
		List<ToolLocationNodeProperty.ToolLocation> result = Lists.newArrayList();
		
		for (String toolLocKeyValue : toolLocations) {
			boolean found = false;
			// Limit the split on only the first occurence
			// of ':', so that the tool location path can
			// contain ':' characters.
			String[] toolLoc = toolLocKeyValue.split(":", 2);
			
			for (ToolDescriptor<?> desc : ToolInstallation.all()) {
				for (ToolInstallation inst : desc.getInstallations()) {
					if (inst.getName().equals(toolLoc[0])) {
						found = true;
                        
						String location = toolLoc[1];
		
						ToolLocationNodeProperty.ToolLocation toolLocation = new ToolLocationNodeProperty.ToolLocation(desc, inst.getName(), location);
						result.add(toolLocation);
					}
				}
			}
			
			// don't fail silently, inform the user what tool is missing
			if (!found) {
				throw new RuntimeException("No tool '" + toolLoc[0] + "' is defined on Jenkins.");
			}
		}
		
		return result;
	}

    static String getSwarmSecret() {
        return UDPFragmentImpl.all().get(UDPFragmentImpl.class).secret.toString();
    }

    public void doSlaveInfo(StaplerRequest req, StaplerResponse rsp) throws IOException {
        final Jenkins jenkins = Jenkins.getInstance();
        jenkins.checkPermission(SlaveComputer.CREATE);

        rsp.setContentType("text/xml");
        Writer w = rsp.getCompressedWriter(req);
        w.write("<slaveInfo><swarmSecret>" + getSwarmSecret() + "</swarmSecret></slaveInfo>");
        w.close();
    }
}
