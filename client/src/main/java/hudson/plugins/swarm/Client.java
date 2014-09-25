package hudson.plugins.swarm;

import hudson.remoting.Launcher;
import hudson.remoting.jnlp.Main;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.UsernamePasswordCredentials;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.Text;
import org.xml.sax.SAXException;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.*;
import java.net.*;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.text.MessageFormat;

/**
 * Swarm client.
 * <p/>
 * <p/>
 * Discovers nearby Jenkins via UDP broadcast, and pick eligible one randomly and
 * joins it.
 *
 * @author Kohsuke Kawaguchi
 * @changes Marcelo Brunken
 */
public class Client {

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

    @Option(name = "-master", usage = "The complete target Jenkins URL like 'http://server:8080/jenkins/'. If this option is specified, auto-discovery will be skipped")
    public String master;

    @Option(name = "-autoDiscoveryAddress", usage = "Use this address for udp-based auto-discovery (default 255.255.255.255)")
    public String autoDiscoveryAddress = "255.255.255.255";

    @Option(name = "-disableSslVerification", usage = "Disables SSL verification in the HttpClient.")
    public boolean disableSslVerification;

    @Option(name = "-noRetryAfterConnected", usage = "Do not retry if a successful connection gets closed.")
    public boolean noRetryAfterConnected;

    @Option(name = "-retry", usage = "Number of retries before giving up. Unlimited if not specified.")
    public int retry = -1;

    @Option(
            name = "-mode",
            usage = "The mode controlling how Jenkins allocates jobs to slaves. Can be either '" + ModeOptionHandler.NORMAL + "' " +
                    "(utilize this slave as much as possible) or '" + ModeOptionHandler.EXCLUSIVE + "' (leave this machine for tied " +
                    "jobs only). Default is " + ModeOptionHandler.NORMAL + ".",
            handler = ModeOptionHandler.class
    )
    public String mode = ModeOptionHandler.NORMAL;
    
    @Option(name = "-toolLocations", usage = "Whitespace-separated list of tool locations to be defined on this slave. A tool location is specified as 'toolname:location'")
    public List<String> toolLocations = new ArrayList<String>();

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
     * <p/>
     * This method never returns.
     */
    public void run() throws InterruptedException {
        System.out.println("Discovering Jenkins master");

        // wait until we get the ACK back
        while (true) {
            try {
                if (master == null) {
                    target = discoverFromBroadcast();
                } else {
                    target = discoverFromMasterUrl();
                }

                if (password == null && username == null) {
                    verifyThatUrlIsHudson();
                }

                System.out.println("Attempting to connect to " + target.url + " " + target.secret);

                // create a new swarm slave
                createSwarmSlave();
                connect();
                if (noRetryAfterConnected) {
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

            if (retry >= 0) {
                if (retry == 0) {
                    System.out.println("Retry limit reached, exiting...");
                    System.exit(-1);
                } else {
                    System.out.println("Remaining retries: " + retry);
                    retry--;
                }
            }

            // retry
            System.out.println("Retrying in 10 seconds");
            Thread.sleep(10 * 1000);
        }

    }

    protected Candidate discoverFromBroadcast() throws IOException,
            InterruptedException, RetryException, ParserConfigurationException {

        DatagramSocket socket = new DatagramSocket();
        socket.setBroadcast(true);

        sendBroadcast(socket);
        List<DatagramPacket> responses = collectBroadcastResponses(socket);
        return getCandidateFromDatagramResponses(responses);
    }

    private Candidate getCandidateFromDatagramResponses(List<DatagramPacket> responses) throws ParserConfigurationException, IOException, RetryException {
        List<Candidate> candidates = new ArrayList<Candidate>();
        for (DatagramPacket recv : responses) {

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
                        + " doesn't have the configuration set yet. Please go to the system configuration page of this Jenkins and submit it: "
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

        return candidates.get(new Random().nextInt(candidates.size()));
    }

    protected void sendBroadcast(DatagramSocket socket) throws IOException {
        byte[] buffer= new byte[128];
        Arrays.fill(buffer, (byte) 1);
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        packet.setAddress(InetAddress.getByName(autoDiscoveryAddress));
        packet.setPort(Integer.getInteger("jenkins.udp", Integer.getInteger("hudson.udp", 33848)));
        socket.send(packet);
    }

    protected List<DatagramPacket> collectBroadcastResponses(DatagramSocket socket) throws IOException, RetryException {
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
                    throw new RetryException("Failed to receive a reply to broadcast.");
                }
                return responses;
            }
        }
    }

