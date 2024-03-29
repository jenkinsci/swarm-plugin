package hudson.plugins.swarm;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

public class LabelFileWatcher implements Runnable {

    private static final Logger logger = Logger.getLogger(LabelFileWatcher.class.getName());

    private static final long LABEL_FILE_WATCHER_INTERVAL_MILLIS = Long.getLong(
            LabelFileWatcher.class.getName() + ".labelFileWatcherIntervalMillis", TimeUnit.SECONDS.toMillis(30));

    private boolean isRunning = false;
    private final Options options;
    private final String name;
    private String labels;
    private final String[] args;
    private final URL url;

    public LabelFileWatcher(URL url, Options options, String name, String... args) throws IOException {
        logger.config("LabelFileWatcher() constructed with: " + options.labelsFile + " and " + String.join(", ", args));
        this.url = url;
        this.options = options;
        this.name = name;
        this.labels = Files.readString(Paths.get(options.labelsFile), StandardCharsets.UTF_8);
        this.args = args;
        logger.config("Labels loaded: " + labels);
    }

    private void softLabelUpdate(String sNewLabels) throws SoftLabelUpdateException {
        // 1. get labels from controller
        // 2. issue remove command for all old labels
        // 3. issue update commands for new labels
        logger.log(
                Level.CONFIG,
                "NOTICE: " + options.labelsFile + " has changed.  Attempting soft label update (no node restart)");

        logger.log(Level.CONFIG, "Getting current labels from controller");

        Document xml;

        HttpClient client = SwarmClient.createHttpClient(options);
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create(url + "plugin/swarm/getSlaveLabels?name=" + name))
                .GET();
        SwarmClient.addAuthorizationHeader(builder, options);
        HttpRequest request = builder.build();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != HttpURLConnection.HTTP_OK) {
                logger.log(
                        Level.CONFIG,
                        "Failed to retrieve labels from controller -- Response code: " + response.statusCode());
                throw new SoftLabelUpdateException(
                        "Unable to acquire labels from controller to begin removal process.");
            }
            try {
                xml = XmlUtils.parse(response.body());
            } catch (SAXException e) {
                String msg = "Invalid XML received from " + url;
                logger.log(Level.SEVERE, msg, e);
                throw new SoftLabelUpdateException(msg);
            }
        } catch (IOException | InterruptedException e) {
            String msg = "Exception when reading from " + url;
            logger.log(Level.SEVERE, msg, e);
            throw new SoftLabelUpdateException(msg);
        }

        String labelStr = SwarmClient.getChildElementString(xml.getDocumentElement(), "labels");
        labelStr = labelStr.replace("swarm", "");

        logger.log(Level.CONFIG, "Labels to be removed: " + labelStr);

        // remove the labels in 1000 char blocks
        List<String> lLabels = List.of(labelStr.split("\\s+"));
        StringBuilder sb = new StringBuilder();
        for (String s : lLabels) {
            sb.append(s);
            sb.append(" ");
            if (sb.length() > 1000) {
                try {
                    SwarmClient.postLabelRemove(name, sb.toString(), client, options, url);
                } catch (IOException | InterruptedException | RetryException e) {
                    String msg = "Exception when removing label from " + url;
                    logger.log(Level.SEVERE, msg, e);
                    throw new SoftLabelUpdateException(msg);
                }
                sb = new StringBuilder();
            }
        }
        if (sb.length() > 0) {
            try {
                SwarmClient.postLabelRemove(name, sb.toString(), client, options, url);
            } catch (IOException | InterruptedException | RetryException e) {
                String msg = "Exception when removing label from " + url;
                logger.log(Level.SEVERE, msg, e);
                throw new SoftLabelUpdateException(msg);
            }
        }

        // now add the labels back on
        logger.log(Level.CONFIG, "Labels to be added: " + sNewLabels);
        lLabels = List.of(sNewLabels.split("\\s+"));
        sb = new StringBuilder();
        for (String s : lLabels) {
            sb.append(s);
            sb.append(" ");
            if (sb.length() > 1000) {
                try {
                    SwarmClient.postLabelAppend(name, sb.toString(), client, options, url);
                } catch (IOException | InterruptedException | RetryException e) {
                    String msg = "Exception when appending label to " + url;
                    logger.log(Level.SEVERE, msg, e);
                    throw new SoftLabelUpdateException(msg);
                }
                sb = new StringBuilder();
            }
        }

        if (sb.length() > 0) {
            try {
                SwarmClient.postLabelAppend(name, sb.toString(), client, options, url);
            } catch (IOException | InterruptedException | RetryException e) {
                String msg = "Exception when appending label to " + url;
                logger.log(Level.SEVERE, msg, e);
                throw new SoftLabelUpdateException(msg);
            }
        }
    }

    private void hardLabelUpdate() throws IOException {
        logger.config("NOTICE: " + options.labelsFile + " has changed.  Hard node restart attempt initiated.");
        isRunning = false;
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        try {
            File currentJar = new File(LabelFileWatcher.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
            if (!currentJar.getName().endsWith(".jar")) {
                throw new URISyntaxException(currentJar.getName(), "Doesn't end in .jar");
            } else {
                // invoke the restart
                ArrayList<String> command = new ArrayList<>();
                command.add(javaBin);
                if (System.getProperty("java.util.logging.config.file") == null) {
                    logger.warning("NOTE:  You do not have a -Djava.util.logging.config.file specified,"
                            + " but your labels file has changed.  You will lose logging for"
                            + " the new client instance. Although the client will continue to"
                            + " work, you will have no logging.");
                } else {
                    command.add(
                            "-Djava.util.logging.config.file=" + System.getProperty("java.util.logging.config.file"));
                }
                command.add("-jar");
                command.add(currentJar.getPath());
                Collections.addAll(command, args);
                String sCommandString = Arrays.toString(command.toArray());
                sCommandString =
                        sCommandString.replaceAll("\n", "").replaceAll("\r", "").replaceAll(",", "");
                logger.config("Invoking: " + sCommandString);
                ProcessBuilder builder = new ProcessBuilder(command);
                builder.start();
                logger.config("New node instance started, ignore subsequent warning.");
            }
        } catch (URISyntaxException e) {
            logger.log(
                    Level.SEVERE,
                    "ERROR: LabelFileWatcher unable to determine current running jar. Node"
                            + " failure. URISyntaxException.",
                    e);
        }
    }

    @Override
    @SuppressFBWarnings(value = "DM_EXIT", justification = "behavior is intentional")
    @SuppressWarnings("lgtm[jenkins/unsafe-calls]")
    public void run() {
        String sTempLabels;
        isRunning = true;

        logger.config("LabelFileWatcher running, monitoring file: " + options.labelsFile);

        while (isRunning) {
            try {
                logger.log(
                        Level.FINE,
                        String.format("LabelFileWatcher sleeping %d milliseconds", LABEL_FILE_WATCHER_INTERVAL_MILLIS));
                Thread.sleep(LABEL_FILE_WATCHER_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                logger.log(Level.WARNING, "LabelFileWatcher InterruptedException occurred.", e);
            }
            try {
                sTempLabels = Files.readString(Paths.get(options.labelsFile), StandardCharsets.UTF_8);
                if (sTempLabels.equalsIgnoreCase(labels)) {
                    logger.log(Level.FINEST, "Nothing to do. " + options.labelsFile + " has not changed.");
                } else {
                    try {
                        // try to do the "soft" form of label updating (manipulating the labels
                        // through the plugin APIs
                        softLabelUpdate(sTempLabels);
                        labels = Files.readString(Paths.get(options.labelsFile), StandardCharsets.UTF_8);
                    } catch (SoftLabelUpdateException e) {
                        // if we're unable to
                        logger.log(
                                Level.WARNING,
                                "WARNING: Normal process, soft label update failed. "
                                        + e.getLocalizedMessage()
                                        + ", forcing Swarm client restart. This can be disruptive"
                                        + " to Jenkins jobs. Check your Swarm client log files to"
                                        + " see why this is happening.");
                        hardLabelUpdate();
                    }
                }
            } catch (IOException e) {
                logger.log(
                        Level.WARNING,
                        "WARNING: unable to read "
                                + options.labelsFile
                                + ", node may not be reporting proper labels to controller.",
                        e);
            }
        }

        logger.warning("LabelFileWatcher no longer running. Shutting down this Swarm client.");
        System.exit(0);
    }
}
