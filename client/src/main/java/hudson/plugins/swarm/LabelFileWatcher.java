package hudson.plugins.swarm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.lang.InterruptedException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.lang.System;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.*;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.UsernamePasswordCredentials;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.lang.StringUtils;

import org.w3c.dom.Document;
import org.xml.sax.SAXException;


public class LabelFileWatcher implements Runnable {

    private static String sFileName;
    private static boolean bRunning = false;
    private static Options opts = null;
    private static String sLabels;
    private static String[] sArgs;
    private static Candidate targ = null;
    private static final Logger logger = Logger.getLogger(LabelFileWatcher.class.getPackage().getName());

    public LabelFileWatcher(Candidate target, Options options, String... args) throws IOException {
        logger.config("LabelFileWatcher() constructed with: " + options.labelsFile + ", and " + StringUtils.join(args));
        targ = target;
        opts = options;
        sFileName = options.labelsFile;
        sLabels = new String(Files.readAllBytes(Paths.get(sFileName)), "UTF-8");
        sArgs = args;
        logger.config("Labels loaded: " + sLabels);
    }


    private HttpClient createHttpClient(URL urlForAuth) {
        logger.fine("createHttpClient() invoked");

        if (opts.disableSslVerification) {
            try {
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(new KeyManager[0], new TrustManager[]{new SwarmClient.DefaultTrustManager()}, new SecureRandom());
                SSLContext.setDefault(ctx);
            }
            catch (KeyManagementException e) {
                logger.log(Level.SEVERE, "KeyManagementException occurred", e);
                throw new RuntimeException(e);
            }
            catch (NoSuchAlgorithmException e) {
                logger.log(Level.SEVERE, "NoSuchAlgorithmException occurred", e);
                throw new RuntimeException(e);
            }
        }

        HttpClient client = SwarmClient.getGlobalHttpClient();

        if (opts.username != null && opts.password != null) {
            logger.fine("Setting HttpClient credentials based on options passed");
            client.getState().setCredentials(
                    new AuthScope(urlForAuth.getHost(), urlForAuth.getPort()),
                    new UsernamePasswordCredentials(opts.username, opts.password));
        }

        client.getParams().setAuthenticationPreemptive(true);
        return client;
    }


