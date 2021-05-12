package hudson.plugins.swarm;

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
                        + "webSocket: true\n"
                        + "disableClientsUniqueId: true\n"
                        + "mode: exclusive\n"
                        + "retry: 0\n"
                        + "failIfWorkDirIsMissing: false\n"
                        + "toolLocations:\n"
                        + "  tool-a: /tool/path/a\n"
                        + "  tool-b: /tool/path/b\n"
                        + "noRetryAfterConnected: true\n"
                        + "environmentVariables:\n"
                        + "  ENV_1: env#1\n"
                        + "  ENV_2: env#2\n"
                        + "retryInterval: 12\n"
                        + "maxRetryInterval: 9\n"
                        + "disableSslVerification: false\n"
                        + "sslFingerprints: fp0 fp1 fp2\n"
                        + "deleteExistingClients: true\n"
                        + "username: swarm-user-name\n"
                        + "password: no_actual_value\n"
                        + "passwordEnvVariable: PASS_ENV\n"
                        + "passwordFile: ~/p.conf\n"
                        + "labelsFile: ~/l.conf\n"
                        + "pidFile: ~/s.pid\n"
                        + "prometheusPort: 112233\n";

        final Options options = loadYaml(yamlString);
        assertThat(options.url, equalTo("http://localhost:8080/jenkins"));
        assertThat(options.description, equalTo("Configured from yml"));
        assertThat(options.executors, equalTo(3));
        assertThat(options.labels, hasItems("label-a", "label-b", "label-c"));
        assertThat(options.webSocket, equalTo(true));
        assertThat(options.disableClientsUniqueId, equalTo(true));
        assertThat(options.mode, equalTo(ModeOptionHandler.EXCLUSIVE));
        assertThat(options.retry, equalTo(0));
        assertThat(options.failIfWorkDirIsMissing, equalTo(false));
        assertThat(options.toolLocations.size(), equalTo(2));
        assertThat(options.toolLocations.get("tool-a"), equalTo("/tool/path/a"));
        assertThat(options.toolLocations.get("tool-b"), equalTo("/tool/path/b"));
        assertThat(options.noRetryAfterConnected, equalTo(true));
        assertThat(options.environmentVariables.size(), equalTo(2));
        assertThat(options.environmentVariables.get("ENV_1"), equalTo("env#1"));
        assertThat(options.environmentVariables.get("ENV_2"), equalTo("env#2"));
        assertThat(options.retryInterval, equalTo(12));
        assertThat(options.maxRetryInterval, equalTo(9));
        assertThat(options.disableSslVerification, equalTo(false));
        assertThat(options.sslFingerprints, equalTo("fp0 fp1 fp2"));
        assertThat(options.deleteExistingClients, equalTo(true));
        assertThat(options.username, equalTo("swarm-user-name"));
        assertThat(options.passwordEnvVariable, equalTo("PASS_ENV"));
        assertThat(options.passwordFile, equalTo("~/p.conf"));
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
    public void failsIfRequiredOptionIsMissing() {
        final Throwable ex =
                assertThrows(ConfigurationException.class, () -> loadYaml("name: should-fail\n"));
        assertThat(ex.getMessage(), containsString("url"));
    }

    @Test
    public void failsOnWebSocketAndTunnelOption() {
        final Throwable ex =
                assertThrows(
                        ConfigurationException.class,
                        () ->
                                loadYaml(
                                        "url: ignore\n"
                                                + "webSocket: true\n"
                                                + "tunnel: excluded-by.ws:123\n"));
        assertThat(ex.getMessage(), containsString("tunnel"));
    }

    @Test
    public void failsOnDisableWorkDirAndWorkDirOption() {
        final Throwable ex =
                assertThrows(
                        ConfigurationException.class,
                        () -> loadYaml("url: ignore\ndisableWorkDir: true\nworkDir: /tmp/wd\n"));
        assertThat(ex.getMessage(), containsString("workDir"));
    }

    @Test
    public void failsOnDisableWorkDirAndInternalDirOption() {
        final Throwable ex =
                assertThrows(
                        ConfigurationException.class,
                        () ->
                                loadYaml(
                                        "url: ignore\n"
                                                + "disableWorkDir: true\n"
                                                + "internalDir: /tmp/id\n"));
        assertThat(ex.getMessage(), containsString("internalDir"));
    }

    @Test
    public void failsOnDisableWorkDirAndFailIfWorkDirIsMissingOption() {
        final Throwable ex =
                assertThrows(
                        ConfigurationException.class,
                        () ->
                                loadYaml(
                                        "url: ignore\n"
                                                + "disableWorkDir: true\n"
                                                + "failIfWorkDirIsMissing: true\n"));
        assertThat(ex.getMessage(), containsString("failIfWorkDirIsMissing"));
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
