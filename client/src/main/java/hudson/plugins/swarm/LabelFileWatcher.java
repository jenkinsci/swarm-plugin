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

public class LabelFileWatcher implements Runnable {

    private static String sFileName;
    private static boolean bRunning = false;
    private static String sLabels;
    private static String[] sArgs;
    
    public LabelFileWatcher(String fName, String... args) throws IOException {
        sFileName = fName;
        sLabels = new String(Files.readAllBytes(Paths.get(sFileName)));
        sArgs = args;
    }

    public void run() {
        String sTempLabels;
        bRunning = true;
        System.out.println("LabelFileWatcher running, monitoring file: " + sFileName);
        
        
        while(bRunning) {
            try {
                Thread.currentThread().sleep(10 * 1000);
            }
            catch(InterruptedException e) {
                System.out.println("LabelFileWatcher InterruptedException occurred.");
            }
            try {
                sTempLabels = new String(Files.readAllBytes(Paths.get(sFileName)));
                if(!sTempLabels.equalsIgnoreCase(sLabels)) {
                    System.out.println("NOTICE: " + sFileName + " has changed.  Slave restart initiated.");
                    bRunning = false;
                    final String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
                    try
                    {
                        final File currentJar = new File(LabelFileWatcher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                        if(!currentJar.getName().endsWith(".jar")) {
                            System.out.println("ERROR: LabelFileWatcher unable to determine current running jar.  Slave failure.");
                        }
                        else {
                            // invoke the restart
                            final ArrayList<String> command = new ArrayList<String>();
                            command.add(javaBin);
                            command.add("-jar");
                            command.add(currentJar.getPath());
                            for(int i=0;i<sArgs.length;i++) {
                                command.add(sArgs[i]);
                            }
                            String sCommandString = Arrays.toString(command.toArray());
                            sCommandString = sCommandString.replaceAll("\n","").replaceAll("\r","").replaceAll(",","");
                            System.out.println("Invoking: " + sCommandString);
                            final ProcessBuilder builder = new ProcessBuilder(command);
                            Process p = builder.start();
                            System.out.println("New slave instance started.");
                        }
                    }
                    catch(URISyntaxException e) {
                        System.out.println("WARNING: LabelFileWatcher unable to determine current running jar.  Slave failure.  URISyntaxException.");
                    }
                }
            }
            catch(IOException e) {
                System.out.println("WARNING: unable to read " + sFileName + ", slave may not be reporting proper labels to master.");
            }
        }
        
        System.out.println("LabelFileWatcher no longer running.  Shutting down this instance of Swarm Client.");
        System.exit(0);
    }
    
    public void stopLabelFileWatcher() {
        bRunning = false;
    }

}
