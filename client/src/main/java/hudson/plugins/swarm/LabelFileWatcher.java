package hudson.plugins.swarm;

import static java.nio.charset.StandardCharsets.UTF_8;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpHost;
import org.apache.http.HttpStatus;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.AuthCache;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.BasicAuthCache;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

public class LabelFileWatcher implements Runnable {

    private final Logger logger = Logger.getLogger(LabelFileWatcher.class.getPackage().getName());

    private String sFileName;
    private boolean bRunning = false;
    private Options opts;
    private final String name;
    private String sLabels;
    private String[] sArgs;
    private Candidate targ;

    public LabelFileWatcher(Candidate target, Options options, String name, String... args)
            throws IOException {
        logger.config("LabelFileWatcher() constructed with: " + options.labelsFile + ", and " + StringUtils.join(args));
        targ = target;
        opts = options;
        this.name = name;
        sFileName = options.labelsFile;
        sLabels = new String(Files.readAllBytes(Paths.get(sFileName)), UTF_8);
        sArgs = args;
        logger.config("Labels loaded: " + sLabels);
    }

    private CloseableHttpClient createHttpClient(URL urlForAuth) {
        logger.fine("createHttpClient() invoked");

        if (opts.disableSslVerification || !opts.sslFingerprints.isEmpty()) {
            try {
                SSLContext ctx = SSLContext.getInstance("TLS");
                String trusted = opts.disableSslVerification ? "" : opts.sslFingerprints;
                ctx.init(new KeyManager[0], new TrustManager[]{new SwarmClient.DefaultTrustManager(trusted)}, new SecureRandom());
                SSLContext.setDefault(ctx);
            } catch (KeyManagementException e) {
                logger.log(Level.SEVERE, "KeyManagementException occurred", e);
                throw new RuntimeException(e);
            } catch (NoSuchAlgorithmException e) {
                logger.log(Level.SEVERE, "NoSuchAlgorithmException occurred", e);
                throw new RuntimeException(e);
            }
        }

        return HttpClients.createSystem();
    }

