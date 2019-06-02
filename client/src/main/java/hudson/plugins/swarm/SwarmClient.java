package hudson.plugins.swarm;

import static java.nio.charset.StandardCharsets.UTF_8;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.remoting.Launcher;
import hudson.remoting.jnlp.Main;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.MultiThreadedHttpConnectionManager;
import org.apache.commons.httpclient.UsernamePasswordCredentials;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.lang.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.Text;
import org.xml.sax.SAXException;

public class SwarmClient {

    private static final Logger logger = Logger.getLogger(SwarmClient.class.getPackage().getName());

    private final Options options;
    private final String hash;
    private String name;
    private static HttpClient g_client = null;


    @SuppressFBWarnings("DM_EXIT")
    public SwarmClient(Options options) {
        logger.config("SwarmClient constructed with " + options);

        this.options = options;
        Map<String, String> env = System.getenv();
        if (env.containsKey("MESOS_TASK_ID") && StringUtils.isNotEmpty(env.get("MESOS_TASK_ID"))) {
            this.hash = env.get("MESOS_TASK_ID");
            logger.info("Using MESOS_TASK_ID: " + this.hash);
        } else if (!options.disableClientsUniqueId) {
            this.hash = hash(options.remoteFsRoot);
        } else {
            this.hash = "";
        }
        this.name = options.name;

        if (options.labelsFile != null) {
            logger.info("Loading labels from " + options.labelsFile + "...");
            try {
                String labels = new String(Files.readAllBytes(Paths.get(options.labelsFile)), UTF_8);
                options.labels.addAll(Arrays.asList(labels.split(" ")));
                logger.info("Labels found in file: " + labels);
                logger.info("Effective label list: " + Arrays.toString(options.labels.toArray()).replaceAll("\n", "").replaceAll("\r", ""));
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Problem reading labels from file " + options.labelsFile, e);
                e.printStackTrace();
                System.exit(1);
            }
        }
    }

    public String getHash() {
        return hash;
    }

    public Candidate discoverFromBroadcast() throws IOException, RetryException {
        logger.config("discoverFromBroadcast() invoked");

        DatagramSocket socket = new DatagramSocket();
        socket.setBroadcast(true);

        sendBroadcast(socket);
        List<DatagramPacket> responses = collectBroadcastResponses(socket);
        return getCandidateFromDatagramResponses(responses);
    }

    private Candidate getCandidateFromDatagramResponses(List<DatagramPacket> responses)
            throws IOException, RetryException {
        logger.finer("getCandidateFromDatagramResponses() invoked");

        List<Candidate> candidates = new ArrayList<>();
        for (DatagramPacket recv : responses) {
            String responseXml = new String(recv.getData(), 0, recv.getLength(), UTF_8);

            Document xml;

            String address = printable(recv.getAddress());

            try {
                xml = XmlUtils.parse(recv.getData());
            } catch (SAXException e) {
                logger.severe("Invalid response XML from " + address + ": " + responseXml);
                continue;
            }
            if (!StringUtils.isBlank(options.candidateTag)) {
                logger.finer(address + options.candidateTag);
                continue;
            }
            String swarm = getChildElementString(xml.getDocumentElement(), "swarm");
            if (swarm == null) {
                logger.warning(address + " doesn't support swarm");
                continue;
            }

            String url = options.master == null ? getChildElementString(
                    xml.getDocumentElement(), "url") : options.master;

            if (url == null) {
                logger.warning("Jenkins master at '" + address + "' doesn't have a valid Jenkins URL configuration set. Please go to <jenkins url>/configure and set a valid URL.");
                continue;
            }
            candidates.add(new Candidate(url, swarm));
        }

        if (candidates.isEmpty()) {
            logger.severe("No nearby Jenkins supports swarming");
            throw new RetryException("No nearby Jenkins supports swarming");
        }

        logger.finer("Found " + candidates.size() + " eligible Jenkins.");

        return candidates.get(new Random().nextInt(candidates.size()));
    }

    protected void sendBroadcast(DatagramSocket socket) throws IOException {
        logger.fine("sendBroadcast() invoked");

        byte[] buffer = new byte[128];
        Arrays.fill(buffer, (byte) 1);
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        packet.setAddress(InetAddress.getByName(options.autoDiscoveryAddress));
        packet.setPort(Integer.getInteger("jenkins.udp", Integer.getInteger("hudson.udp", 33848)));
        socket.send(packet);
    }

