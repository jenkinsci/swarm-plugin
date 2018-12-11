package hudson.plugins.swarm;

import java.util.logging.Logger;

public class Candidate {

    private static final Logger logger = Logger.getLogger(Candidate.class.getPackage().getName());

    final String url;
    final String secret;

    public Candidate(String url, String secret) {
        this.url = url;
        this.secret = secret;

        logger.fine("Candidate constructed with url: " + url + ", " + "secret: " + secret);
    }

    public String getURL() {
        return url;
    }

    public String getSecret() {
        return secret;
    }
}
