package hudson.plugins.swarm;

import org.kohsuke.args4j.Option;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.Objects;

/** Reads {@link Options} from a YAML file. */
public class YamlConfig {
    private static final Options defaultOptions = new Options();
    private final Yaml yaml;

    public YamlConfig() {
        this.yaml = new Yaml(new Constructor(Options.class));
    }

    public Options loadOptions(InputStream inputStream) throws ConfigurationException {
        final Options options = yaml.loadAs(inputStream, Options.class);
        checkForbidden(options.config != null, "config");
        checkForbidden(options.password != null, "password");

        for (Field field : Options.class.getDeclaredFields()) {
            checkField(options, field);
        }

        if (!ModeOptionHandler.accepts(options.mode)) {
            throw new ConfigurationException("'mode' has an invalid value: '" + options.mode + "'");
        }

        return options;
    }

    private void checkForbidden(boolean hasValue, String name) throws ConfigurationException {
        if (hasValue) {
            throw new ConfigurationException("'" + name + "' is not allowed in configuration file");
        }
    }

    private boolean isSet(Options options, Field field)
            throws NoSuchFieldException, IllegalAccessException {
        final Object defaultValue =
                Options.class.getDeclaredField(field.getName()).get(defaultOptions);
        return !Objects.equals(field.get(options), defaultValue);
    }

    private void checkField(Options options, Field field) throws ConfigurationException {
        try {
            final Option annotation = field.getAnnotation(Option.class);

            if (annotation != null) {
                if (annotation.required() && !isSet(options, field)) {
                    throw new ConfigurationException("'" + field.getName() + "' is required");
                }

                if (annotation.help()) {
                    checkForbidden(isSet(options, field), field.getName());
                }

                checkForbids(options, field, annotation.forbids());
                checkDepends(options, field, annotation.depends());
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private void checkDepends(Options options, Field field, String[] depends)
            throws NoSuchFieldException, IllegalAccessException, ConfigurationException {
        for (String dependsOnOption : depends) {
            final Field dependsOn = fieldForOption(dependsOnOption);

            if (isSet(options, field) && !isSet(options, dependsOn)) {
                throw new ConfigurationException(
                        "'" + field.getName() + "' depends on '" + dependsOn.getName() + "'");
            }
        }
    }

    private void checkForbids(Options options, Field field, String[] forbids)
            throws NoSuchFieldException, IllegalAccessException, ConfigurationException {
        for (String forbidden : forbids) {
            final Field forbiddenField = fieldForOption(forbidden);

            if (isSet(options, field) && isSet(options, forbiddenField)) {
                throw new ConfigurationException(
                        "'"
                                + field.getName()
                                + "' can not be used with '"
                                + forbiddenField.getName()
                                + "'");
            }
        }
    }

    private Field fieldForOption(String option) throws NoSuchFieldException {
        return Options.class.getDeclaredField(option.replace("-", ""));
    }
}