    protected List<DatagramPacket> collectBroadcastResponses(DatagramSocket socket) throws IOException, RetryException {
        List<DatagramPacket> responses = new ArrayList<>();

        logger.fine("collectBroadcastResponses() invoked");

        // wait for 5 secs to gather up all the replies
        long limit = System.currentTimeMillis() + (5 * 1000);
        while (true) {
            try {
                socket.setSoTimeout(Math.max(1, (int) (limit - System.currentTimeMillis())));

                DatagramPacket recv = new DatagramPacket(new byte[2048], 2048);
                socket.receive(recv);
                responses.add(recv);
            } catch (SocketTimeoutException e) {
                // timed out
                logger.log(Level.FINEST, "SocketTimeoutException occurred, may be normal.", e);
                if (responses.isEmpty()) {
                    String msg = "Failed to receive a reply to broadcast.";
                    logger.log(Level.WARNING, msg, e);
                    throw new RetryException(msg);
                }
                return responses;
            }
        }
    }

    public Candidate discoverFromMasterUrl() throws IOException, ParserConfigurationException, RetryException {
        logger.config("discoverFromMasterUrl() invoked");

        if (!options.master.endsWith("/")) {
            options.master += "/";
        }
        URL masterURL;
        try {
            masterURL = new URL(options.master);
        } catch (MalformedURLException e) {
            String msg = MessageFormat.format("The master URL \"{0}\" is invalid", options.master);
            logger.log(Level.SEVERE, msg, e);
            throw new RuntimeException(msg, e);
        }

        logger.config("Connecting to " + masterURL + " to configure swarm client.");
        HttpClient client = createHttpClient(masterURL);

        GetMethod get = null;
        String swarmSecret;

        try {
            String url = masterURL.toExternalForm() + "plugin/swarm/slaveInfo";
            get = new GetMethod(url);
            get.setDoAuthentication(true);
            get.addRequestHeader("Connection", "close");

            int responseCode = client.executeMethod(get);
            if (responseCode != 200) {
                if (responseCode == 404) {
                    String msg = "Failed to fetch swarm information from Jenkins, plugin not installed?";
                    logger.log(Level.SEVERE, msg);
                    throw new RetryException(msg);
                } else {
                    String msg = "Failed to fetch slave info from Jenkins, HTTP response code: " + responseCode;
                    logger.log(Level.SEVERE, msg);
                    throw new RetryException(msg);
                }
            }

            Document xml;
            try {
                xml = XmlUtils.parse(get.getResponseBody());
            } catch (SAXException e) {
                String msg = "Invalid XML received from " + url;
                logger.log(Level.SEVERE, msg, e);
                throw new RetryException(msg);
            }
            swarmSecret = getChildElementString(xml.getDocumentElement(), "swarmSecret");
        } finally {
            if (get != null) {
                get.releaseConnection();
            }
        }

        return new Candidate(masterURL.toExternalForm(), swarmSecret);
    }

    /**
     * This method blocks while the swarm slave is serving as a slave.
     * <p>
     * Interrupt the thread to abort it and return.
     *
     * @param target candidate
     */
    protected void connect(Candidate target) throws InterruptedException {
        logger.fine("connect() invoked");

        Launcher launcher = new Launcher();
        // prevent infinite retry in hudson.remoting.Launcher.parseJnlpArguments()
        launcher.noReconnect = true;

        List<String> jnlpArgs = Collections.emptyList();

        try {
            launcher.slaveJnlpURL = new URL(target.url + "computer/" + name + "/slave-agent.jnlp");
        } catch (MalformedURLException e) {
            e.printStackTrace();
            logger.log(Level.SEVERE, "Failed to establish JNLP connection to " + target.url, e);
            Thread.sleep(10 * 1000);
        }

        if (options.username != null && options.password != null) {
            launcher.auth = options.username + ":" + options.password;
            launcher.slaveJnlpCredentials = options.username + ":" + options.password;
        }

        try {
            jnlpArgs = launcher.parseJnlpArguments();
        } catch (Exception e) {
            e.printStackTrace();
            logger.log(Level.SEVERE, "Failed to establish JNLP connection to " + target.url, e);
            Thread.sleep(10 * 1000);
        }

        List<String> args = new LinkedList<>();
        args.add(jnlpArgs.get(0));
        args.add(jnlpArgs.get(1));

        args.add("-url");
        args.add(target.url);

        // if the tunnel option is set in the command line, use it
        if (options.tunnel != null) {
            args.add("-tunnel");
            args.add(options.tunnel);
            logger.fine("Using tunnel through " + options.tunnel);
        }

        if (options.username != null && options.password != null) {
            args.add("-credentials");
            args.add(options.username + ":" + options.password);
        }
        args.add("-headless");
        args.add("-noreconnect");

        try {
            Main.main(args.toArray(new String[0]));
        } catch (Exception e) {
            e.printStackTrace();
            logger.log(Level.SEVERE, "Failed to establish JNLP connection to " + target.url, e);
            Thread.sleep(10 * 1000);
        }
    }

