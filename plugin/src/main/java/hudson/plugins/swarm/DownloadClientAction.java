package hudson.plugins.swarm;

import hudson.Extension;
import hudson.Plugin;
import hudson.model.UnprotectedRootAction;

import jenkins.model.Jenkins;

import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import java.io.IOException;

import javax.servlet.ServletException;

@Extension
public class DownloadClientAction implements UnprotectedRootAction {

    @Override
    public String getIconFileName() {
        return null;
    }

    @Override
    public String getDisplayName() {
        return null;
    }

    @Override
    public String getUrlName() {
        return "swarm";
    }

    // serve static resources
    @Restricted(NoExternalUse.class)
    public void doDynamic(StaplerRequest req, StaplerResponse rsp)
            throws IOException, ServletException {
        Plugin plugin = Jenkins.get().getPlugin("swarm");
        if (plugin != null) {
            plugin.doDynamic(req, rsp);
        }
    }
}
