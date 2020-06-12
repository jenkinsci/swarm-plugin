package hudson.plugins.swarm;

import java.util.Arrays;
import java.util.List;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.OptionDef;
import org.kohsuke.args4j.spi.OneArgumentOptionHandler;
import org.kohsuke.args4j.spi.Setter;

/**
 * Parses possible node modes: can be either 'normal' or 'exclusive'.
 *
 * @author Timur Strekalov
 */
public class ModeOptionHandler extends OneArgumentOptionHandler<String> {

    public static final String NORMAL = "normal";
    public static final String EXCLUSIVE = "exclusive";

    private static final List<String> ACCEPTABLE_VALUES = Arrays.asList(NORMAL, EXCLUSIVE);

    public ModeOptionHandler(CmdLineParser parser, OptionDef option, Setter<? super String> setter) {
        super(parser, option, setter);
    }

    @Override
    public String parse(String argument) throws NumberFormatException, CmdLineException {
        int index = ACCEPTABLE_VALUES.indexOf(argument);
        if (index == -1) {
            throw new CmdLineException(owner, "Invalid mode", null);
        }

        return argument;
    }

    @Override
    public String getDefaultMetaVariable() {
        return "MODE";
    }

}
