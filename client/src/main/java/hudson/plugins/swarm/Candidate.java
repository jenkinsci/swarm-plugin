package hudson.plugins.swarm;

/**
 *
 */
public class Candidate {

    final String url;

    final String secret;

    Candidate(String url, String secret) {
        this.url = url;
        this.secret = secret;
    }

}
