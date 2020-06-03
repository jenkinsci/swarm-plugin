package hudson.plugins.swarm;

import static javax.servlet.http.HttpServletResponse.SC_CONFLICT;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Plugin;
import hudson.Util;
import hudson.model.Computer;
import hudson.model.Descriptor.FormException;
import hudson.model.Node;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import hudson.slaves.NodeProperty;
import hudson.slaves.SlaveComputer;
import hudson.tools.ToolDescriptor;
import hudson.tools.ToolInstallation;
import hudson.tools.ToolLocationNodeProperty;
import hudson.tools.ToolLocationNodeProperty.ToolLocation;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import javax.servlet.ServletOutputStream;
import jenkins.model.Jenkins;
import org.apache.commons.lang.ArrayUtils;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.verb.POST;

/**
 * Exposes an entry point to add a new swarm slave.
 *
 * @author Kohsuke Kawaguchi
 */
public class PluginImpl extends Plugin {

    private Node getNodeByName(String name, StaplerResponse rsp) throws IOException {
        Jenkins jenkins = Jenkins.get();

        try {
            Node n = jenkins.getNode(name);

            if (n == null) {
                rsp.setStatus(SC_NOT_FOUND);
                rsp.setContentType("text/plain; UTF-8");
                rsp.getWriter().printf("A slave called '%s' does not exist.%n", name);
                return null;
            }
            return n;
        } catch (NullPointerException ignored) {}

        return null;
    }

    /**
     * Gets list of labels for slave
     */
    public void doGetSlaveLabels(StaplerRequest req, StaplerResponse rsp, @QueryParameter String name)
                                 throws IOException {
        Node nn = getNodeByName(name, rsp);
        if (nn == null) {
            return;
        }

        normalResponse(req, rsp, nn.getLabelString());
    }

    @SuppressFBWarnings(
            value = "RCN_REDUNDANT_NULLCHECK_WOULD_HAVE_BEEN_A_NPE",
            justification = "False positive for try-with-resources in Java 11")
    private void normalResponse(StaplerRequest req, StaplerResponse rsp, String sLabelList) throws IOException {
        rsp.setContentType("text/xml");

        try (Writer w = rsp.getCompressedWriter(req)) {
            w.write("<labelResponse><labels>" + sLabelList + "</labels></labelResponse>");
        }
    }

    /**
     * Adds labels to a slave.
     */
    @POST
    public void doAddSlaveLabels(StaplerRequest req, StaplerResponse rsp, @QueryParameter String name,
                            @QueryParameter String labels)  throws IOException{
        Node nn = getNodeByName(name, rsp);
        if (nn == null) {
            return;
        }

        nn.checkPermission(Computer.CONFIGURE);

        String sCurrentLabels = nn.getLabelString();
        List<String> lCurrentLabels = Arrays.asList(sCurrentLabels.split("\\s+"));
        Set<String> hs = new HashSet<>(lCurrentLabels);
        List<String> lNewLabels = Arrays.asList(labels.split("\\s+"));
        hs.addAll(lNewLabels);
        nn.setLabelString(setToString(hs));
        nn.getAssignedLabels();

        normalResponse(req, rsp, nn.getLabelString());
    }

    private static String setToString(Set<String> labels) {
        return String.join(" ", labels);
    }

    /**
     * Remove labels from a slave
     */
    @POST
    public void doRemoveSlaveLabels(StaplerRequest req, StaplerResponse rsp, @QueryParameter String name,
                            @QueryParameter String labels) throws IOException {
        Node nn = getNodeByName(name, rsp);
        if (nn == null) {
            return;
        }

        nn.checkPermission(Computer.CONFIGURE);

        String sCurrentLabels = nn.getLabelString();
        List<String> lCurrentLabels = Arrays.asList(sCurrentLabels.split("\\s+"));
        Set<String> hs = new HashSet<>(lCurrentLabels);
        List<String> lBadLabels = Arrays.asList(labels.split("\\s+"));
        hs.removeAll(lBadLabels);
        nn.setLabelString(setToString(hs));
        nn.getAssignedLabels();
        normalResponse(req, rsp, nn.getLabelString());
    }

