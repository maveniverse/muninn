#!/bin/sh
# Offline demo: builds the extension and a stand-in compile/test plugin with javac, installs
# both into a private local repository, and runs the demo reactor with agent output enabled.
# The stand-in plugin reproduces maven-compiler-plugin's exception text and surefire's report
# format and failure message, so no access to Maven Central is needed.
set -e
IT="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$IT")"
WORK="$IT/work"
MAVEN_HOME="${MAVEN_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v mvn)")")")}"
rm -rf "$WORK" && mkdir -p "$WORK/repo" "$WORK/remote"

"$ROOT/build.sh"
EXT="$WORK/repo/eu/maveniverse/maven/muninn/extension3/0.1.0-SNAPSHOT"
mkdir -p "$EXT"
cp "$ROOT/build/extension3-0.1.0-SNAPSHOT.jar" "$EXT/"
printf '<project><modelVersion>4.0.0</modelVersion><groupId>eu.maveniverse.maven.muninn</groupId><artifactId>extension3</artifactId><version>0.1.0-SNAPSHOT</version></project>\n' \
  > "$EXT/extension3-0.1.0-SNAPSHOT.pom"

PLG="$WORK/repo/org/example/standin/standin-maven-plugin/1.0"
mkdir -p "$PLG" "$WORK/plugin-classes"
javac --release 11 -cp "$MAVEN_HOME/lib/*" -d "$WORK/plugin-classes" $(find "$IT/standin-plugin/src" -name '*.java')
cp -r "$IT/standin-plugin/res/"* "$WORK/plugin-classes/"
(cd "$WORK/plugin-classes" && jar cf "$PLG/standin-maven-plugin-1.0.jar" .)
cp "$IT/standin-plugin/pom.xml" "$PLG/standin-maven-plugin-1.0.pom"

cat > "$WORK/settings.xml" <<XML
<settings>
  <localRepository>$WORK/repo</localRepository>
  <mirrors><mirror><id>corp-nexus</id><mirrorOf>*</mirrorOf><url>file://$WORK/remote</url></mirror></mirrors>
</settings>
XML

cd "$IT/demo"
rm -rf target */target
echo "--- mvn -fae verify -Dmuninn.output=json"
mvn -B -s "$WORK/settings.xml" -fae verify -Dmuninn.output=json || echo "(exit code $?)"
