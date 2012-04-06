package hudson.plugins.swarm;

import hudson.remoting.Launcher;
import hudson.remoting.jnlp.Main;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.UsernamePasswordCredentials;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.methods.PostMethod;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.Text;
import org.xml.sax.SAXException;

/**
 * Swarm client.
 * 
 * <p>
 * Discovers nearby Jenkins via UDP broadcast, and pick eligible one randomly and
 * joins it.
 * 
 * @author Kohsuke Kawaguchi
 * @changes Marcelo Brunken
 * 
 * 
 * 
 */
public class Client {

    /**
     * Used to discover the server.
     */
    protected final DatagramSocket socket;

    /**
     * The Jenkins that we are trying to connect to.
     */
    protected Candidate target;

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

    @Option(name = "-master", usage = "The complete target Jenkins URL like 'http://server:8080/jenkins'. If this option is specified, auto-discovery will be skipped")
    public String master;

    @Option(name = "-username", usage = "The Jenkins username for authentication")
    public String username;

    @Option(name = "-password", usage = "The Jenkins user password")
    public String password;

    @Option(name = "-help", aliases = "--help", usage = "Show the help screen")
    public boolean help;

    public static void main(String... args) throws InterruptedException,
            IOException {
        Client client = new Client();
        CmdLineParser p = new CmdLineParser(client);
        try {
            p.parseArgument(args);
        } catch (CmdLineException e) {
            System.out.println(e.getMessage());
            p.printUsage(System.out);
            System.exit(-1);
        }
        if (client.help) {
            p.printUsage(System.out);
            System.exit(0);
        }
        client.run();
    }

    public Client() throws IOException {
        socket = new DatagramSocket();
        socket.setBroadcast(true);
        name = InetAddress.getLocalHost().getCanonicalHostName();
    }

    class Candidate {

        final String url;

        final String secret;

        Candidate(String url, String secret) {
            this.url = url;
            this.secret = secret;
        }
    }

    /**
     * Finds a Jenkins master that supports swarming, and join it.
     * 
     * This method never returns.
     */
    public void run() throws InterruptedException {
        System.out.println("Discovering Jenkins master");

        // wait until we get the ACK back
        while (true) {
            try {
                List<Candidate> candidates = new ArrayList<Candidate>();
                for (DatagramPacket recv : discover()) {

                    String responseXml = new String(recv.getData(), 0,
                            recv.getLength());

                    Document xml;
                    System.out.println();

                    try {
                        xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(recv.getData(),
                                0, recv.getLength()));
                    } catch (SAXException e) {
                        System.out.println("Invalid response XML from "
                                + recv.getAddress() + ": " + responseXml);
                        continue;
                    }
                    String swarm = getChildElementString(
                            xml.getDocumentElement(), "swarm");
                    if (swarm == null) {
                        System.out.println(recv.getAddress()
                                + " doesn't support swarm");
                        continue;
                    }

                    String url = master == null ? getChildElementString(
                            xml.getDocumentElement(), "url") : master;
                    if (url == null) {
                        System.out.println(recv.getAddress()
                                + " doesn't have the configuration set yet. Please go to the sytem configuration page of this Jenkins and submit it: "
                                + responseXml);
                        continue;
                    }
                    candidates.add(new Candidate(url, swarm));
                }

                if (candidates.isEmpty()) {
                    throw new RetryException(
                            "No nearby Jenkins supports swarming");
                }

                System.out.println("Found " + candidates.size()
                        + " eligible Jenkins.");
                // randomly pick up the Jenkins to connect to
                target = candidates.get(new Random().nextInt(candidates.size()));
                if (password == null && username == null) {
                    verifyThatUrlIsHudson();
                }

                // create a new swarm slave
                createSwarmSlave();
                connect();
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

            // retry
            System.out.println("Retrying in 10 seconds");
            Thread.sleep(10 * 1000);
        }

    }

    /**
     * Discovers Jenkins running nearby.
     * 
     * To give every nearby Jenkins a fair chance, wait for some time until we
     * hear all the responses.
     */
    protected List<DatagramPacket> discover() throws IOException,
            InterruptedException, RetryException {
        sendBroadcast();

        List<DatagramPacket> responses = new ArrayList<DatagramPacket>();

        // wait for 5 secs to gather up all the replies
        long limit = System.currentTimeMillis() + 5 * 1000;
        while (true) {
            try {
                socket.setSoTimeout(Math.max(1,
                        (int) (limit - System.currentTimeMillis())));

                DatagramPacket recv = new DatagramPacket(new byte[2048], 2048);
                socket.receive(recv);
                responses.add(recv);
            } catch (SocketTimeoutException e) {
                // timed out
                if (responses.isEmpty()) {

                    if (master != null) {
                        throw new RetryException(
                                "Failed to receive a reply from " + master);
                    } else {
                        throw new RetryException(
                                "Failed to receive a reply to broadcast.");
                    }
                }
                return responses;
            }
        }
    }

