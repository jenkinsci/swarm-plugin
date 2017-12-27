package hudson.plugins.swarm;

import hudson.Extension;
import hudson.model.UnprotectedRootAction;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import javax.servlet.ServletException;
import java.io.IOException;

@Extension
public class DownloadClientAction implements UnprotectedRootAction {
    public String getIconFileName() {
        return null;
    }

    public String getDisplayName() {
        return null;
    }

    public String getUrlName() {
        return "swarm";
    }

    // serve static resources
    @Restricted(NoExternalUse.class)
    public void doDynamic(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
        Jenkins.getActiveInstance().getPlugin("swarm").doDynamic(req, rsp);
    }
}
