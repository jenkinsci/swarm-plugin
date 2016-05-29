package hudson.plugins.swarm;

import java.util.logging.*;

/**
 *
 */
public class Candidate {

    final String url;

    final String secret;
    private static final Logger logger =  Logger.getLogger(Candidate.class.getPackage().getName());

    public Candidate(String url, String secret) {
        this.url = url;
        this.secret = secret;
    
        logger.fine("Candidate constructed with url: " + url + ", " + "secret: " + secret);
    }

}
