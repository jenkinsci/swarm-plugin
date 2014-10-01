package hudson.plugins.swarm;

import org.kohsuke.args4j.Option;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 *
 */
public class Options {

    @Option(name = "-name", usage = "Name of the slave")
    public String name;

    @Option(name = "-description", usage = "Description to be put on the slave")
    public String description;

    @Option(name = "-labels", usage = "Whitespace-separated list of labels to be assigned for this slave. Multiple options are allowed.")
    public List<String> labels = new ArrayList<String>();

    @Option(name = "-fsroot", usage = "Directory where Jenkins places files")
    public File remoteFsRoot = new File(".");

    @Option(name = "-executors", usage = "Number of executors")
    public int executors = Runtime.getRuntime().availableProcessors();

    @Option(name = "-master", usage = "The complete target Jenkins URL like 'http://server:8080/jenkins/'. If this option is specified, auto-discovery will be skipped")
    public String master;

    @Option(name = "-noRetryAfterConnected", usage = "Do not retry if a successful connection gets closed.")
    public boolean noRetryAfterConnected;

    @Option(name = "-retry", usage = "Number of retries before giving up. Unlimited if not specified.")
    public int retry = -1;

    @Option(name = "-autoDiscoveryAddress", usage = "Use this address for udp-based auto-discovery (default 255.255.255.255)")
    public String autoDiscoveryAddress = "255.255.255.255";

    @Option(name = "-disableSslVerification", usage = "Disables SSL verification in the HttpClient.")
    public boolean disableSslVerification;

    @Option(
            name = "-mode",
            usage = "The mode controlling how Jenkins allocates jobs to slaves. Can be either '" + ModeOptionHandler.NORMAL + "' " +
                    "(utilize this slave as much as possible) or '" + ModeOptionHandler.EXCLUSIVE + "' (leave this machine for tied " +
                    "jobs only). Default is " + ModeOptionHandler.NORMAL + ".",
            handler = ModeOptionHandler.class
    )
    public String mode = ModeOptionHandler.NORMAL;

    @Option(name = "-username", usage = "The Jenkins username for authentication")
    public String username;

    @Option(name = "-password", usage = "The Jenkins user password")
    public String password;

    @Option(name = "-help", aliases = "--help", usage = "Show the help screen")
    public boolean help;

}
