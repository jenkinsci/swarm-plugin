#!/bin/bash

/usr/bin/curl -X POST -i -F file=@./plugin/target/swarm.hpi http://localhost:8080/pluginManager/uploadPlugin
