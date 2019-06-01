# Logging and Diagnostics

## Standard logging

[Jenkins uses `java.util.logging` for
logging](https://wiki.jenkins.io/display/JENKINS/Logging), including in
[Remoting](https://github.com/jenkinsci/remoting) and
[Swarm](https://github.com/jenkinsci/swarm-plugin). By default,
`java.util.logging` uses the configuration from
`$JAVA_HOME/jre/lib/logging.properties`. This is typically something like the
following:

```
handlers= java.util.logging.ConsoleHandler
.level= INFO
java.util.logging.ConsoleHandler.level = INFO
java.util.logging.ConsoleHandler.formatter = java.util.logging.SimpleFormatter
```

The default handler,
[`ConsoleHandler`](https://docs.oracle.com/javase/8/docs/api/java/util/logging/ConsoleHandler.html),
sends `INFO`-level log records to `System.err`, using
[`SimpleFormatter`](https://docs.oracle.com/javase/8/docs/api/java/util/logging/SimpleFormatter.html).

### Configuration

To get more detailed logs from the Swarm client, create a custom
`logging.properties` file and pass it in via the
`java.util.logging.config.file` property. This repository contains [a sample
verbose `logging.properties` file](../client/logging.properties). For example:

```
java \
	-Djava.util.logging.config.file='logging.properties' \
	-jar swarm-client.jar
```

For more information about the property file format, see the [Oracle
documentation](https://docs.oracle.com/cd/E19717-01/819-7753/6n9m71435/index.html)
and [this guide](http://tutorials.jenkov.com/java-logging/configuration.html).
