package hudson.plugins.swarm;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;

import java.net.URL;
import org.junit.Test;

public class ClientTest {

    @Test
    public void should_not_retry_more_than_specified() {
        Options options = givenBackOff(RetryBackOffStrategy.NONE);
        // one try
        options.retry = 1;
        runAndVerify(options, "Exited with status 1 after 0 seconds");
        // a few tries
        options.retry = 5;
        runAndVerify(options, "Exited with status 1 after 40 seconds");
    }

    @Test
    public void should_run_with_web_socket() {
        Options options = givenBackOff(RetryBackOffStrategy.NONE);
        options.webSocket = true;
        options.retry = -1;
        runAndVerify(options, "Running long enough");
    }

    @Test
    public void should_keep_retrying_if_there_is_no_limit() {
        Options options = givenBackOff(RetryBackOffStrategy.NONE);
        options.retry = -1;
        runAndVerify(options, "Running long enough");
    }

    @Test
    public void should_run_with_no_backoff() {
        Options options = givenBackOff(RetryBackOffStrategy.NONE);
        runAndVerify(options, "Exited with status 1 after 90 seconds");
    }

    @Test
    public void should_run_with_linear_backoff() {
        Options options = givenBackOff(RetryBackOffStrategy.LINEAR);
        runAndVerify(options, "Exited with status 1 after 450 seconds");
    }

    @Test
    public void should_run_with_exponential_backoff() {
        Options options = givenBackOff(RetryBackOffStrategy.EXPONENTIAL);
        runAndVerify(options, "Exited with status 1 after 750 seconds");
    }

    private Options givenBackOff(RetryBackOffStrategy retryBackOffStrategy) {
        Options options = new Options();
        options.url = "http://localhost:8080";
        options.retryBackOffStrategy = retryBackOffStrategy;
        options.retry = 10;
        options.retryInterval = 10;
        options.maxRetryInterval = 120;
        return options;
    }

    private void runAndVerify(Options options, String expectedResult) {
        SwarmClient swarmClient = new DummySwarmClient(options);
        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> Client.run(swarmClient, options));
        assertThat(thrown.getMessage(), containsString(expectedResult));
    }

    private static class DummySwarmClient extends SwarmClient {

        private int totalWaitTime;

        DummySwarmClient(Options options) {
            super(options);
        }

        @Override
        protected void createSwarmAgent(URL url) throws RetryException {
            throw new RetryException("try again");
        }

        @Override
        public void exitWithStatus(int status) {
            throw new IllegalStateException("Exited with status " + status + " after " + totalWaitTime + " seconds");
        }

        @Override
        public void sleepSeconds(int waitTime) {
            totalWaitTime += waitTime;
            if (totalWaitTime > 1000) {
                throw new IllegalStateException("Running long enough");
            }
        }
    }
}
