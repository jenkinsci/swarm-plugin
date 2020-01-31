# Swarm

[![Build Status](https://ci.jenkins.io/buildStatus/icon?job=Plugins/swarm-plugin/master)](https://ci.jenkins.io/job/Plugins/job/swarm-plugin/job/master/)
[![Jenkins Plugin](https://img.shields.io/jenkins/plugin/v/swarm.svg)](https://plugins.jenkins.io/swarm)
[![Jenkins Plugin Installs](https://img.shields.io/jenkins/plugin/i/swarm.svg)](https://plugins.jenkins.io/swarm)
[![Dependabot Status](https://api.dependabot.com/badges/status?host=github&repo=jenkinsci/swarm-plugin)](https://dependabot.com)

Swarm enables nodes to join a nearby Jenkins master, thereby forming an
ad-hoc cluster. This plugin makes it easier to scale a Jenkins cluster
by spinning up and tearing down new nodes.

This plugin consists of two pieces:

 1. A self-contained client that can join an existing Jenkins master.
 2. A plugin that needs to be installed on Jenkins master to accept
    Swarm clients.

With the Swarm client, a person who is willing to contribute some of his
computing power to the cluster just needs to run a virtual machine with
the Swarm client and the cluster gets an additional agent. Because the
Swarm client is running on a separate VM, there is no need to worry
about the builds/tests interfering with the host system or altering its
settings unexpectedly.

## Usage

 1. Install the Swarm plugin from the Update Center.
 2. Download [the Swarm
    client](https://repo.jenkins-ci.org/releases/org/jenkins-ci/plugins/swarm-client/).
 3. Run the Swarm client with `java -jar path/to/swarm-client.jar`.
    There are no required command-line options; run with the `-help`
    option to see the available options.

## Documentation

* [Changelog](CHANGELOG.md)
* [Logging](docs/logging.md)
* [Proxy Configuration](docs/proxy.md)

## Available Options

```
$ java -jar swarm-client.jar --help
 -candidateTag VAL                      : Show swarm candidate with tag only
 -deleteExistingClients                 : Deletes any existing slave with the
                                          same name. (default: false)
 -description VAL                       : Description to be put on the slave
 -disableClientsUniqueId                : Disables client's unique ID.
                                          (default: false)
 -disableSslVerification                : Disables SSL verification in the
                                          HttpClient. (default: false)
 -disableWorkDir                        : Disable Remoting Working Directory
                                          and run agent in legacy mode.
                                          (default: false)
 -e (--env)                             : An environment variable to be defined
                                          on this slave. It is specified as
                                          'key=value'. Multiple variables are
                                          allowed.
 -executors N                           : Number of executors (default: 8)
 -fsroot FILE                           : Directory where Jenkins places files
                                          (default: .)
 -help (--help)                         : Show the help screen (default: false)
 -labels VAL                            : Whitespace-separated list of labels
                                          to be assigned for this slave.
                                          Multiple options are allowed.
 -labelsFile VAL                        : File location with space delimited
                                          list of labels.  If the file changes,
                                          the client is restarted.
 -master VAL                            : The complete target Jenkins URL like
                                          'http://server:8080/jenkins/'.
 -maxRetryInterval N                    : Max time to wait before retry in
                                          seconds. Default is 60 seconds.
                                          (default: 60)
 -mode MODE                             : The mode controlling how Jenkins
                                          allocates jobs to slaves. Can be
                                          either 'normal' (utilize this slave
                                          as much as possible) or 'exclusive'
                                          (leave this machine for tied jobs
                                          only). Default is 'normal'. (default:
                                          normal)
 -name VAL                              : Name of the slave
 -noRetryAfterConnected                 : Do not retry if a successful
                                          connection gets closed. (default:
                                          false)
 -password VAL                          : The Jenkins user password
 -passwordEnvVariable VAL               : Environment variable that the
                                          password is stored in
 -passwordFile VAL                      : File containing the Jenkins user
                                          password
 -pidFile VAL                           : File to write PID to. The client will
                                          refuse to start if this file exists
                                          and the previous process is still
                                          running.
 -retry N                               : Number of retries before giving up.
                                          Unlimited if not specified. (default:
                                          -1)
 -retryBackOffStrategy RETRY_BACK_OFF_S : The mode controlling retry wait time.
 TRATEGY                                  Can be either 'none' (use same
                                          interval between retries) or 'linear'
                                          (increase wait time before each retry
                                          up to maxRetryInterval) or
                                          'exponential' (double wait interval
                                          on each retry up to maxRetryInterval).
                                          Default is 'none'. (default: NONE)
 -retryInterval N                       : Time to wait before retry in seconds.
                                          Default is 10 seconds. (default: 10)
 -showHostName (--showHostName)         : Show hostname instead of IP address
                                          (default: false)
 -sslFingerprints VAL                   : Whitespace-separated list of accepted
                                          certificate fingerprints
                                          (SHA-256/Hex), otherwise system
                                          truststore will be used. No
                                          revocation, expiration or not yet
                                          valid check will be performed for
                                          custom fingerprints! Multiple options
                                          are allowed. (default: )
 -t (--toolLocation)                    : A tool location to be defined on this
                                          slave. It is specified as
                                          'toolName=location'
 -tunnel VAL                            : Connect to the specified host and
                                          port, instead of connecting directly
                                          to Jenkins. Useful when connection to
                                          Jenkins needs to be tunneled. Can be
                                          also HOST: or :PORT, in which case
                                          the missing portion will be
                                          auto-configured like the default
                                          behavior
 -username VAL                          : The Jenkins username for
                                          authentication
```
