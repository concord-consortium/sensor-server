#!/bin/bash

cd "$( dirname "${BASH_SOURCE[0]}" )"
cd ..
export JAVA_HOME=$(pwd -P)/Resources/jdk
export PATH=$JAVA_HOME/Home/bin:$PATH

java -d32 -jar Java/sensor-server-0.0.1-SNAPSHOT.jar
