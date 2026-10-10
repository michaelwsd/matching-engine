#!/usr/bin/env bash
#
# Builds and runs the market simulation.
#
# Exists because the simulation redraws the order book in place using ANSI cursor codes,
# which need a real terminal. IntelliJ's Run console is append-only and cannot do that, so
# the program detects it and falls back to printing a snapshot every couple of seconds.
# Running this from a terminal - including IntelliJ's terminal panel, cmd J - gets the live
# view. The alternative is to tick "Emulate terminal in output console" in the IntelliJ run
# configuration, which gives the green arrow a pty and works just as well.
#
# Usage:  ./run.sh                       the simulation
#         ./run.sh -Dsimulation.ansi=false   force the plain, scrolling output
#
set -euo pipefail
cd "$(dirname "$0")"

# Maven's own internals call a deprecated sun.misc.Unsafe method, which JDK 25 warns about
# three times on every invocation. The warning is about Maven, not this project. Allowing the
# access explicitly silences it without hiding anything we would want to see.
export MAVEN_OPTS="${MAVEN_OPTS:-} --sun-misc-unsafe-memory-access=allow"

CLASSPATH_FILE=target/cp.txt

# Resolving the dependency jars is slow and only changes when pom.xml does, so cache it.
if [ ! -f "$CLASSPATH_FILE" ] || [ pom.xml -nt "$CLASSPATH_FILE" ]; then
    echo "resolving dependencies..."
    ./mvnw -q compile dependency:build-classpath -Dmdep.outputFile="$CLASSPATH_FILE"
else
    ./mvnw -q compile
fi

exec java "$@" -cp "target/classes:$(cat "$CLASSPATH_FILE")" com.mwang.Main