    protected void sendBroadcast() throws IOException {

        URL url = null;
        if (master != null) {
            url = new URL(master);
        }

        byte[] buffer = new byte[128];
        Arrays.fill(buffer, (byte) 1);

        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        packet.setAddress(InetAddress.getByName(url != null ? url.getHost()
                : "255.255.255.255"));
        packet.setPort(Integer.getInteger("jenkins.udp", Integer.getInteger("hudson.udp", 33848)));
        socket.send(packet);
    }

    /**
     * This method blocks while the swarm slave is serving as a slave.
     *
     * Interrupt the thread to abort it and return.
     */
    protected void connect() throws InterruptedException {
        try {
            Launcher launcher = new Launcher();

            launcher.slaveJnlpURL = new URL(target.url + "/computer/" + name
                    + "/slave-agent.jnlp");

            if (username != null && password != null) {
                launcher.auth = username + ":" + password;
                launcher.slaveJnlpCredentials = username + ":" + password;
            }

            List<String> jnlpArgs = launcher.parseJnlpArguments();

            List<String> args = new LinkedList<String>();
            args.add(jnlpArgs.get(0));
            args.add(jnlpArgs.get(1));

            args.add("-url");
            args.add(target.url);
            if (username != null && password != null) {
                args.add("-credentials");
                args.add(username + ":" + password);
            }
            args.add("-headless");
            args.add("-noreconnect");

            Main.main(args.toArray(new String[args.size()]));
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("Failed to establish JNLP connection to "
                    + target.url);
            Thread.sleep(10 * 1000);
        }
    }

    protected void createSwarmSlave() throws IOException, InterruptedException,
            RetryException {
        StringBuilder labelStr = new StringBuilder();
        for (String l : labels) {
            if (labelStr.length() > 0) {
                labelStr.append(' ');
            }
            labelStr.append(l);
        }
        URL url = new URL(target.url);
        HttpClient client = new HttpClient();

        if (username != null && password != null) {
            client.getState().setCredentials(
                    new AuthScope(url.getHost(), url.getPort()),
                    new UsernamePasswordCredentials(username, password));
        }

        // Jenkins does not do any authentication negotiation,
        // ie. it does not return a 401 (Unauthorized)
        // but immediately a 403 (Forbidden)

        client.getParams().setAuthenticationPreemptive(true);
        PostMethod post = new PostMethod(target.url
                + "/plugin/swarm/createSlave?name=" + name + "&executors="
                + executors
                + param("remoteFsRoot", remoteFsRoot.getAbsolutePath())
                + param("description", description)
                + param("labels", labelStr.toString()) + "&secret="
                + target.secret);

        post.setDoAuthentication(true);
        int responseCode = client.executeMethod(post);
        if (responseCode != 200) {

            throw new RetryException(
                    "Failed to create a slave on Jenkins CODE: " + responseCode);

        }
    }

    private String param(String name, String value)
            throws UnsupportedEncodingException {
        if (value == null) {
            return "";
        }
        return "&" + name + "=" + URLEncoder.encode(value, "UTF-8");
    }

    protected void verifyThatUrlIsHudson() throws InterruptedException,
            RetryException {
        try {
            System.out.println("Connecting to " + target.url);
            HttpURLConnection con = (HttpURLConnection) new URL(target.url).openConnection();
            con.connect();

            if (con.getResponseCode() == HttpURLConnection.HTTP_FORBIDDEN) {
                throw new RetryException(
                        "This jenkins server requires Authentication!.");
            }

            String v = con.getHeaderField("X-Hudson");
            if (v == null) {
                throw new RetryException("This URL doesn't look like Jenkins.");
            }
        } catch (IOException e) {
            throw new RetryException("Failed to connect to " + target.url, e);
        }
    }

    private static void copy(InputStream in, OutputStream out)
            throws IOException {
        byte[] buf = new byte[8192];
        int len;
        while ((len = in.read(buf)) >= 0) {
            out.write(buf, 0, len);
        }
    }

    private static String getChildElementString(Element parent, String tagName) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element) {
                Element e = (Element) n;
                if (e.getTagName().equals(tagName)) {
                    StringBuilder buf = new StringBuilder();
                    for (n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
                        if (n instanceof Text) {
                            buf.append(n.getTextContent());
                        }
                    }
                    return buf.toString();
                }
            }
        }
        return null;
    }
}
