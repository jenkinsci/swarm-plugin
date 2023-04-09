package hudson.plugins.swarm;

import hudson.Functions;
import hudson.Plugin;
import hudson.Util;
import hudson.model.Computer;
import hudson.model.Descriptor.FormException;
import hudson.model.Node;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import hudson.slaves.NodeProperty;
import hudson.tools.ToolDescriptor;
import hudson.tools.ToolInstallation;
import hudson.tools.ToolLocationNodeProperty;
import hudson.tools.ToolLocationNodeProperty.ToolLocation;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import javax.servlet.http.HttpServletResponse;
import jenkins.model.Jenkins;
import org.apache.commons.lang.ArrayUtils;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.verb.POST;

/**
 * Exposes an entry point to add a new Swarm agent.
 *
 * @author Kohsuke Kawaguchi
 */
public class PluginImpl extends Plugin {

    private Node getNodeByName(String name, StaplerResponse rsp) throws IOException {
        Jenkins jenkins = Jenkins.get();
        Node node = jenkins.getNode(name);

        if (node == null) {
            rsp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            rsp.setContentType("text/plain; UTF-8");
            rsp.getWriter().printf("Agent \"%s\" does not exist.%n", name);
            return null;
        }

        return node;
    }

    /** Get the list of labels for an agent. */
    @SuppressWarnings({"lgtm[jenkins/csrf]", "lgtm[jenkins/no-permission-check]"})
    public void doGetSlaveLabels(StaplerRequest req, StaplerResponse rsp, @QueryParameter String name)
            throws IOException {
        Node node = getNodeByName(name, rsp);
        if (node == null) {
            return;
        }

        normalResponse(req, rsp, node.getLabelString());
    }

    private void normalResponse(StaplerRequest req, StaplerResponse rsp, String sLabelList) throws IOException {
        rsp.setContentType("text/xml");

        try (Writer writer = rsp.getCompressedWriter(req)) {
            writer.write("<labelResponse><labels>" + sLabelList + "</labels></labelResponse>");
        }
    }

    /** Add labels to an agent. */
    @POST
    public void doAddSlaveLabels(
            StaplerRequest req, StaplerResponse rsp, @QueryParameter String name, @QueryParameter String labels)
            throws IOException {
        Node node = getNodeByName(name, rsp);
        if (node == null) {
            return;
        }

        node.checkPermission(Computer.CONFIGURE);

        LinkedHashSet<String> currentLabels = stringToSet(node.getLabelString());
        LinkedHashSet<String> labelsToAdd = stringToSet(labels);
        currentLabels.addAll(labelsToAdd);
        node.setLabelString(setToString(currentLabels));

        normalResponse(req, rsp, node.getLabelString());
    }

    private static String setToString(Set<String> labels) {
        return String.join(" ", labels);
    }

    private static LinkedHashSet<String> stringToSet(String labels) {
        return new LinkedHashSet<>(List.of(labels.split("\\s+")));
    }

    /** Remove labels from an agent. */
    @POST
    public void doRemoveSlaveLabels(
            StaplerRequest req, StaplerResponse rsp, @QueryParameter String name, @QueryParameter String labels)
            throws IOException {
        Node node = getNodeByName(name, rsp);
        if (node == null) {
            return;
        }

        node.checkPermission(Computer.CONFIGURE);

        LinkedHashSet<String> currentLabels = stringToSet(node.getLabelString());
        LinkedHashSet<String> labelsToRemove = stringToSet(labels);
        currentLabels.removeAll(labelsToRemove);
        node.setLabelString(setToString(currentLabels));

        normalResponse(req, rsp, node.getLabelString());
    }

