package hudson.plugins.swarm;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.InputStream;
import java.util.Arrays;
import java.util.List;

/**
 * Reads {@link Options} from a YAML file.
 *
 * <p>The keys are equal the field names of {@link Options}, except {@code help} and {@code config}
 * which aren't allowed.
 */
public class YamlConfig {
    private final Yaml yaml;

    public YamlConfig() {
        this.yaml = new Yaml(new Constructor(Options.class));
    }

    public Options loadOptions(InputStream inputStream) throws ConfigurationException {
        final Options options = yaml.loadAs(inputStream, Options.class);
        required(Entry.of(options.url, "url"));
        forbidden(Entry.of(options.config, "config"));
        forbidden(Entry.of(options.help, "help"));
        excluding(Entry.of(options.webSocket, "webSocket"), Entry.of(options.tunnel, "tunnel"));
        excluding(
                Entry.of(options.disableWorkDir, "disableWorkDir"),
                Entry.of(options.workDir, "workDir"));
        excluding(
                Entry.of(options.disableWorkDir, "disableWorkDir"),
                Entry.of(options.internalDir, "internalDir"));
        excluding(
                Entry.of(options.disableWorkDir, "disableWorkDir"),
                Entry.of(options.failIfWorkDirIsMissing, "failIfWorkDirIsMissing"));
        hasAllowedValue(
                Entry.of(options.mode, "mode"),
                Arrays.asList(ModeOptionHandler.NORMAL, ModeOptionHandler.EXCLUSIVE));
        return options;
    }

    private <T> void required(Entry<T> entry) throws ConfigurationException {
        if (!entry.set) {
            throw new ConfigurationException("'" + entry.name + "' is required");
        }
    }

    private <T> void forbidden(Entry<T> entry) throws ConfigurationException {
        if (entry.set) {
            throw new ConfigurationException(
                    "'" + entry.name + "' is not allowed in configuration file");
        }
    }

    private <T, U> void excluding(Entry<T> entryA, Entry<U> entryB) throws ConfigurationException {
        if (entryA.set && entryB.set) {
            throw new ConfigurationException(
                    "'" + entryA.name + "' can not be used with '" + entryB.name + "'");
        }
    }

    private <T> void hasAllowedValue(Entry<T> entry, List<T> allowed)
            throws ConfigurationException {
        if (!allowed.contains(entry.value)) {
            throw new ConfigurationException(
                    "'" + entry.name + "' has an invalid value: '" + entry.value + "'");
        }
    }

    private static class Entry<T> {
        final T value;
        final String name;
        final boolean set;

        private Entry(T value, String name, boolean set) {
            this.value = value;
            this.name = name;
            this.set = set;
        }

        public static <T> Entry<T> of(T value, String name) {
            return new Entry<>(value, name, value != null);
        }

        static Entry<Boolean> of(boolean value, String name) {
            return new Entry<>(value, name, value);
        }
    }
}