    protected HttpClientContext createHttpClientContext(URL urlForAuth) {
        logger.fine("createHttpClientContext() invoked");

        HttpClientContext context = HttpClientContext.create();

        if (opts.username != null && opts.password != null) {
            logger.fine("Setting HttpClient credentials based on options passed");

            CredentialsProvider credsProvider = new BasicCredentialsProvider();
            credsProvider.setCredentials(
                    new AuthScope(urlForAuth.getHost(), urlForAuth.getPort()),
                    new UsernamePasswordCredentials(opts.username, opts.password));
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

    private void softLabelUpdate(String sNewLabels) throws SoftLabelUpdateException, MalformedURLException {
        // 1. get labels from master
        // 2. issue remove command for all old labels
        // 3. issue update commands for new labels
        logger.log(Level.CONFIG, "NOTICE: " + sFileName + " has changed.  Attempting soft label update (no node restart)");
        URL urlForAuth = new URL(targ.getURL());
        CloseableHttpClient h = createHttpClient(urlForAuth);
        HttpClientContext context = createHttpClientContext(urlForAuth);

        logger.log(Level.CONFIG, "Getting current labels from master");

        Document xml;

        HttpGet get =
                new HttpGet(
                        targ.getURL()
                                + "/plugin/swarm/getSlaveLabels?name="
                                + name
                                + "&secret="
                                + targ.getSecret());
        try (CloseableHttpResponse response = h.execute(get, context)) {
            if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                logger.log(
                        Level.CONFIG,
                        "Failed to retrieve labels from master -- Response code: "
                                + response.getStatusLine().getStatusCode());
                throw new SoftLabelUpdateException(
                        "Unable to acquire labels from master to begin removal process.");
            }
            try {
                xml = XmlUtils.parse(response.getEntity().getContent());
            } catch (SAXException e) {
                String msg = "Invalid XML received from " + targ.getURL();
                logger.log(Level.SEVERE, msg, e);
                throw new SoftLabelUpdateException(msg);
            }
        } catch (IOException e) {
            String msg = "IOException when reading from " + targ.getURL();
            logger.log(Level.SEVERE, msg, e);
            throw new SoftLabelUpdateException(msg);
        }

        String labelStr = SwarmClient.getChildElementString(xml.getDocumentElement(), "labels");
        labelStr = labelStr.replace("swarm", "");

        logger.log(Level.CONFIG, "Labels to be removed: " + labelStr);

        // remove the labels in 1000 char blocks
        List<String> lLabels = Arrays.asList(labelStr.split("\\s+"));
        StringBuilder sb = new StringBuilder();
        for (String s : lLabels) {
            sb.append(s);
            sb.append(" ");
            if (sb.length() > 1000) {
                try {
                    SwarmClient.postLabelRemove(name, sb.toString(), h, context, targ);
                } catch (IOException | RetryException e) {
                    String msg = "Exception when removing label from " + targ.getURL();
                    logger.log(Level.SEVERE, msg, e);
                    throw new SoftLabelUpdateException(msg);
                }
                sb = new StringBuilder();
            }
        }
        if (sb.length() > 0) {
            try {
                SwarmClient.postLabelRemove(name, sb.toString(), h, context, targ);
            } catch (IOException | RetryException e) {
                String msg = "Exception when removing label from " + targ.getURL();
                logger.log(Level.SEVERE, msg, e);
                throw new SoftLabelUpdateException(msg);
            }
        }

        // now add the labels back on
        logger.log(Level.CONFIG, "Labels to be added: " + sNewLabels);
        lLabels = Arrays.asList(sNewLabels.split("\\s+"));
        sb = new StringBuilder();
        for (String s : lLabels) {
            sb.append(s);
            sb.append(" ");
            if (sb.length() > 1000) {
                try {
                    SwarmClient.postLabelAppend(name, sb.toString(), h, context, targ);
                } catch (IOException | RetryException e) {
                    String msg = "Exception when appending label to " + targ.getURL();
                    logger.log(Level.SEVERE, msg, e);
                    throw new SoftLabelUpdateException(msg);
                }
                sb = new StringBuilder();
            }
        }

        if (sb.length() > 0) {
            try {
                SwarmClient.postLabelAppend(name, sb.toString(), h, context, targ);
            } catch (IOException | RetryException e) {
                String msg = "Exception when appending label to " + targ.getURL();
                logger.log(Level.SEVERE, msg, e);
                throw new SoftLabelUpdateException(msg);
            }
        }
    }

    private void hardLabelUpdate() throws IOException {
        logger.config("NOTICE: " + sFileName + " has changed.  Hard node restart attempt initiated.");
        bRunning = false;
        final String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        try {
            final File currentJar = new File(LabelFileWatcher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!currentJar.getName().endsWith(".jar")) {
                throw new URISyntaxException(currentJar.getName(), "Doesn't end in .jar");
            } else {
                // invoke the restart
                final ArrayList<String> command = new ArrayList<>();
                command.add(javaBin);
                if (System.getProperty("java.util.logging.config.file") == null) {
                    logger.warning("NOTE:  You do not have a -Djava.util.logging.config.file specified, but your labels file has changed.  You will lose logging for the new client instance. Although the client will continue to work, you will have no logging.");
                } else {
                    command.add("-Djava.util.logging.config.file=" + System.getProperty("java.util.logging.config.file"));
                }
                command.add("-jar");
                command.add(currentJar.getPath());
                Collections.addAll(command, sArgs);
                String sCommandString = Arrays.toString(command.toArray());
                sCommandString = sCommandString.replaceAll("\n", "").replaceAll("\r", "").replaceAll(",", "");
                logger.config("Invoking: " + sCommandString);
                final ProcessBuilder builder = new ProcessBuilder(command);
                builder.start();
                logger.config("New node instance started, ignore subsequent warning.");
            }
        } catch (URISyntaxException e) {
            logger.log(Level.SEVERE, "ERROR: LabelFileWatcher unable to determine current running jar. Node failure. URISyntaxException.", e);
        }
    }

    @Override
    @SuppressFBWarnings("DM_EXIT")
    public void run() {
        String sTempLabels;
        bRunning = true;

        logger.config("LabelFileWatcher running, monitoring file: " + sFileName);

        while (bRunning) {
            try {
                logger.log(Level.FINE, "LabelFileWatcher sleeping 10 secs");
                Thread.sleep(10 * 1000);
            } catch (InterruptedException e) {
                logger.log(Level.WARNING, "LabelFileWatcher InterruptedException occurred.", e);
            }
            try {
                sTempLabels = new String(Files.readAllBytes(Paths.get(sFileName)), UTF_8);
                if (sTempLabels.equalsIgnoreCase(sLabels)) {
                    logger.log(Level.FINEST, "Nothing to do. " + sFileName + " has not changed.");
                } else {
                    try {
                        // try to do the "soft" form of label updating (manipulating the labels through the plugin APIs
                        softLabelUpdate(sTempLabels);
                        sLabels = new String(Files.readAllBytes(Paths.get(sFileName)), UTF_8);
                    } catch (SoftLabelUpdateException e) {
                        // if we're unable to
                        logger.log(Level.WARNING, "WARNING: Normal process, soft label update failed. " + e.getLocalizedMessage() + ", forcing swarm client reboot.  This can be disruptive to Jenkins jobs.  Check your swarm client log files to see why this is happening.");
                        hardLabelUpdate();
                    }
                }
            } catch (IOException e) {
                logger.log(Level.WARNING, "WARNING: unable to read " + sFileName + ", node may not be reporting proper labels to master.", e);
            }
        }

        logger.warning("LabelFileWatcher no longer running. Shutting down this instance of Swarm Client.");
        System.exit(0);
    }

}
