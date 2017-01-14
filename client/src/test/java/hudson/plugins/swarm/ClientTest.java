package hudson.plugins.swarm;

import org.junit.Test;

import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;

import static hudson.plugins.swarm.RetryBackOffStrategy.EXPONENTIAL;
import static hudson.plugins.swarm.RetryBackOffStrategy.LINEAR;
import static hudson.plugins.swarm.RetryBackOffStrategy.NONE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class ClientTest {

    @Test
    public void should_not_retry_more_than_specified() {
        Options options = givenBackOff(NONE);
        // one trie
        options.retry = 1;
        runAndVerify(options, "Exited with status -1 after 0 seconds");
        // a few tries
        options.retry = 5;
        runAndVerify(options, "Exited with status -1 after 40 seconds");
    }

    @Test
    public void should_keep_retrying_if_there_is_no_limit() {
        Options options = givenBackOff(NONE);
        options.retry = -1;
        runAndVerify(options, "Running long enough");
    }

    @Test
    public void should_run_with_no_backoff() {
        Options options = givenBackOff(NONE);
        runAndVerify(options, "Exited with status -1 after 90 seconds");
    }

    @Test
    public void should_run_with_linear_backoff() {
        Options options = givenBackOff(LINEAR);
        runAndVerify(options, "Exited with status -1 after 450 seconds");
    }

    @Test
    public void should_run_with_exponential_backoff() {
        Options options = givenBackOff(EXPONENTIAL);
        runAndVerify(options, "Exited with status -1 after 750 seconds");
    }

    private Options givenBackOff(RetryBackOffStrategy retryBackOffStrategy) {
        Options options = new Options();
        options.master = "http://localhost:8080";
        options.retryBackOffStrategy = retryBackOffStrategy;
        options.retry = 10;
        options.retryInterval = 10;
        options.maxRetryInterval = 120;
        return options;
    }

    private void runAndVerify(Options options, String expectedResult) {
        try {
            SwarmClient swarmClient = new DummySwarmClient(options);
            Client client = new Client(options);
            client.run(swarmClient);
            fail();
        } catch (IllegalStateException e) {
            assertEquals(expectedResult, e.getMessage());
        } catch (Exception e) {
            fail();
        }
    }

    class DummySwarmClient extends SwarmClient {

        int totalWaitTime;

        public DummySwarmClient(Options options) {
            super(options);
        }

        @Override
        public Candidate discoverFromMasterUrl() throws IOException, ParserConfigurationException, RetryException {
            throw new IOException("Unable to connect at the moment");
        }

        @Override
        public void exitWithStatus(int status) {
            throw new IllegalStateException("Exited with status " + status + " after " + totalWaitTime + " seconds");
        }

        @Override
        public void sleepSeconds(int waitTime) throws InterruptedException {
            totalWaitTime += waitTime;
            if (totalWaitTime > 1000) {
                throw new IllegalStateException("Running long enough");
            }
        }

    }

}