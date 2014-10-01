package hudson.plugins.swarm;

import hudson.remoting.Launcher;
import hudson.remoting.jnlp.Main;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpStatus;
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

    private final Options options;

    public SwarmClient(Options options) {
        this.options = options;
    }

    public Candidate discoverFromBroadcast() throws IOException,
            RetryException, ParserConfigurationException {

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

            String url = options.master == null ? getChildElementString(
                    xml.getDocumentElement(), "url") : options.master;
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
        packet.setAddress(InetAddress.getByName(options.autoDiscoveryAddress));
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

    public Candidate discoverFromMasterUrl() throws IOException, ParserConfigurationException, RetryException {
        if (!options.master.endsWith("/")) {
            options.master += "/";
        }
        URL masterURL;
        try {
            masterURL = new URL(options.master);
        } catch (MalformedURLException e) {
            throw new RuntimeException(MessageFormat.format("The master URL \"{0}\" is invalid", options.master), e);
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
     * @param target candidate
     */
    protected void connect(Candidate target) throws InterruptedException {
        try {
            Launcher launcher = new Launcher();

            launcher.slaveJnlpURL = new URL(target.url + "computer/" + options.name
                    + "/slave-agent.jnlp");

            if (options.username != null && options.password != null) {
                launcher.auth = options.username + ":" + options.password;
                launcher.slaveJnlpCredentials = options.username + ":" + options.password;
            }

            List<String> jnlpArgs = launcher.parseJnlpArguments();

            List<String> args = new LinkedList<String>();
            args.add(jnlpArgs.get(0));
            args.add(jnlpArgs.get(1));

            args.add("-url");
            args.add(target.url);
            if (options.username != null && options.password != null) {
                args.add("-credentials");
                args.add(options.username + ":" + options.password);
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
        if (options.disableSslVerification) {
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

        if (options.username != null && options.password != null) {
            client.getState().setCredentials(
                    new AuthScope(urlForAuth.getHost(), urlForAuth.getPort()),
                    new UsernamePasswordCredentials(options.username, options.password));
        }

        client.getParams().setAuthenticationPreemptive(true);
        return client;
    }

    private Crumb getCsrfCrumb(HttpClient client, Candidate target) throws IOException {
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

    protected void createSwarmSlave(Candidate target) throws IOException, InterruptedException,
            RetryException {

        HttpClient client = createHttpClient(new URL(target.url));

        // Jenkins does not do any authentication negotiation,
        // ie. it does not return a 401 (Unauthorized)
        // but immediately a 403 (Forbidden)

        String labelStr = StringUtils.join(options.labels, ' ');
        String toolLocationsStr = StringUtils.join(options.toolLocations, ' ');

        PostMethod post = new PostMethod(target.url
                + "/plugin/swarm/createSlave?name=" + options.name 
                + "&executors=" + options.executors
                + param("remoteFsRoot", options.remoteFsRoot.getAbsolutePath())
                + param("description", options.description)
                + param("labels", labelStr)
                + param("toolLocations", toolLocationsStr)
                + "&secret=" + target.secret
                + param("mode", options.mode.toUpperCase()));

        post.setDoAuthentication(true);

        Crumb csrfCrumb = getCsrfCrumb(client, target);
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

    protected void verifyThatUrlIsHudson(Candidate target) throws InterruptedException,
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