    /** Add a new Swarm agent. */
    @POST
    public void doCreateSlave(
            StaplerRequest req,
            StaplerResponse rsp,
            @QueryParameter String name,
            @QueryParameter(fixEmpty = true) String description,
            @QueryParameter int executors,
            @QueryParameter String remoteFsRoot,
            @QueryParameter String labels,
            @QueryParameter Node.Mode mode,
            @QueryParameter(fixEmpty = true) String hash,
            @QueryParameter boolean deleteExistingClients,
            @QueryParameter boolean keepDisconnectedClients)
            throws IOException {
        Jenkins jenkins = Jenkins.get();

        jenkins.checkPermission(Computer.CREATE);

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
            nodeProperties.add(new EnvironmentVariablesNodeProperty(parsedEnvironmentVariables));
        }

        // We use the existance of the node property itself as the boolean flag
        if (keepDisconnectedClients) {
            nodeProperties.add(new KeepSwarmClientNodeProperty());
        }

        if (hash == null && jenkins.getNode(name) != null && !deleteExistingClients) {
            /*
             * This is a legacy client. They won't be able to pick up the new name, so throw them
             * away. Perhaps they can find another controller to connect to.
             */
            rsp.setStatus(HttpServletResponse.SC_CONFLICT);
            rsp.setContentType("text/plain; UTF-8");
            rsp.getWriter().printf("Agent \"%s\" already exists.%n", name);
            return;
        }

        if (hash != null) {
            /*
             * Try to make the name unique. Swarm clients are often replicated VMs, and they may
             * have the same name.
             */
            name = name + '-' + hash;
        }

        // Check for existing connections.
        Node node = jenkins.getNode(name);
        if (node != null && !deleteExistingClients) {
            Computer computer = node.toComputer();
            if (computer != null && computer.isOnline()) {
                /*
                 * This is an existing connection. We'll only cause issues if we trample over an
                 * online connection.
                 */
                rsp.setStatus(HttpServletResponse.SC_CONFLICT);
                rsp.setContentType("text/plain; UTF-8");
                rsp.getWriter().printf("Agent \"%s\" is already created and on-line.%n", name);
                return;
            }
        }

        try {
            String nodeDescription = "Swarm agent from " + req.getRemoteHost();
            if (description != null) {
                nodeDescription += ": " + description;
            }
            SwarmSlave agent = new SwarmSlave(
                    name,
                    nodeDescription,
                    remoteFsRoot,
                    String.valueOf(executors),
                    mode,
                    "swarm " + Util.fixNull(labels),
                    nodeProperties);
            jenkins.addNode(agent);

            rsp.setContentType("text/plain; charset=iso-8859-1");
            try (OutputStream outputStream = rsp.getCompressedOutputStream(req)) {
                Properties props = new Properties();
                props.put("name", name);
                props.store(outputStream, "");
            }
        } catch (FormException e) {
            Functions.printStackTrace(e, System.err);
        }
    }

    private static List<ToolLocation> parseToolLocations(String[] toolLocations) {
        List<ToolLocationNodeProperty.ToolLocation> result = new ArrayList<>();

        for (String toolLocKeyValue : toolLocations) {
            boolean found = false;
            /*
             * Limit the split on only the first occurrence of ':' so that the tool location path
             * can contain ':' characters.
             */
            String[] toolLoc = toolLocKeyValue.split(":", 2);

            for (ToolDescriptor<?> desc : ToolInstallation.all()) {
                for (ToolInstallation inst : desc.getInstallations()) {
                    if (inst.getName().equals(toolLoc[0])) {
                        found = true;

                        String location = toolLoc[1];

                        ToolLocationNodeProperty.ToolLocation toolLocation =
                                new ToolLocationNodeProperty.ToolLocation(desc, inst.getName(), location);
                        result.add(toolLocation);
                    }
                }
            }

            // Don't fail silently; rather, inform the user what tool is missing.
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
            /*
             * Limit the split on only the first occurrence of ':' so that the value can contain ':'
             * characters.
             */
            String[] keyValue = environmentVariable.split(":", 2);
            EnvironmentVariablesNodeProperty.Entry var =
                    new EnvironmentVariablesNodeProperty.Entry(keyValue[0], keyValue[1]);
            result.add(var);
        }

        return result;
    }
}
