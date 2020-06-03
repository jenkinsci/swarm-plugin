package hudson.plugins.swarm;

import java.util.logging.Logger;

public class Candidate {

    private static final Logger logger = Logger.getLogger(Candidate.class.getPackage().getName());

    final String url;

    public Candidate(String url) {
        this.url = url;

        logger.fine("Candidate constructed with url: " + url);
    }

    public String getURL() {
        return url;
    }
}