    /**
     * Adds a new swarm slave.
     */
    @POST
    public void doCreateSlave(StaplerRequest req, StaplerResponse rsp, @QueryParameter String name,
                              @QueryParameter String description, @QueryParameter int executors,
                              @QueryParameter String remoteFsRoot, @QueryParameter String labels,
                              @QueryParameter Node.Mode mode,
                              @QueryParameter(fixEmpty = true) String hash,
                              @QueryParameter boolean deleteExistingClients) throws IOException {
        try {
            Jenkins jenkins = Jenkins.get();

            jenkins.checkPermission(SlaveComputer.CREATE);

            List<NodeProperty<Node>> nodeProperties = new ArrayList<>();

            String[] toolLocations = req.getParameterValues("toolLocation");
            if (!ArrayUtils.isEmpty(toolLocations)) {
                List<ToolLocation> parsedToolLocations = parseToolLocations(toolLocations);
                nodeProperties.add(new ToolLocationNodeProperty(parsedToolLocations));
            }

            String[] environmentVariables = req.getParameterValues("environmentVariable");
            if (!ArrayUtils.isEmpty(environmentVariables)) {
                List<EnvironmentVariablesNodeProperty.Entry> parsedEnvironmentVariables =
                        parseEnvironmentVariables(environmentVariables);
                nodeProperties.add(
                        new EnvironmentVariablesNodeProperty(parsedEnvironmentVariables));
            }

            if (hash == null && jenkins.getNode(name) != null && !deleteExistingClients) {
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
                if (n != null && !deleteExistingClients) {
                    Computer c = n.toComputer();
                    if (c != null && c.isOnline()) {
                        // this is an existing connection, we'll only cause issues
                        // if we trample over an online connection
                        rsp.setStatus(SC_CONFLICT);
                        rsp.setContentType("text/plain; UTF-8");
                        rsp.getWriter().printf("A slave called '%s' is already created and on-line%n", name);
                        return;
                    }
                }
            }

            SwarmSlave slave =
                    new SwarmSlave(
                            name,
                            "Swarm slave from "
                                    + req.getRemoteHost()
                                    + ((description == null || description.isEmpty())
                                            ? ""
                                            : (": " + description)),
                            remoteFsRoot,
                            String.valueOf(executors),
                            mode,
                            "swarm " + Util.fixNull(labels),
                            nodeProperties);

            jenkins.addNode(slave);
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

    private static List<ToolLocation> parseToolLocations(String[] toolLocations) {
        List<ToolLocationNodeProperty.ToolLocation> result = new ArrayList<>();

        for (String toolLocKeyValue : toolLocations) {
            boolean found = false;
            // Limit the split on only the first occurrence
            // of ':', so that the tool location path can
            // contain ':' characters.
            String[] toolLoc = toolLocKeyValue.split(":", 2);

            for (ToolDescriptor<?> desc : ToolInstallation.all()) {
                for (ToolInstallation inst : desc.getInstallations()) {
                    if (inst.getName().equals(toolLoc[0])) {
                        found = true;

                        String location = toolLoc[1];

                        ToolLocationNodeProperty.ToolLocation toolLocation = new ToolLocationNodeProperty
                                .ToolLocation(desc, inst.getName(), location);
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

    private static List<EnvironmentVariablesNodeProperty.Entry> parseEnvironmentVariables(
            String[] environmentVariables) {
        List<EnvironmentVariablesNodeProperty.Entry> result = new ArrayList<>();

        for (String environmentVariable : environmentVariables) {
            // Limit the split on only the first occurrence of ':' so that the value can contain ':'
            // characters.
            String[] keyValue = environmentVariable.split(":", 2);
            EnvironmentVariablesNodeProperty.Entry var =
                    new EnvironmentVariablesNodeProperty.Entry(keyValue[0], keyValue[1]);
            result.add(var);
        }

        return result;
    }

    /**
     * This merely exists to support older versions of the Swarm client that expect to be able to
     * retrieve a UUID-based secret. Security is now handled through CSRF and permission checks
     * rather than a UUID-based secret. Newer clients do not call this method or pass in a
     * UUID-based secret, and newer versions of the server do not check for a UUID-based secret.
     * When support for older clients that call this endpoint is removed, this endpoint can be
     * deleted.
     */
    @Deprecated
    @SuppressFBWarnings(
            value = "RCN_REDUNDANT_NULLCHECK_WOULD_HAVE_BEEN_A_NPE",
            justification = "False positive for try-with-resources in Java 11")
    public void doSlaveInfo(StaplerRequest req, StaplerResponse rsp) throws IOException {
        Jenkins jenkins = Jenkins.get();
        jenkins.checkPermission(SlaveComputer.CREATE);

        rsp.setContentType("text/xml");
        try (Writer w = rsp.getCompressedWriter(req)) {
            w.write("<slaveInfo><swarmSecret>" + UUID.randomUUID().toString() + "</swarmSecret></slaveInfo>");
        }
    }
}