    public static synchronized HttpClient getGlobalHttpClient() {
        if (g_client == null) {
            g_client = new HttpClient(new MultiThreadedHttpConnectionManager());
        }

        return g_client;
    }

    protected HttpClient createHttpClient(URL urlForAuth) {
        logger.fine("createHttpClient() invoked");

        if (options.disableSslVerification || !options.sslFingerprints.isEmpty()) {
            try {
                SSLContext ctx = SSLContext.getInstance("TLS");
                String trusted = options.disableSslVerification ? "" : options.sslFingerprints;
                ctx.init(new KeyManager[0], new TrustManager[]{new DefaultTrustManager(trusted)}, new SecureRandom());
                SSLContext.setDefault(ctx);
            } catch (KeyManagementException e) {
                logger.log(Level.SEVERE, "KeyManagementException occurred", e);
                throw new RuntimeException(e);
            } catch (NoSuchAlgorithmException e) {
                logger.log(Level.SEVERE, "NoSuchAlgorithmException occurred", e);
                throw new RuntimeException(e);
            }
        }

        HttpClient client = getGlobalHttpClient();

        if (options.username != null && options.password != null) {
            logger.fine("Setting HttpClient credentials based on options passed");
            client.getState().setCredentials(
                    new AuthScope(urlForAuth.getHost(), urlForAuth.getPort()),
                    new UsernamePasswordCredentials(options.username, options.password));
        }

        client.getParams().setAuthenticationPreemptive(true);
        return client;
    }

    protected static synchronized Crumb getCsrfCrumb(HttpClient client, Candidate target) throws IOException {
        logger.finer("getCsrfCrumb() invoked");

        GetMethod httpGet = null;
        String[] crumbResponse;

        try {
            httpGet = new GetMethod(target.url + "crumbIssuer/api/xml?xpath=" +
                    URLEncoder.encode("concat(//crumbRequestField,\":\",//crumb)", "UTF-8"));
            httpGet.setDoAuthentication(true);
            int responseCode = client.executeMethod(httpGet);
            if (responseCode != HttpStatus.SC_OK) {
                logger.log(Level.SEVERE, "Could not obtain CSRF crumb. Response code: " + responseCode);
                return null;
            }
            crumbResponse = httpGet.getResponseBodyAsString().split(":");
            if (crumbResponse.length != 2) {
                logger.log(Level.SEVERE, "Unexpected CSRF crumb response: " + httpGet.getResponseBodyAsString());
                return null;
            }
        } finally {
            if (httpGet != null) {
                httpGet.releaseConnection();
            }
        }

        return new Crumb(crumbResponse[0], crumbResponse[1]);
    }

