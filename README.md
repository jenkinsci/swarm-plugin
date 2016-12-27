swarm-plugin
============

Jenkins Swarm plugin for self organizing slave nodes. 

[![Build Status](https://jenkins.ci.cloudbees.com/buildStatus/icon?job=plugins/swarm-plugin)](https://jenkins.ci.cloudbees.com/job/plugins/swarm-plugin)

This plugin enables slaves to auto-discover a nearby Jenkins master and
join it automatically, thereby forming an ad-hoc cluster. Swarm makes it
easier to auto scale a Jenkins cluster by spinning up and tearing down
new slave nodes since no manual intervention is required to make them
join or leave the cluster.

More documentation available on the Jenkins wiki:

https://wiki.jenkins-ci.org/display/JENKINS/Swarm+Plugin

(Sending this pull request to ask the following question to the author as there is no other way I could fine (the issues option for the repository is disabled)
Qn: The releases section of the repository has 3.0 as the last release, while the releases at https://repo.jenkins-ci.org/releases/org/jenkins-ci/plugins/swarm-client/ has only upto 2.2. Is there any other location where I should be checking for the release?
