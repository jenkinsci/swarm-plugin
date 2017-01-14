package hudson.plugins.swarm;

import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.OptionDef;
import org.kohsuke.args4j.spi.EnumOptionHandler;
import org.kohsuke.args4j.spi.Setter;

public class RetryBackOffStrategyOptionHandler extends EnumOptionHandler<RetryBackOffStrategy> {

    public RetryBackOffStrategyOptionHandler(CmdLineParser parser, OptionDef option, Setter<? super RetryBackOffStrategy> setter) {
        super(parser, option, setter, RetryBackOffStrategy.class);
    }

    @Override
    public String getDefaultMetaVariable() {
        return "RETRY_BACK_OFF_STRATEGY";
    }

}