    protected void createSwarmSlave(Candidate target) throws IOException, RetryException {
        logger.fine("createSwarmSlave() invoked");

        HttpClient client = createHttpClient(new URL(target.url));

        // Jenkins does not do any authentication negotiation,
        // ie. it does not return a 401 (Unauthorized)
        // but immediately a 403 (Forbidden)

        String labelStr = StringUtils.join(options.labels, ' ');
        StringBuilder toolLocationBuilder = new StringBuilder();
        if (options.toolLocations != null) {
            for (Entry<String, String> toolLocation : options.toolLocations.entrySet()) {
                toolLocationBuilder.append(param("toolLocation", toolLocation.getKey() + ":" + toolLocation.getValue()));
            }
        }

        String sMyLabels = labelStr;
        if (sMyLabels.length() > 1000) {
            sMyLabels = "";
        }

        PostMethod post = null;
        Properties props = new Properties();

        try {
            post = new PostMethod(target.url
                    + "plugin/swarm/createSlave?name=" + options.name
                    + "&executors=" + options.executors
                    + param("remoteFsRoot", options.remoteFsRoot.getAbsolutePath())
                    + param("description", options.description)
                    + param("labels", sMyLabels)
                    + toolLocationBuilder.toString()
                    + "&secret=" + target.secret
                    + param("mode", options.mode.toUpperCase(Locale.ENGLISH))
                    + param("hash", hash)
                    + param("deleteExistingClients", Boolean.toString(options.deleteExistingClients))
            );

            post.setDoAuthentication(true);
            post.addRequestHeader("Connection", "close");

            Crumb csrfCrumb = getCsrfCrumb(client, target);
            if (csrfCrumb != null) {
                post.addRequestHeader(csrfCrumb.crumbRequestField, csrfCrumb.crumb);
            }

            int responseCode = client.executeMethod(post);
            if (responseCode != HttpStatus.SC_OK) {
                String msg = String.format("Failed to create a slave on Jenkins, response code: %s%n%s",
                        responseCode,
                        post.getResponseBodyAsString());
                logger.log(Level.SEVERE, msg);
                throw new RetryException(msg);
            }
            InputStream stream = post.getResponseBodyAsStream();
            if (stream != null) {
                try {
                    props.load(stream);
                } finally {
                    stream.close();
                }
            }
        } finally {
            if (post != null) {
                post.releaseConnection();
            }
        }

        String name = props.getProperty("name");
        if (name == null) {
            this.name = options.name;
            return;
        }
        name = name.trim();
        if (name.isEmpty()) {
            this.name = options.name;
            return;
        }
        this.name = name;

        // special handling for very long lists of labels (avoids 413 FULL Header error)
        if (sMyLabels.length() == 0 && labelStr.length() > 0) {
            String[] lLabels = labelStr.split("\\s+");
            StringBuilder sb = new StringBuilder();
            for (String s : lLabels) {
                sb.append(s);
                sb.append(" ");
                if (sb.length() > 1000) {
                    postLabelAppend(name, sb.toString(), client, target);
                    sb = new StringBuilder();
                }
            }
            if (sb.length() > 0) {
                postLabelAppend(name, sb.toString(), client, target);
            }
        }
    }

    protected static synchronized void postLabelRemove(String name, String labels, HttpClient client, Candidate target) throws IOException, RetryException {
        PostMethod post = null;

        try {
            post = new PostMethod(target.url
                    + "plugin/swarm/removeSlaveLabels?name=" + name
                    + "&secret=" + target.secret
                    + SwarmClient.param("labels", labels));

            post.setDoAuthentication(true);
            post.addRequestHeader("Connection", "close");

            Crumb csrfCrumb = SwarmClient.getCsrfCrumb(client, target);
            if (csrfCrumb != null) {
                post.addRequestHeader(csrfCrumb.crumbRequestField, csrfCrumb.crumb);
            }

            int responseCode = client.executeMethod(post);
            if (responseCode != HttpStatus.SC_OK) {
                String msg = String.format("Failed to remove slave labels. %s - %s",
                        responseCode,
                        post.getResponseBodyAsString());
                logger.log(Level.SEVERE, msg);
                throw new RetryException(msg);
            }
        } finally {
            if (post != null) {
                post.releaseConnection();
            }
        }
    }

    protected static synchronized void postLabelAppend(String name, String labels, HttpClient client, Candidate target) throws RetryException, IOException {
        PostMethod post = null;

        try {
            post = new PostMethod(target.url
                    + "plugin/swarm/addSlaveLabels?name=" + name
                    + "&secret=" + target.secret
                    + param("labels", labels));

            post.setDoAuthentication(true);
            post.addRequestHeader("Connection", "close");

            Crumb csrfCrumb = getCsrfCrumb(client, target);
            if (csrfCrumb != null) {
                post.addRequestHeader(csrfCrumb.crumbRequestField, csrfCrumb.crumb);
            }

            int responseCode = client.executeMethod(post);
            if (responseCode != HttpStatus.SC_OK) {
                String msg = String.format("Failed to update slave labels. Slave is probably messed up. %s - %s",
                        responseCode,
                        post.getResponseBodyAsString());
                logger.log(Level.SEVERE, msg);
                throw new RetryException(msg);
            }
        } finally {
            if (post != null) {
                post.releaseConnection();
            }
        }
    }

    private static synchronized String encode(String value) throws UnsupportedEncodingException {
        logger.finer("encode() invoked");

        return URLEncoder.encode(value, "UTF-8");
    }

    protected static synchronized String param(String name, String value) throws UnsupportedEncodingException {
        logger.finer("param() invoked");

        if (value == null) {
            return "";
        }
        return "&" + name + "=" + encode(value);
    }

