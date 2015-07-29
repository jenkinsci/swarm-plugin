package hudson.plugins.swarm;

import java.io.IOException;
import java.net.InetAddress;

import javax.xml.parsers.ParserConfigurationException;

import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;

/**
 * Swarm client.
 * <p/>
 * <p/>
 * Discovers nearby Jenkins via UDP broadcast, and pick eligible one randomly and
 * joins it.
 *
 * @author Kohsuke Kawaguchi
 * @changes Marcelo Brunken, Dmitry Buzdin, Peter Joensson
 */
public class Client {

    protected final Options options;

    public static void main(String... args) throws InterruptedException,
            IOException {
        Options options = new Options();
        Client client = new Client(options);
        CmdLineParser p = new CmdLineParser(options);
        try {
            p.parseArgument(args);
        } catch (CmdLineException e) {
            System.out.println(e.getMessage());
            p.printUsage(System.out);
            System.exit(-1);
        }
        if (options.help) {
            p.printUsage(System.out);
            System.exit(0);
        }
        // Check to see if passwordEnvVariable is set, if so pull down the
        // password from the env and set as password.
        if (options.passwordEnvVariable != null) {
            options.password = System.getenv(options.passwordEnvVariable);
        }

	// Only look up the hostname if we have not already specified
	// name of the slave. Also in certain cases this lookup might fail.
	// E.g.
	// Querying a external DNS server which might not be informed
	// of a newly created slave from a DHCP server.
	//
	// From https://docs.oracle.com/javase/8/docs/api/java/net/InetAddress.html#getCanonicalHostName--
	//
	// "Gets the fully qualified domain name for this IP
	// address. Best effort method, meaning we may not be able to
	// return the FQDN depending on the underlying system
	// configuration."
	if (options.name == null) {
	    try {
		client.options.name = InetAddress.getLocalHost().getCanonicalHostName();
	    } catch (IOException e) {
		System.out.println("Failed to lookup the canonical hostname of this slave, please check system settings.");
		System.out.println("If not possible to resolve please specify a node name using the '-name' option");
		System.exit(-1);
	    }
	}

	client.run();
    }

    public Client(Options options) {
        this.options = options;
    }

    /**
     * Finds a Jenkins master that supports swarming, and join it.
     * <p/>
     * This method never returns.
     */
    public void run() throws InterruptedException {
        System.out.println("Discovering Jenkins master");

        SwarmClient swarmClient = new SwarmClient(options);

        // The Jenkins that we are trying to connect to.
        Candidate target;

        // wait until we get the ACK back
        while (true) {
            try {
                if (options.master == null) {
                    target = swarmClient.discoverFromBroadcast();
                } else {
                    target = swarmClient.discoverFromMasterUrl();
                }

                if (options.password == null && options.username == null) {
                    swarmClient.verifyThatUrlIsHudson(target);
                }

                System.out.println("Attempting to connect to " + target.url + " " + target.secret + " with ID " + swarmClient.getHash());

                // create a new swarm slave
                swarmClient.createSwarmSlave(target);
                swarmClient.connect(target);
                if (options.noRetryAfterConnected) {
                    System.out.println("Connection closed, exiting...");
                    System.exit(0);
                }
            } catch (IOException e) {
                e.printStackTrace();
            } catch (ParserConfigurationException e) {
                e.printStackTrace();
            } catch (RetryException e) {
                System.out.println(e.getMessage());
                if (e.getCause() != null) {
                    e.getCause().printStackTrace();
                }
            }

            if (options.retry >= 0) {
                if (options.retry == 0) {
                    System.out.println("Retry limit reached, exiting...");
                    System.exit(-1);
                } else {
                    System.out.println("Remaining retries: " + options.retry);
                    options.retry--;
                }
            }

            // retry
            System.out.println("Retrying in 10 seconds");
            Thread.sleep(10 * 1000);
        }
    }
}
