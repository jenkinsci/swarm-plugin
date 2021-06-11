package hudson.plugins.swarm;

import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class YamlConfigTest {
    @Test
    public void loadOptionsFromYaml() throws ConfigurationException {
        final String yamlString =
                "url: http://localhost:8080/jenkins\n"
                        + "name: agent-name-0\n"
                        + "description: Configured from yml\n"
                        + "executors: 3\n"
                        + "labels:\n"
                        + "  - label-a\n"
                        + "  - label-b\n"
                        + "  - label-c\n"
                        + "disableClientsUniqueId: true\n"
                        + "mode: exclusive\n"
                        + "failIfWorkDirIsMissing: false\n"
                        + "deleteExistingClients: true\n"
                        + "labelsFile: ~/l.conf\n"
                        + "pidFile: ~/s.pid\n"
                        + "prometheusPort: 112233\n";

        final Options options = loadYaml(yamlString);
        assertThat(options.url, equalTo("http://localhost:8080/jenkins"));
        assertThat(options.description, equalTo("Configured from yml"));
        assertThat(options.executors, equalTo(3));
        assertThat(options.labels, hasItems("label-a", "label-b", "label-c"));
        assertThat(options.disableClientsUniqueId, equalTo(true));
        assertThat(options.mode, equalTo(ModeOptionHandler.EXCLUSIVE));
        assertThat(options.failIfWorkDirIsMissing, equalTo(false));
        assertThat(options.deleteExistingClients, equalTo(true));
        assertThat(options.labelsFile, equalTo("~/l.conf"));
        assertThat(options.pidFile, equalTo("~/s.pid"));
        assertThat(options.prometheusPort, equalTo(112233));
    }

    @Test
    public void defaultValues() throws ConfigurationException {
        final Options defaultOptions = new Options();
        defaultOptions.url = "ignore";
        final Options options = loadYaml("url: ignore\n");

        Arrays.stream(Options.class.getDeclaredFields())
                .forEach(
                        f -> {
                            try {
                                assertThat(
                                        "Field " + f.getName(),
                                        f.get(options),
                                        equalTo(f.get(defaultOptions)));
                            } catch (IllegalAccessException e) {
                                fail(e.getMessage());
                            }
                        });
    }

    @Test
    public void toolLocationOption() throws ConfigurationException {
        final String yamlString =
                "url: http://localhost:8080/jenkins\n"
                        + "toolLocations:\n"
                        + "  tool-a: /tool/path/a\n"
                        + "  tool-b: /tool/path/b\n";

        final Options options = loadYaml(yamlString);
        assertThat(options.toolLocations.size(), equalTo(2));
        assertThat(options.toolLocations.get("tool-a"), equalTo("/tool/path/a"));
        assertThat(options.toolLocations.get("tool-b"), equalTo("/tool/path/b"));
    }

    @Test
    public void errorHandlingOptions() throws ConfigurationException {
        final String yamlString =
                "url: http://localhost:8080/jenkins\n"
                        + "retry: 0\n"
                        + "noRetryAfterConnected: true\n"
                        + "retryInterval: 12\n"
                        + "maxRetryInterval: 9\n";

        final Options options = loadYaml(yamlString);
        assertThat(options.url, equalTo("http://localhost:8080/jenkins"));
        assertThat(options.retry, equalTo(0));
        assertThat(options.failIfWorkDirIsMissing, equalTo(false));
        assertThat(options.noRetryAfterConnected, equalTo(true));
        assertThat(options.retryInterval, equalTo(12));
        assertThat(options.maxRetryInterval, equalTo(9));
    }

    @Test
    public void environmentVariablesOption() throws ConfigurationException {
        final String yamlString =
                "url: http://localhost:8080/jenkins\n"
                        + "environmentVariables:\n"
                        + "  ENV_1: env#1\n"
                        + "  ENV_2: env#2\n";

        final Options options = loadYaml(yamlString);
        assertThat(options.environmentVariables.size(), equalTo(2));
        assertThat(options.environmentVariables.get("ENV_1"), equalTo("env#1"));
        assertThat(options.environmentVariables.get("ENV_2"), equalTo("env#2"));
    }

    @Test
    public void authenticationOptions() throws ConfigurationException {
        final String yamlString =
                "url: http://localhost:8080/jenkins\n"
                        + "disableSslVerification: false\n"
                        + "sslFingerprints: fp0 fp1 fp2\n"
                        + "username: swarm-user-name\n"
                        + "passwordEnvVariable: PASS_ENV\n"
                        + "passwordFile: ~/p.conf\n";

        final Options options = loadYaml(yamlString);
        assertThat(options.url, equalTo("http://localhost:8080/jenkins"));
        assertThat(options.disableSslVerification, equalTo(false));
        assertThat(options.sslFingerprints, equalTo("fp0 fp1 fp2"));
        assertThat(options.username, equalTo("swarm-user-name"));
        assertThat(options.passwordEnvVariable, equalTo("PASS_ENV"));
        assertThat(options.passwordFile, equalTo("~/p.conf"));
    }

    @Test
    public void webSocketOption() throws ConfigurationException {
        final String yamlString = "url: http://localhost:8080/jenkins\n" + "webSocket: true\n";

        final Options options = loadYaml(yamlString);
        assertThat(options.webSocket, equalTo(true));
    }

    @Test
    public void webSocketHeadersOption() throws ConfigurationException {
        final String yamlString =
                "url: http://localhost:8080/jenkins\n"
                        + "webSocket: true\n"
                        + "webSocketHeaders:\n"
                        + "  WS_HEADER_0: WS_VALUE_0\n"
                        + "  WS_HEADER_1: WS_VALUE_1\n";

        final Options options = loadYaml(yamlString);
        assertThat(options.webSocketHeaders.size(), equalTo(2));
        assertThat(options.webSocketHeaders.get("WS_HEADER_0"), equalTo("WS_VALUE_0"));
        assertThat(options.webSocketHeaders.get("WS_HEADER_1"), equalTo("WS_VALUE_1"));
    }

    @Test
    public void webSocketHeadersFailsIfWebSocketOptionIsNotSet() {
        final String yamlString =
                "url: http://localhost:8080/jenkins\n"
                        + "webSocketHeaders:\n"
                        + "  WS_HEADER_0: WS_VALUE_0\n"
                        + "  WS_HEADER_1: WS_VALUE_1\n";

        final Throwable ex = assertThrows(ConfigurationException.class, () -> loadYaml(yamlString));
        assertThat(
                ex.getMessage(),
                allOf(containsString("webSocketHeaders"), containsString("webSocket")));
    }

    @Test
    public void retryBackOffStrategyOptionValues() throws ConfigurationException {
        final Options none = loadYaml("url: ignore\nretryBackOffStrategy: NONE\n");
        assertThat(none.retryBackOffStrategy, equalTo(RetryBackOffStrategy.NONE));

        final Options linear = loadYaml("url: ignore\nretryBackOffStrategy: LINEAR\n");
        assertThat(linear.retryBackOffStrategy, equalTo(RetryBackOffStrategy.LINEAR));

        final Options exponential = loadYaml("url: ignore\nretryBackOffStrategy: EXPONENTIAL\n");
        assertThat(exponential.retryBackOffStrategy, equalTo(RetryBackOffStrategy.EXPONENTIAL));
    }

    @Test
    public void modeOptionValues() throws ConfigurationException {
        final Options normal = loadYaml("url: ignore\nmode: " + ModeOptionHandler.NORMAL + "\n");
        assertThat(normal.mode, equalTo(ModeOptionHandler.NORMAL));
        final Options exclusive =
                loadYaml("url: ignore\nmode: " + ModeOptionHandler.EXCLUSIVE + "\n");
        assertThat(exclusive.mode, equalTo(ModeOptionHandler.EXCLUSIVE));
    }

    @Test
    public void failIfModeHasInvalidValue() {
        final Throwable ex =
                assertThrows(
                        ConfigurationException.class,
                        () -> loadYaml("url: ignore\nmode: invalid\n"));
        assertThat(ex.getMessage(), containsString("mode"));
    }

    @Test
    public void enumsAreCaseInsensitive() throws ConfigurationException {
        final Options upperCase = loadYaml("url: ignore\nretryBackOffStrategy: LINEAR\n");
        assertThat(upperCase.retryBackOffStrategy, equalTo(RetryBackOffStrategy.LINEAR));

        final Options lowerCase = loadYaml("url: ignore\nretryBackOffStrategy: exponential\n");
        assertThat(lowerCase.retryBackOffStrategy, equalTo(RetryBackOffStrategy.EXPONENTIAL));

        final Options mixedCase = loadYaml("url: ignore\nretryBackOffStrategy: eXPONenTiaL\n");
        assertThat(mixedCase.retryBackOffStrategy, equalTo(RetryBackOffStrategy.EXPONENTIAL));
    }

    @Test
    public void failsIfDeprecatedOptionIsUsed() {
        final Throwable ex =
                assertThrows(
                        ConfigurationException.class, () -> loadYaml("password: should-fail\n"));
        assertThat(ex.getMessage(), containsString("password"));
    }

    @Test
    public void failsOnConflictingOptions() {
        final Throwable ex =
                assertThrows(
                        ConfigurationException.class,
                        () ->
                                loadYaml(
                                        "url: ignore\n"
                                                + "webSocket: true\n"
                                                + "tunnel: excluded-by.ws:123\n"));
        assertThat(ex.getMessage(), allOf(containsString("webSocket"), containsString("tunnel")));
    }

    @Test
    public void failsOnConfigOption() {
        final Throwable ex =
                assertThrows(
                        ConfigurationException.class,
                        () -> loadYaml("url: ignore\nconfig: no-other-config-allowed.yml\n"));
        assertThat(ex.getMessage(), containsString("config"));
    }

    @Test
    public void failsOnHelpOption() {
        final Throwable ex =
                assertThrows(
                        ConfigurationException.class, () -> loadYaml("url: ignore\nhelp: true\n"));
        assertThat(ex.getMessage(), containsString("help"));
    }

    private Options loadYaml(String yamlString) throws ConfigurationException {
        return new YamlConfig()
                .loadOptions(new ByteArrayInputStream(yamlString.getBytes(StandardCharsets.UTF_8)));
    }
}
