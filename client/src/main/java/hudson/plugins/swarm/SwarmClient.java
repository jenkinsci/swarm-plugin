package hudson.plugins.swarm;

import com.sun.net.httpserver.HttpServer;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import hudson.remoting.Launcher;
import hudson.remoting.jnlp.Main;

import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmHeapPressureMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.FileDescriptorMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.binder.system.UptimeMetrics;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.hc.client5.http.auth.AuthCache;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.auth.BasicAuthCache;
import org.apache.hc.client5.http.impl.auth.BasicScheme;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.ssl.HttpsSupport;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.ssl.SSLInitializationException;
import org.apache.http.entity.StringEntity;
import org.jenkinsci.remoting.util.VersionNumber;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.Text;
import org.xml.sax.SAXException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.io.UnsupportedEncodingException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.xml.parsers.ParserConfigurationException;

public class SwarmClient {

    private static final Logger logger = Logger.getLogger(SwarmClient.class.getName());

    private final Options options;
    private final String hash;
    private String name;
    private HttpServer prometheusServer = null;

    public SwarmClient(Options options) {
        this.options = options;
        if (!options.disableClientsUniqueId) {
            this.hash = hash(options.fsroot);
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
                logger.info(
                        "Effective label list: "
                                + Arrays.toString(options.labels.toArray())
                                        .replaceAll("\n", "")
                                        .replaceAll("\r", ""));
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "Problem reading labels from file " + options.labelsFile, e);
            }
        }

        if (options.prometheusPort > 0) {
            startPrometheusService(options.prometheusPort);
        }
    }

    public String getName() {
        return name;
    }

    public URL getUrl() {
        logger.config("getUrl() invoked");

        if (!options.url.endsWith("/")) {
            options.url += "/";
        }

        try {
            return new URL(options.url);
        } catch (MalformedURLException e) {
            throw new UncheckedIOException(String.format("The URL %s is invalid", options.url), e);
        }
    }

    /**
     * @param url The URL
     * @return The JNLP arguments, as returned from {@link Launcher#parseJnlpArguments()}.
     */
    List<String> getJnlpArgs(URL url) throws IOException, RetryException {
        logger.fine("connect() invoked");

        Launcher launcher = new Launcher();

        /*
         * Swarm does its own retrying internally, so disable the retrying functionality in
         * Launcher#parseJnlpArguments().
         */
        launcher.noReconnect = true;

        launcher.agentJnlpURL = new URL(url + "computer/" + name + "/slave-agent.jnlp");

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
            return launcher.parseJnlpArguments();
        } catch (InterruptedException
                | ParserConfigurationException
                | RuntimeException
                | SAXException e) {
            throw new RetryException("Failed get JNLP arguments for " + url, e);
        }
    }

    /**
     * This method blocks while the Swarm agent is connected.
     *
     * <p>Interrupt the thread to abort it and try connecting again.
     */
    void connect(List<String> jnlpArgs, URL url) throws IOException, RetryException {
        List<String> args = new ArrayList<>();
        args.add(jnlpArgs.get(0));
        args.add(jnlpArgs.get(1));

        args.add("-url");
        args.add(url.toString());

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
            String workDirPath =
                    options.workDir != null
                            ? options.workDir.getPath()
                            : options.fsroot.getPath();
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

        /*
         * Swarm does its own retrying internally, so disable the retrying functionality in
         * hudson.remoting.Engine.
         */
        args.add("-noreconnect");

        if (options.webSocket) {
            args.add("-webSocket");

            if (options.webSocketHeaders != null) {
                for (Map.Entry<String, String> entry : options.webSocketHeaders.entrySet()) {
                    args.add("-webSocketHeader");
                    args.add(entry.getKey() + "=" + entry.getValue());
                }
            }
        }

        try {
            Main.main(args.toArray(new String[0]));
        } catch (InterruptedException | RuntimeException e) {
            throw new RetryException("Failed to establish JNLP connection to " + url, e);
        }
    }

    static CloseableHttpClient createHttpClient(Options clientOptions) {
        logger.fine("createHttpClient() invoked");

        HttpClientBuilder builder = HttpClientBuilder.create();
        builder.useSystemProperties();

        if (clientOptions.disableSslVerification || !clientOptions.sslFingerprints.isEmpty()) {
            // Set the default SSL context for Remoting.
            SSLContext sslContext;
            try {
                sslContext = SSLContext.getInstance("TLS");
                String trusted =
                        clientOptions.disableSslVerification ? "" : clientOptions.sslFingerprints;
                sslContext.init(
                        new KeyManager[0],
                        new TrustManager[] {new DefaultTrustManager(trusted)},
                        new SecureRandom());
            } catch (KeyManagementException | NoSuchAlgorithmException e) {
                logger.log(Level.SEVERE, "An error occurred", e);
                throw new SSLInitializationException(e.getMessage(), e);
            }
            SSLContext.setDefault(sslContext);

            // HttpComponents Client does not use the default SSL context, so explicitly configure
            // it with the SSL context we created above.
            HostnameVerifier hostnameVerifier =
                    clientOptions.disableSslVerification
                            ? NoopHostnameVerifier.INSTANCE
                            : HttpsSupport.getDefaultHostnameVerifier();
            SSLConnectionSocketFactory sslSocketFactory =
                    new SSLConnectionSocketFactory(sslContext, hostnameVerifier);
            HttpClientConnectionManager connectionManager =
                    PoolingHttpClientConnectionManagerBuilder.create()
                            .setSSLSocketFactory(sslSocketFactory)
                            .build();
            builder.setConnectionManager(connectionManager);
        }

        return builder.build();
    }

    static HttpClientContext createHttpClientContext(Options clientOptions, URL urlForAuth) {
        logger.fine("createHttpClientContext() invoked");

        HttpClientContext context = HttpClientContext.create();

        if (clientOptions.username != null && clientOptions.password != null) {
            logger.fine("Setting HttpClient credentials based on options passed");

            BasicScheme basicScheme = new BasicScheme();
            basicScheme.initPreemptive(
                    new UsernamePasswordCredentials(
                            clientOptions.username, clientOptions.password.toCharArray()));

            HttpHost host =
                    new HttpHost(
                            urlForAuth.getProtocol(), urlForAuth.getHost(), urlForAuth.getPort());
            context.resetAuthExchange(host, basicScheme);

            AuthCache authCache = new BasicAuthCache();
            authCache.put(host, basicScheme);
            context.setAuthCache(authCache);
        }

        return context;
    }

    @SuppressFBWarnings(
            value = "RCN_REDUNDANT_NULLCHECK_WOULD_HAVE_BEEN_A_NPE",
            justification = "False positive for try-with-resources in Java 11")
    private static synchronized Crumb getCsrfCrumb(
            CloseableHttpClient client, HttpClientContext context, URL url)
            throws IOException, ParseException {
        logger.finer("getCsrfCrumb() invoked");

        String[] crumbResponse;

        HttpGet httpGet =
                new HttpGet(
                        url
                                + "crumbIssuer/api/xml?xpath="
                                + URLEncoder.encode(
                                        "concat(//crumbRequestField,\":\",//crumb)", "UTF-8"));
        try (CloseableHttpResponse response = client.execute(httpGet, context)) {
            if (response.getCode() != HttpStatus.SC_OK) {
                logger.log(
                        Level.SEVERE,
                        String.format(
                                "Could not obtain CSRF crumb. Response code: %s%n%s",
                                response.getCode(),
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
    void createSwarmAgent(URL url) throws IOException, ParseException, RetryException {
        logger.fine("createSwarmAgent() invoked");

        CloseableHttpClient client = createHttpClient(options);
        HttpClientContext context = createHttpClientContext(options, url);

        VersionNumber jenkinsVersion = getJenkinsVersion(client, context, url);
        if (options.webSocket
                && jenkinsVersion.isOlderThan(new VersionNumber("2.229"))
                && !(jenkinsVersion.getDigitAt(0) == 2
                        && jenkinsVersion.getDigitAt(1) == 222
                        && jenkinsVersion.getDigitAt(2) >= 4)) {
            throw new RetryException(
                    String.format(
                            "\"%s\" running Jenkins %s does not support the WebSocket protocol.",
                            url, jenkinsVersion));
        }

        String labelStr = StringUtils.join(options.labels, ' ');
        StringBuilder toolLocationBuilder = new StringBuilder();
        if (options.toolLocations != null) {
            for (Map.Entry<String, String> toolLocation : options.toolLocations.entrySet()) {
                toolLocationBuilder.append(
                        param(
                                "toolLocation",
                                toolLocation.getKey() + ":" + toolLocation.getValue()));
            }
        }

        StringBuilder environmentVariablesBuilder = new StringBuilder();
        if (options.environmentVariables != null) {
            for (Map.Entry<String, String> environmentVariable :
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
                        url
                                + "plugin/swarm/createSlave?name="
                                + options.name
                                + "&executors="
                                + options.executors
                                + param("remoteFsRoot", options.fsroot.getAbsolutePath())
                                + param("description", options.description)
                                + param("labels", sMyLabels)
                                + toolLocationBuilder
                                + environmentVariablesBuilder
                                + param("mode", options.mode.toUpperCase(Locale.ENGLISH))
                                + param("hash", hash)
                                + param(
                                        "deleteExistingClients",
                                        Boolean.toString(options.deleteExistingClients)));

        post.addHeader(HttpHeaders.CONNECTION, "close");

        // Add an empty body, as without it, some servers return a HTTP 411 response.
        post.setEntity((HttpEntity) new StringEntity(""));

        Crumb csrfCrumb = getCsrfCrumb(client, context, url);
        if (csrfCrumb != null) {
            post.addHeader(csrfCrumb.crumbRequestField, csrfCrumb.crumb);
        }

        try (CloseableHttpResponse response = client.execute(post, context)) {
            if (response.getCode() != HttpStatus.SC_OK) {
                throw new RetryException(
                        String.format(
                                "Failed to create a Swarm agent on Jenkins. Response code: %s%n%s",
                                response.getCode(),
                                EntityUtils.toString(
                                        response.getEntity(), StandardCharsets.UTF_8)));
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
                    postLabelAppend(name, sb.toString(), client, context, url);
                    sb = new StringBuilder();
                }
            }
            if (sb.length() > 0) {
                postLabelAppend(name, sb.toString(), client, context, url);
            }
        }
    }

    @SuppressFBWarnings(
            value = "RCN_REDUNDANT_NULLCHECK_WOULD_HAVE_BEEN_A_NPE",
            justification = "False positive for try-with-resources in Java 11")
    static synchronized void postLabelRemove(
            String name,
            String labels,
            CloseableHttpClient client,
            HttpClientContext context,
            URL url)
            throws IOException, ParseException, RetryException {
        HttpPost post =
                new HttpPost(
                        url
                                + "plugin/swarm/removeSlaveLabels?name="
                                + name
                                + SwarmClient.param("labels", labels));

        post.addHeader(HttpHeaders.CONNECTION, "close");

        // Add an empty body, as without it, some servers return a HTTP 411 response.
        post.setEntity((HttpEntity) new StringEntity(""));

        Crumb csrfCrumb = SwarmClient.getCsrfCrumb(client, context, url);
        if (csrfCrumb != null) {
            post.addHeader(csrfCrumb.crumbRequestField, csrfCrumb.crumb);
        }

        try (CloseableHttpResponse response = client.execute(post, context)) {
            if (response.getCode() != HttpStatus.SC_OK) {
                throw new RetryException(
                        String.format(
                                "Failed to remove agent labels. Response code: %s%n%s",
                                response.getCode(),
                                EntityUtils.toString(
                                        response.getEntity(), StandardCharsets.UTF_8)));
            }
        }
    }

    @SuppressFBWarnings(
            value = "RCN_REDUNDANT_NULLCHECK_WOULD_HAVE_BEEN_A_NPE",
            justification = "False positive for try-with-resources in Java 11")
    static synchronized void postLabelAppend(
            String name,
            String labels,
            CloseableHttpClient client,
            HttpClientContext context,
            URL url)
            throws IOException, ParseException, RetryException {
        HttpPost post =
                new HttpPost(
                        url + "plugin/swarm/addSlaveLabels?name=" + name + param("labels", labels));

        post.addHeader(HttpHeaders.CONNECTION, "close");

        // Add an empty body, as without it, some servers return a HTTP 411 response.
        post.setEntity((HttpEntity) new StringEntity(""));

        Crumb csrfCrumb = getCsrfCrumb(client, context, url);
        if (csrfCrumb != null) {
            post.addHeader(csrfCrumb.crumbRequestField, csrfCrumb.crumb);
        }

        try (CloseableHttpResponse response = client.execute(post, context)) {
            if (response.getCode() != HttpStatus.SC_OK) {
                throw new RetryException(
                        String.format(
                                "Failed to update agent labels. Response code: %s%n%s",
                                response.getCode(),
                                EntityUtils.toString(
                                        response.getEntity(), StandardCharsets.UTF_8)));
            }
        }
    }

    private static synchronized String encode(String value) throws UnsupportedEncodingException {
        logger.finer("encode() invoked");

        return URLEncoder.encode(value, "UTF-8");
    }

    private static synchronized String param(String name, String value)
            throws UnsupportedEncodingException {
        logger.finer("param() invoked");

        if (value == null) {
            return "";
        }
        return "&" + name + "=" + encode(value);
    }

    /** Get the Jenkins version. */
    @SuppressFBWarnings(
            value = "RCN_REDUNDANT_NULLCHECK_WOULD_HAVE_BEEN_A_NPE",
            justification = "False positive for try-with-resources in Java 11")
    VersionNumber getJenkinsVersion(CloseableHttpClient client, HttpClientContext context, URL url)
            throws IOException, ParseException, RetryException {
        logger.fine("getJenkinsVersion() invoked");

        HttpGet httpGet = new HttpGet(url + "api");
        try (CloseableHttpResponse response = client.execute(httpGet, context)) {
            if (response.getCode() != HttpStatus.SC_OK) {
                throw new RetryException(
                        String.format(
                                "Could not get Jenkins version. Response code: %s%n%s",
                                response.getCode(),
                                EntityUtils.toString(
                                        response.getEntity(), StandardCharsets.UTF_8)));
            }

            Header[] headers = response.getHeaders("X-Jenkins");
            if (headers.length != 1) {
                throw new RetryException("This URL doesn't look like Jenkins.");
            }

            String versionStr = headers[0].getValue();
            try {
                return new VersionNumber(versionStr);
            } catch (RuntimeException e) {
                throw new RetryException("Unexpected Jenkins version: " + versionStr, e);
            }
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
        if (prometheusServer != null) {
            prometheusServer.stop(1);
        }
        System.exit(status);
    }

    public void sleepSeconds(int waitTime) throws InterruptedException {
        TimeUnit.SECONDS.sleep(waitTime);
    }

    private void startPrometheusService(int port) {
        logger.fine("Starting Prometheus service on port " + port);
        PrometheusMeterRegistry prometheusRegistry =
                new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        // Add some standard metrics to the registry
        new ClassLoaderMetrics().bindTo(prometheusRegistry);
        new FileDescriptorMetrics().bindTo(prometheusRegistry);
        new JvmGcMetrics().bindTo(prometheusRegistry);
        new JvmHeapPressureMetrics().bindTo(prometheusRegistry);
        new JvmMemoryMetrics().bindTo(prometheusRegistry);
        new JvmThreadMetrics().bindTo(prometheusRegistry);
        new ProcessorMetrics().bindTo(prometheusRegistry);
        new UptimeMetrics().bindTo(prometheusRegistry);

        try {
            prometheusServer = HttpServer.create(new InetSocketAddress(port), 0);
            prometheusServer.createContext(
                    "/prometheus",
                    httpExchange -> {
                        String response = prometheusRegistry.scrape();
                        byte[] responseContent = response.getBytes(StandardCharsets.UTF_8);
                        httpExchange.sendResponseHeaders(200, responseContent.length);
                        try (OutputStream os = httpExchange.getResponseBody()) {
                            os.write(responseContent);
                        }
                    });

            new Thread(prometheusServer::start).start();
        } catch (IOException e) {
            logger.severe("Failed to start Prometheus service: " + e.getMessage());
            throw new UncheckedIOException(e);
        }
        logger.info("Started Prometheus service on port " + port);
    }

    private static class DefaultTrustManager implements X509TrustManager {

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