    private Candidate discoverFromMasterUrl() throws IOException, ParserConfigurationException, RetryException {
        if (!master.endsWith("/")) {
            master += "/";
        }
        URL masterURL;
        try {
            masterURL = new URL(master);
        } catch (MalformedURLException e) {
            throw new RuntimeException(MessageFormat.format("The master URL \"{0}\" is invalid", master), e);
        }

        HttpClient client = createHttpClient(masterURL);

        String url = masterURL.toExternalForm() + "plugin/swarm/slaveInfo";
        GetMethod get = new GetMethod(url);
        get.setDoAuthentication(true);
        int responseCode = client.executeMethod(get);

        if (responseCode != 200) {
            throw new RetryException(
                    "Failed to fetch slave info from Jenkins CODE: " + responseCode);

        }

        Document xml;
        try {
            xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(get.getResponseBody()));
        } catch (SAXException e) {
            throw new RetryException("Invalid XML received from " + url);
        }
        String swarmSecret = getChildElementString(xml.getDocumentElement(), "swarmSecret");
        return new Candidate(masterURL.toExternalForm(), swarmSecret);
    }

    /**
     * This method blocks while the swarm slave is serving as a slave.
     * <p/>
     * Interrupt the thread to abort it and return.
     */
    protected void connect() throws InterruptedException {
        try {
            Launcher launcher = new Launcher();

            launcher.slaveJnlpURL = new URL(target.url + "computer/" + name
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

    protected HttpClient createHttpClient(URL urlForAuth) {
        if (disableSslVerification) {
            try {
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(new KeyManager[0], new TrustManager[]{new DefaultTrustManager()}, new SecureRandom());
                SSLContext.setDefault(ctx);
            } catch (KeyManagementException e) {
                throw new RuntimeException(e);
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        }

        HttpClient client = new HttpClient();

        if (username != null && password != null) {
            client.getState().setCredentials(
                    new AuthScope(urlForAuth.getHost(), urlForAuth.getPort()),
                    new UsernamePasswordCredentials(username, password));
        }

        client.getParams().setAuthenticationPreemptive(true);
        return client;
    }

    private Crumb getCsrfCrumb(HttpClient client) throws IOException {
        GetMethod httpGet = new GetMethod(target.url + "crumbIssuer/api/xml?xpath=" + URLEncoder.encode("concat(//crumbRequestField,\":\",//crumb)", "UTF-8"));
        httpGet.setDoAuthentication(true);
        int responseCode = client.executeMethod(httpGet);
        if (responseCode != HttpStatus.SC_OK) {
            System.out.println("Could not obtain CSRF crumb. Response code: " + responseCode);
            return null;
        }
        String[] crumbResponse = httpGet.getResponseBodyAsString().split(":");
        if (crumbResponse.length != 2) {
            System.out.println("Unexpected CSRF crumb response: " + httpGet.getResponseBodyAsString());
            return null;
        }
        return new Crumb(crumbResponse[0], crumbResponse[1]);
    }

    protected void createSwarmSlave() throws IOException, InterruptedException,
            RetryException {

        HttpClient client = createHttpClient(new URL(target.url));

        // Jenkins does not do any authentication negotiation,
        // ie. it does not return a 401 (Unauthorized)
        // but immediately a 403 (Forbidden)

        String labelStr = StringUtils.join(labels, ' ');
        String toolLocationsStr = StringUtils.join(toolLocations, ' ');

        PostMethod post = new PostMethod(target.url
                + "/plugin/swarm/createSlave?name=" + name 
                + "&executors=" + executors
                + param("remoteFsRoot", remoteFsRoot.getAbsolutePath())
                + param("description", description)
                + param("toolLocations", toolLocationsStr)
                + param("labels", labelStr) 
                + "&secret=" + target.secret
                + param("mode", mode.toUpperCase()));

        post.setDoAuthentication(true);

        Crumb csrfCrumb = getCsrfCrumb(client);
        if (csrfCrumb != null) {
            post.addRequestHeader(csrfCrumb.crumbRequestField, csrfCrumb.crumb);
        }

        int responseCode = client.executeMethod(post);
        if (responseCode != 200) {
            throw new RetryException(
                    "Failed to create a slave on Jenkins CODE: " + responseCode);

        }
    }
    
    private String encode(String value) throws UnsupportedEncodingException {
        return URLEncoder.encode(value, "UTF-8");
    }

    private String param(String name, String value)
            throws UnsupportedEncodingException {
        if (value == null) {
            return "";
        }
        return "&" + name + "=" + encode(value);
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

    private static class DefaultTrustManager implements X509TrustManager {

        public void checkClientTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException {
        }

        public void checkServerTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException {
        }

        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    private static class Crumb {

        private final String crumb;
        private final String crumbRequestField;

        Crumb(String crumbRequestField, String crumb) {
            this.crumbRequestField = crumbRequestField;
            this.crumb = crumb;
        }

    }

}
