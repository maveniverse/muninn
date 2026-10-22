#!/bin/sh
# Builds the extension jar without Maven Central (javac against a local Maven install).
# With network access, `mvn install` on pom.xml does the same.
set -e
cd "$(dirname "$0")"
MAVEN_HOME="${MAVEN_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v mvn)")")")}"
rm -rf build && mkdir -p build/classes
javac --release 8 -Xlint:-options -cp "$MAVEN_HOME/lib/*" -d build/classes $(find extension3/src/main/java -name '*.java')
# The Maven build generates the Sisu index with sisu-maven-plugin; here we write it by hand.
mkdir -p build/classes/META-INF/sisu
echo eu.maveniverse.maven.muninn.extension3.AgentOutputSpy > build/classes/META-INF/sisu/javax.inject.Named
(cd build/classes && jar cf ../extension3-0.1.0-SNAPSHOT.jar .)
echo "built build/extension3-0.1.0-SNAPSHOT.jar"
