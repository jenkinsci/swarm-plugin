package hudson.plugins.swarm;

import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;

import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.net.InetAddress;

/**
 * Swarm client.
 * <p/>
 * <p/>
 * Discovers nearby Jenkins via UDP broadcast, and pick eligible one randomly and
 * joins it.
 *
 * @author Kohsuke Kawaguchi
 * @changes Marcelo Brunken, Dmitry Buzdin
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
        client.run();
    }

    public Client(Options options) throws IOException {
        this.options = options;
        this.options.name = InetAddress.getLocalHost().getCanonicalHostName();
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

                System.out.println("Attempting to connect to " + target.url + " " + target.secret);

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