    protected void verifyThatUrlIsHudson(Candidate target) throws RetryException {
        logger.fine("verifyThatUrlIsHudson() invoked");

        try {
            logger.fine("Connecting to " + target.url);
            HttpURLConnection con = (HttpURLConnection) new URL(target.url).openConnection();
            con.connect();

            if (con.getResponseCode() == HttpURLConnection.HTTP_FORBIDDEN) {
                String msg = "This jenkins server requires Authentication!";
                logger.log(Level.SEVERE, msg);
                throw new RetryException(msg);
            }

            String v = con.getHeaderField("X-Hudson");
            if (v == null) {
                String msg = "This URL doesn't look like Jenkins.";
                logger.log(Level.SEVERE, msg);
                throw new RetryException(msg);
            }
        } catch (IOException e) {
            String msg = "Failed to connect to " + target.url;
            logger.log(Level.SEVERE, msg, e);
            throw new RetryException(msg, e);
        }
    }

    protected static String getChildElementString(Element parent, String tagName) {
        logger.finer("getChildElementString() invoked");

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

    private String printable(InetAddress ia) {
        logger.finer("printable() invoked");

        if (options.showHostName) {
            return ia.getHostName();
        } else {
            return ia.toString();
        }
    }

    /**
     * Returns a hash that should be consistent for any individual swarm client (as long as it has a persistent IP)
     * and should be unique to that client.
     *
     * @param remoteFsRoot the file system root should be part of the hash (to support multiple swarm clients from
     *                     the same machine)
     * @return our best effort at a consistent hash
     */
    public static String hash(File remoteFsRoot) {
        logger.config("hash() invoked");

        StringBuilder buf = new StringBuilder();
        try {
            buf.append(remoteFsRoot.getCanonicalPath()).append('\n');
        } catch (IOException e) {
            logger.log(Level.FINER, "hash() IOException - may be normal?", e);
            buf.append(remoteFsRoot.getAbsolutePath()).append('\n');
        }
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress ia : Collections.list(ni.getInetAddresses())) {
                    if (ia instanceof Inet4Address) {
                        buf.append(ia.getHostAddress()).append('\n');
                    } else if (ia instanceof Inet6Address) {
                        buf.append(ia.getHostAddress()).append('\n');
                    }
                }
                byte[] hardwareAddress = ni.getHardwareAddress();
                if (hardwareAddress != null) {
                    buf.append(Arrays.toString(hardwareAddress));
                }
            }
        } catch (SocketException e) {
            // oh well we tried
            logger.log(Level.FINEST, "hash() SocketException - 'oh well we tried'", e);
        }
        return DigestUtils.md5Hex(buf.toString()).substring(0, 8);
    }

    public void exitWithStatus(int status) {
        System.exit(status);
    }

    public void sleepSeconds(int waitTime) throws InterruptedException {
        Thread.sleep(waitTime * 1000);
    }

    protected static class DefaultTrustManager implements X509TrustManager {

        List<String> allowedFingerprints = new ArrayList<>();

        List<X509Certificate> acceptedIssuers = new ArrayList<>();

        @Override
        public void checkClientTrusted(X509Certificate[] x509Certificates, String s) {}

        @Override
        public void checkServerTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException {
            if (allowedFingerprints.isEmpty()) {
                return;
            }

            List<X509Certificate> list = new ArrayList<>();

            for (X509Certificate cert : x509Certificates) {
                String fingerprint = DigestUtils.sha256Hex(cert.getEncoded());
                logger.fine("Check fingerprint: " + fingerprint);
                if (allowedFingerprints.contains(fingerprint)) {
                    list.add(cert);
                    logger.fine("Found allowed certificate: " + cert);
                }
            }

            if (list.isEmpty()) {
                throw new CertificateException("Fingerprint mismatch");
            }

            acceptedIssuers.addAll(list);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return acceptedIssuers.toArray(new X509Certificate[0]);
        }

        public DefaultTrustManager(String fingerprints) {
            if (fingerprints.isEmpty()) {
                return;
            }

            for (String fingerprint : fingerprints.split("\\s+")) {
                String unified = StringUtils.remove(fingerprint.toLowerCase(), ':');
                logger.fine("Add allowed fingerprint: " + unified);
                allowedFingerprints.add(unified);
            }
        }
    }

    protected static class Crumb {
        protected final String crumb;
        protected final String crumbRequestField;

        Crumb(String crumbRequestField, String crumb) {
            this.crumbRequestField = crumbRequestField;
            this.crumb = crumb;
        }
    }

}
