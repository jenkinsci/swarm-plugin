package hudson.plugins.swarm;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static hudson.plugins.swarm.RetryBackOffStrategy.EXPONENTIAL;
import static hudson.plugins.swarm.RetryBackOffStrategy.LINEAR;
import static hudson.plugins.swarm.RetryBackOffStrategy.NONE;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;

public class ClientTest {

    @Test
    public void should_not_retry_more_than_specified() {
        Options options = givenBackOff(NONE);
        // one try
        options.retry = 1;
        runAndVerify(options, "Exited with status 1 after 0 seconds");
        // a few tries
        options.retry = 5;
        runAndVerify(options, "Exited with status 1 after 40 seconds");
    }

    @Test
    public void should_run_with_web_socket() {
        Options options = givenBackOff(NONE);
        options.webSocket = true;
        options.retry = -1;
        runAndVerify(options, "Running long enough");
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
        runAndVerify(options, "Exited with status 1 after 90 seconds");
    }

    @Test
    public void should_run_with_linear_backoff() {
        Options options = givenBackOff(LINEAR);
        runAndVerify(options, "Exited with status 1 after 450 seconds");
    }

    @Test
    public void should_run_with_exponential_backoff() {
        Options options = givenBackOff(EXPONENTIAL);
        runAndVerify(options, "Exited with status 1 after 750 seconds");
    }

    @Test
    public void load_options_from_yaml() {
        final String yamlString = "url: http://localhost:8080/jenkins\n" +
                "name: agent-name-0\n" +
                "description: Configured from yml\n" +
                "executors: 3\n" +
                "labels:\n" +
                "  - label-a\n" +
                "  - label-b\n" +
                "  - label-c\n" +
                "webSocket: true\n" +
                "disableClientsUniqueId: true\n" +
                "mode: exclusive\n" +
                "retry: 0\n" +
                "toolLocations:\n" +
                "  tool-a: /tool/path/a\n" +
                "  tool-b: /tool/path/b\n";
        final Options options = Client.loadFromConfig(new ByteArrayInputStream(yamlString.getBytes(StandardCharsets.UTF_8)));
        assertThat(options.url, equalTo("http://localhost:8080/jenkins"));
        assertThat(options.description, equalTo("Configured from yml"));
        assertThat(options.executors, equalTo(3));
        assertThat(options.labels, hasItems("label-a", "label-b", "label-c"));
        assertThat(options.webSocket, equalTo(true));
        assertThat(options.disableClientsUniqueId, equalTo(true));
        assertThat(options.mode, equalTo(ModeOptionHandler.EXCLUSIVE));
        assertThat(options.retry, equalTo(0));
        assertThat(options.toolLocations.get("tool-a"), equalTo("/tool/path/a"));
        assertThat(options.toolLocations.get("tool-b"), equalTo("/tool/path/b"));
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
            throw new IllegalStateException(
                    "Exited with status " + status + " after " + totalWaitTime + " seconds");
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
