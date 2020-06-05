package hudson.plugins.swarm;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import hudson.remoting.Launcher;
import hudson.remoting.jnlp.Main;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.HttpStatus;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.AuthCache;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.BasicAuthCache;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.jenkinsci.remoting.util.VersionNumber;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.Text;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
import java.util.List;
import java.util.Locale;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class SwarmClient {

    private static final Logger logger = Logger.getLogger(SwarmClient.class.getName());

    private final Options options;
    private final String hash;
    private String name;

    @SuppressFBWarnings("DM_EXIT")
    public SwarmClient(Options options) {
        this.options = options;
        if (!options.disableClientsUniqueId) {
            this.hash = hash(options.remoteFsRoot);
        } else {
            this.hash = "";
        }
        this.name = options.name;

        if (options.labelsFile != null) {
            logger.info("Loading labels from " + options.labelsFile + "...");
            try {
                String labels =
                        new String(
                                Files.readAllBytes(Paths.get(options.labelsFile)),
                                StandardCharsets.UTF_8);
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

    public String getName() {
        return name;
    }

    public URL getMasterUrl() {
        logger.config("getMasterUrl() invoked");

        if (!options.master.endsWith("/")) {
            options.master += "/";
        }

        try {
            return new URL(options.master);
        } catch (MalformedURLException e) {
            String msg = MessageFormat.format("The master URL \"{0}\" is invalid", options.master);
            logger.log(Level.SEVERE, msg, e);
            throw new RuntimeException(msg, e);
        }
    }

    /**
     * This method blocks while the Swarm agent is connected.
     *
     * <p>Interrupt the thread to abort it and return.
     */
    protected void connect(URL masterUrl) throws InterruptedException {
        logger.fine("connect() invoked");

        Launcher launcher = new Launcher();
        // prevent infinite retry in hudson.remoting.Launcher.parseJnlpArguments()
        launcher.noReconnect = true;

        List<String> jnlpArgs = Collections.emptyList();

        try {
            launcher.agentJnlpURL = new URL(masterUrl + "computer/" + name + "/slave-agent.jnlp");
        } catch (MalformedURLException e) {
            e.printStackTrace();
            logger.log(Level.SEVERE, "Failed to establish JNLP connection to " + masterUrl, e);
            Thread.sleep(10 * 1000);
        }

        if (options.username != null && options.password != null) {
            launcher.auth = options.username + ":" + options.password;
            launcher.agentJnlpCredentials = options.username + ":" + options.password;
        }

        if (options.disableSslVerification) {
            try {
                launcher.setNoCertificateCheck(true);
            } catch (KeyManagementException | NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        }

        try {
            jnlpArgs = launcher.parseJnlpArguments();
        } catch (Exception e) {
            e.printStackTrace();
            logger.log(Level.SEVERE, "Failed to establish JNLP connection to " + masterUrl, e);
            Thread.sleep(10 * 1000);
        }

        List<String> args = new ArrayList<>();
        args.add(jnlpArgs.get(0));
        args.add(jnlpArgs.get(1));

        args.add("-url");
        args.add(masterUrl.toString());

        if (options.disableSslVerification) {
            args.add("-disableHttpsCertValidation");
        }

        // if the tunnel option is set in the command line, use it
        if (options.tunnel != null) {
            args.add("-tunnel");
            args.add(options.tunnel);
            logger.fine("Using tunnel through " + options.tunnel);
        }

        if (options.username != null && options.password != null && !options.webSocket) {
            args.add("-credentials");
            args.add(options.username + ":" + options.password);
        }

        if (!options.disableWorkDir) {
            final String workDirPath =
                    options.workDir != null
                            ? options.workDir.getPath()
                            : options.remoteFsRoot.getPath();
            args.add("-workDir");
            args.add(workDirPath);

            if (options.internalDir != null) {
                args.add("-internalDir");
                args.add(options.internalDir.getPath());
            }

            if (options.failIfWorkDirIsMissing) {
                args.add("-failIfWorkDirIsMissing");
            }
        }

        if (options.jarCache != null) {
            args.add("-jar-cache");
            args.add(options.jarCache.getPath());
        }

        args.add("-headless");
        args.add("-noreconnect");

        if (options.webSocket) {
            args.add("-webSocket");
        }

        try {
            Main.main(args.toArray(new String[0]));
        } catch (Exception e) {
            e.printStackTrace();
            logger.log(Level.SEVERE, "Failed to establish JNLP connection to " + masterUrl, e);
            Thread.sleep(10 * 1000);
        }
    }

    static CloseableHttpClient createHttpClient(Options clientOptions) {
        logger.fine("createHttpClient() invoked");

        if (clientOptions.disableSslVerification || !clientOptions.sslFingerprints.isEmpty()) {
            try {
                SSLContext ctx = SSLContext.getInstance("TLS");
                String trusted =
                        clientOptions.disableSslVerification ? "" : clientOptions.sslFingerprints;
                ctx.init(
                        new KeyManager[0],
                        new TrustManager[] {new DefaultTrustManager(trusted)},
                        new SecureRandom());
                SSLContext.setDefault(ctx);
            } catch (KeyManagementException e) {
                logger.log(Level.SEVERE, "KeyManagementException occurred", e);
                throw new RuntimeException(e);
            } catch (NoSuchAlgorithmException e) {
                logger.log(Level.SEVERE, "NoSuchAlgorithmException occurred", e);
                throw new RuntimeException(e);
            }
        }

        HttpClientBuilder builder = HttpClientBuilder.create();
        builder.useSystemProperties();
        if (clientOptions.disableSslVerification) {
            builder.setSSLHostnameVerifier(new NoopHostnameVerifier());
        }
        return builder.build();
    }

    static HttpClientContext createHttpClientContext(Options clientOptions, URL urlForAuth) {
        logger.fine("createHttpClientContext() invoked");

        HttpClientContext context = HttpClientContext.create();

        if (clientOptions.username != null && clientOptions.password != null) {
            logger.fine("Setting HttpClient credentials based on options passed");

            CredentialsProvider credsProvider = new BasicCredentialsProvider();
            credsProvider.setCredentials(
                    new AuthScope(urlForAuth.getHost(), urlForAuth.getPort()),
                    new UsernamePasswordCredentials(
                            clientOptions.username, clientOptions.password));
            context.setCredentialsProvider(credsProvider);

            AuthCache authCache = new BasicAuthCache();
            authCache.put(
                    new HttpHost(
                            urlForAuth.getHost(), urlForAuth.getPort(), urlForAuth.getProtocol()),
                    new BasicScheme());
            context.setAuthCache(authCache);
        }

        return context;
    }

    @SuppressFBWarnings(
            value = "RCN_REDUNDANT_NULLCHECK_WOULD_HAVE_BEEN_A_NPE",
            justification = "False positive for try-with-resources in Java 11")
    private static synchronized Crumb getCsrfCrumb(
            CloseableHttpClient client, HttpClientContext context, URL masterUrl)
            throws IOException {
        logger.finer("getCsrfCrumb() invoked");

        String[] crumbResponse;

        HttpGet httpGet =
                new HttpGet(
                        masterUrl
                                + "crumbIssuer/api/xml?xpath="
                                + URLEncoder.encode(
                                        "concat(//crumbRequestField,\":\",//crumb)", "UTF-8"));
        try (CloseableHttpResponse response = client.execute(httpGet, context)) {
            if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                logger.log(
                        Level.SEVERE,
                        String.format(
                                "Could not obtain CSRF crumb. Response code: %s%n%s",
                                response.getStatusLine().getStatusCode(),
                                EntityUtils.toString(
                                        response.getEntity(), StandardCharsets.UTF_8)));
                return null;
            }

            String crumbResponseString =
                    EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            crumbResponse = crumbResponseString.split(":");
            if (crumbResponse.length != 2) {
                logger.log(Level.SEVERE, "Unexpected CSRF crumb response: " + crumbResponseString);
                return null;
            }
        }

        return new Crumb(crumbResponse[0], crumbResponse[1]);
    }

    @SuppressFBWarnings(
            value = "RCN_REDUNDANT_NULLCHECK_WOULD_HAVE_BEEN_A_NPE",
            justification = "False positive for try-with-resources in Java 11")
    protected void createSwarmAgent(URL masterUrl) throws IOException, RetryException {
        logger.fine("createSwarmAgent() invoked");

        CloseableHttpClient client = createHttpClient(options);
        HttpClientContext context = createHttpClientContext(options, masterUrl);

        VersionNumber jenkinsVersion = getJenkinsVersion(client, context, masterUrl);
        if (jenkinsVersion != null
                && options.webSocket
                && jenkinsVersion.isOlderThan(new VersionNumber("2.229"))
                && !(jenkinsVersion.getDigitAt(0) == 2
                        && jenkinsVersion.getDigitAt(1) == 222
                        && jenkinsVersion.getDigitAt(2) >= 4)) {
            throw new RetryException(
                    String.format(
                            "\"%s\" running Jenkins %s does not support the WebSocket protocol.",
                            masterUrl, jenkinsVersion));
        }

        String labelStr = StringUtils.join(options.labels, ' ');
        StringBuilder toolLocationBuilder = new StringBuilder();
        if (options.toolLocations != null) {
            for (Entry<String, String> toolLocation : options.toolLocations.entrySet()) {
                toolLocationBuilder.append(param("toolLocation", toolLocation.getKey() + ":" + toolLocation.getValue()));
            }
        }

        StringBuilder environmentVariablesBuilder = new StringBuilder();
        if (options.environmentVariables != null) {
            for (Entry<String, String> environmentVariable :
                    options.environmentVariables.entrySet()) {
                environmentVariablesBuilder.append(
                        param(
                                "environmentVariable",
                                environmentVariable.getKey()
                                        + ":"
                                        + environmentVariable.getValue()));
            }
        }

        String sMyLabels = labelStr;
        if (sMyLabels.length() > 1000) {
            sMyLabels = "";
        }

        Properties props = new Properties();

        HttpPost post =
                new HttpPost(
                        masterUrl
                                + "plugin/swarm/createSlave?name="
                                + options.name
                                + "&executors="
                                + options.executors
                                + param("remoteFsRoot", options.remoteFsRoot.getAbsolutePath())
                                + param("description", options.description)
                                + param("labels", sMyLabels)
                                + toolLocationBuilder.toString()
                                + environmentVariablesBuilder.toString()
                                + param("mode", options.mode.toUpperCase(Locale.ENGLISH))
                                + param("hash", hash)
                                + param(
                                        "deleteExistingClients",
                                        Boolean.toString(options.deleteExistingClients)));

        post.addHeader("Connection", "close");

        Crumb csrfCrumb = getCsrfCrumb(client, context, masterUrl);
        if (csrfCrumb != null) {
            post.addHeader(csrfCrumb.crumbRequestField, csrfCrumb.crumb);
        }

        try (CloseableHttpResponse response = client.execute(post, context)) {
            if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                String msg =
                        String.format(
                                "Failed to create a Swarm agent on Jenkins. Response code: %s%n%s",
                                response.getStatusLine().getStatusCode(),
                                EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8));
                logger.log(Level.SEVERE, msg);
                throw new RetryException(msg);
            }

            try (InputStream stream = response.getEntity().getContent()) {
                props.load(stream);
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
                    postLabelAppend(name, sb.toString(), client, context, masterUrl);
                    sb = new StringBuilder();
                }
            }
            if (sb.length() > 0) {
                postLabelAppend(name, sb.toString(), client, context, masterUrl);
            }
        }
    }

    @SuppressFBWarnings(
            value = "RCN_REDUNDANT_NULLCHECK_WOULD_HAVE_BEEN_A_NPE",
            justification = "False positive for try-with-resources in Java 11")
    protected static synchronized void postLabelRemove(
            String name,
            String labels,
            CloseableHttpClient client,
            HttpClientContext context,
            URL masterUrl)
            throws IOException, RetryException {
        HttpPost post =
                new HttpPost(
                        masterUrl
                                + "plugin/swarm/removeSlaveLabels?name="
                                + name
                                + SwarmClient.param("labels", labels));

        post.addHeader("Connection", "close");

        Crumb csrfCrumb = SwarmClient.getCsrfCrumb(client, context, masterUrl);
        if (csrfCrumb != null) {
            post.addHeader(csrfCrumb.crumbRequestField, csrfCrumb.crumb);
        }

        try (CloseableHttpResponse response = client.execute(post, context)) {
            if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                String msg =
                        String.format(
                                "Failed to remove agent labels. Response code: %s%n%s",
                                response.getStatusLine().getStatusCode(),
                                EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8));
                logger.log(Level.SEVERE, msg);
                throw new RetryException(msg);
            }
        }
    }

    @SuppressFBWarnings(
            value = "RCN_REDUNDANT_NULLCHECK_WOULD_HAVE_BEEN_A_NPE",
            justification = "False positive for try-with-resources in Java 11")
    protected static synchronized void postLabelAppend(
            String name,
            String labels,
            CloseableHttpClient client,
            HttpClientContext context,
            URL masterUrl)
            throws IOException, RetryException {
        HttpPost post =
                new HttpPost(
                        masterUrl
                                + "plugin/swarm/addSlaveLabels?name="
                                + name
                                + param("labels", labels));

        post.addHeader("Connection", "close");

        Crumb csrfCrumb = getCsrfCrumb(client, context, masterUrl);
        if (csrfCrumb != null) {
            post.addHeader(csrfCrumb.crumbRequestField, csrfCrumb.crumb);
        }

        try (CloseableHttpResponse response = client.execute(post, context)) {
            if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                String msg =
                        String.format(
                                "Failed to update agent labels. Response code: %s%n%s",
                                response.getStatusLine().getStatusCode(),
                                EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8));
                logger.log(Level.SEVERE, msg);
                throw new RetryException(msg);
            }
        }
    }

    private static synchronized String encode(String value) throws UnsupportedEncodingException {
        logger.finer("encode() invoked");

        return URLEncoder.encode(value, "UTF-8");
    }

    private static synchronized String param(String name, String value) throws UnsupportedEncodingException {
        logger.finer("param() invoked");

        if (value == null) {
            return "";
        }
        return "&" + name + "=" + encode(value);
    }

    /** Make a best-effort attempt to get the Jenkins version. */
    @CheckForNull
    @SuppressFBWarnings(
            value = "RCN_REDUNDANT_NULLCHECK_WOULD_HAVE_BEEN_A_NPE",
            justification = "False positive for try-with-resources in Java 11")
    protected VersionNumber getJenkinsVersion(
            CloseableHttpClient client, HttpClientContext context, URL masterUrl) {
        logger.fine("getJenkinsVersion() invoked");

        HttpGet httpGet = new HttpGet(masterUrl + "api");
        try (CloseableHttpResponse response = client.execute(httpGet, context)) {
            if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                String msg =
                        String.format(
                                "Could not get Jenkins version. Response code: Response code:"
                                        + " %s%n%s",
                                response.getStatusLine().getStatusCode(),
                                EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8));
                logger.log(Level.SEVERE, msg);
                return null;
            }

            Header[] headers = response.getHeaders("X-Jenkins");
            if (headers.length != 1) {
                String msg = "This URL doesn't look like Jenkins.";
                logger.log(Level.SEVERE, msg);
                return null;
            }

            String versionStr = headers[0].getValue();
            try {
                return new VersionNumber(versionStr);
            } catch (RuntimeException e) {
                String msg = "Unexpected Jenkins version: " + versionStr;
                logger.log(Level.SEVERE, msg);
                return null;
            }
        } catch (IOException e) {
            String msg = "Failed to connect to " + masterUrl;
            logger.log(Level.SEVERE, msg, e);
            return null;
        }
    }

    static String getChildElementString(Element parent, String tagName) {
        logger.finer("getChildElementString() invoked");

        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element) {
                Element element = (Element) node;
                if (element.getTagName().equals(tagName)) {
                    StringBuilder buf = new StringBuilder();
                    for (node = element.getFirstChild();
                            node != null;
                            node = node.getNextSibling()) {
                        if (node instanceof Text) {
                            buf.append(node.getTextContent());
                        }
                    }
                    return buf.toString();
                }
            }
        }
        return null;
    }

    /**
     * Returns a hash that should be consistent for any individual swarm client (as long as it has a
     * persistent IP) and should be unique to that client.
     *
     * @param remoteFsRoot the file system root should be part of the hash (to support multiple
     *     swarm clients from the same machine)
     * @return our best effort at a consistent hash
     */
    private static String hash(File remoteFsRoot) {
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

        final List<String> allowedFingerprints = new ArrayList<>();

        final List<X509Certificate> acceptedIssuers = new ArrayList<>();

        @Override
        public void checkClientTrusted(X509Certificate[] x509Certificates, String s) {}

        @Override
        public void checkServerTrusted(X509Certificate[] x509Certificates, String s)
                throws CertificateException {
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

    private static class Crumb {
        final String crumb;
        final String crumbRequestField;

        Crumb(String crumbRequestField, String crumb) {
            this.crumbRequestField = crumbRequestField;
            this.crumb = crumb;
        }
    }
}