    private void softLabelUpdate(String sNewLabels) throws SoftLabelUpdateException, MalformedURLException {
    	// 1. get labels from master
    	// 2. issue remove command for all old labels
    	// 3. issue update commands for new labels
        logger.log(Level.CONFIG, "NOTICE: " + sFileName + " has changed.  Attempting soft label update (no slave restart)");
        HttpClient h = createHttpClient(new URL(targ.getURL()));

        logger.log(Level.CONFIG, "Getting current labels from master");

        GetMethod get = null;

        try {
            get = new GetMethod(targ.getURL()
                    + "/plugin/swarm/getSlaveLabels?name=" + opts.name
                    + "&secret=" + targ.getSecret());

        	int responseCode = h.executeMethod(get);
	        if (responseCode != HttpStatus.SC_OK) {
	            logger.log(Level.CONFIG, "Failed to retrieve labels from master -- Response code: " + responseCode);
	            throw new SoftLabelUpdateException("Unable to acquire labels from master to begin removal process.");
	        }

	        Document xml;
	        try {
	            xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(
	                    get.getResponseBody()));
	        } catch (SAXException e) {
	            String msg = "Invalid XML received from " + targ.getURL();
	            logger.log(Level.SEVERE, msg, e);
	            throw new SoftLabelUpdateException(msg);
	        }
	        String labelStr = SwarmClient.getChildElementString(xml.getDocumentElement(), "labels");
	        labelStr = labelStr.replace("swarm", "");

	        logger.log(Level.CONFIG, "Labels to be removed: " + labelStr);

	        // remove the labels in 1000 char blocks
	        List<String> lLabels = Arrays.asList(labelStr.split("\\s+"));
            StringBuilder sb = new StringBuilder();
            for(String s: lLabels) {
                sb.append(s);
                sb.append(" ");
                if(sb.length() > 1000) {
                    SwarmClient.postLabelRemove(opts.name, sb.toString(), h, targ);
                    sb = new StringBuilder();
                }
            }
            if(sb.length() > 0)
                SwarmClient.postLabelRemove(opts.name, sb.toString(), h, targ);

            // now add the labels back on
	        logger.log(Level.CONFIG, "Labels to be added: " + sNewLabels);
            lLabels = Arrays.asList(sNewLabels.split("\\s+"));
            sb = new StringBuilder();
            for(String s: lLabels) {
                sb.append(s);
                sb.append(" ");
                if(sb.length() > 1000) {
                    SwarmClient.postLabelAppend(opts.name, sb.toString(), h, targ);
                    sb = new StringBuilder();
                }
            }
            if(sb.length() > 0)
                SwarmClient.postLabelAppend(opts.name, sb.toString(), h, targ);

		}
        catch (Exception e) {
        	throw new SoftLabelUpdateException(e.getLocalizedMessage());
		}
        finally {
        	if(get != null)
        		get.releaseConnection();
        }
      }


    private void hardLabelUpdate() throws IOException {
        logger.config("NOTICE: " + sFileName + " has changed.  Hard slave restart attempt initiated.");
        bRunning = false;
        final String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        try
        {
            final File currentJar = new File(LabelFileWatcher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if(!currentJar.getName().endsWith(".jar")) {
                throw new URISyntaxException(currentJar.getName(), "Doesn't end in .jar");
            }
            else {
                // invoke the restart
                final ArrayList<String> command = new ArrayList<String>();
                command.add(javaBin);
                if (System.getProperty("java.util.logging.config.file") == null) {
                    logger.warning("NOTE:  You do not have a -Djava.util.logging.config.file specified, but your labels file has changed.  You will lose logging for the new client instance. Although the client will continue to work, you will have no logging.");
                }
                else {
                    command.add("-Djava.util.logging.config.file="+System.getProperty("java.util.logging.config.file"));
                }
                command.add("-jar");
                command.add(currentJar.getPath());
                for(int i=0;i<sArgs.length;i++) {
                    command.add(sArgs[i]);
                }
                String sCommandString = Arrays.toString(command.toArray());
                sCommandString = sCommandString.replaceAll("\n","").replaceAll("\r","").replaceAll(",","");
                logger.config("Invoking: " + sCommandString);
                final ProcessBuilder builder = new ProcessBuilder(command);
				builder.start();
                logger.config("New slave instance started, ignore subsequent warning.");
            }
        }
        catch(URISyntaxException e) {
            logger.log(Level.SEVERE,"ERROR: LabelFileWatcher unable to determine current running jar.  Slave failure.  URISyntaxException.", e);
        }
    }


    @SuppressWarnings("static-access")
	public void run() {
        String sTempLabels;
        bRunning = true;

        logger.config("LabelFileWatcher running, monitoring file: " + sFileName);

        while(bRunning) {
            try {
                logger.log(Level.FINE, "LabelFileWatcher sleeping 10 secs");
                Thread.currentThread().sleep(10 * 1000);
            }
            catch(InterruptedException e) {
                logger.log(Level.WARNING, "LabelFileWatcher InterruptedException occurred.", e);
            }
            try {
                sTempLabels = new String(Files.readAllBytes(Paths.get(sFileName)), "UTF-8");
                if(sTempLabels.equalsIgnoreCase(sLabels)) {
                    logger.log(Level.FINEST,"Nothing to do. " + sFileName + " has not changed.");
                }
                else {
                	try {
                		// try to do the "soft" form of label updating (manipulating the labels through the plugin APIs
                		softLabelUpdate(sTempLabels);
                        sLabels = new String(Files.readAllBytes(Paths.get(sFileName)), "UTF-8");
                	}
                	catch(SoftLabelUpdateException e) {
                        // if we're unable to
                        logger.log(Level.WARNING, "WARNING: Normal process, soft label update failed. " + e.getLocalizedMessage() + ", forcing swarm client reboot.  This can be disruptive to Jenkins jobs.  Check your swarm client log files to see why this is happening.");
                		hardLabelUpdate();
                	}
                }
            }
            catch(IOException e) {
                logger.log(Level.WARNING, "WARNING: unable to read " + sFileName + ", slave may not be reporting proper labels to master.", e);
            }
        }

        logger.warning("LabelFileWatcher no longer running.  Shutting down this instance of Swarm Client.");
        System.exit(0);
    }

}
