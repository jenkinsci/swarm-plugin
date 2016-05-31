package hudson.plugins.swarm;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.lang.InterruptedException;
import java.net.URISyntaxException;
import java.lang.System;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.logging.*;

public class LabelFileWatcher implements Runnable {

    private static String sFileName;
    private static boolean bRunning = false;
    private static String sLabels;
    private static String[] sArgs;
    private static final Logger logger = Logger.getLogger(LabelFileWatcher.class.getPackage().getName());
    
    public LabelFileWatcher(String fName, String... args) throws IOException {
        logger.config("LabelFileWatcher() constructed with: " + fName + ", and " + args);
        sFileName = fName;
        sLabels = new String(Files.readAllBytes(Paths.get(sFileName)));
        sArgs = args;
        logger.config("Labels loaded: " + sLabels);
    }

    public void run() {
        String sTempLabels;
        bRunning = true;
        
        logger.config("LabelFileWatcher running, monitoring file: " + sFileName);
        
        while(bRunning) {
            try {
                logger.config("LabelFileWatcher sleeping 10 secs");
                Thread.currentThread().sleep(10 * 1000);
            }
            catch(InterruptedException e) {
                logger.log(Level.WARNING, "LabelFileWatcher InterruptedException occurred.", e);
            }
            try {
                sTempLabels = new String(Files.readAllBytes(Paths.get(sFileName)));
                if(sTempLabels.equalsIgnoreCase(sLabels)) {
                    logger.config("Nothing to do. " + sFileName + " has not changed.");
                }
                else {
                    logger.config("NOTICE: " + sFileName + " has changed.  Slave restart attempt initiated.");
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
                            Process p = builder.start();
                            logger.config("New slave instance started, ignore subsequent warning.");
                        }
                    }
                    catch(URISyntaxException e) {
                        logger.log(Level.SEVERE,"ERROR: LabelFileWatcher unable to determine current running jar.  Slave failure.  URISyntaxException.", e);
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
    
    public void stopLabelFileWatcher() {
        bRunning = false;
        logger.config("Stopping LabelFileWatcher thread");
    }

}
