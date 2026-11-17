#!/bin/bash
# Host tests: the case protocol (Case.java and WriteBudget.java compiled
# against tests/stubs) and the LAN peer.  Needs a JDK 21 and python3.
set -euo pipefail
H=$(cd "$(dirname "$0")/.." && pwd)
JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}
O=$(mktemp -d)
trap 'rm -rf "$O"' EXIT
S=$H/app/src/org/neutrino/afdo/workload
"$JAVA_HOME/bin/javac" -Xlint:all -Werror -d "$O" $(find "$H/tests/stubs" "$H/tests/java" -name '*.java') "$S/Case.java" "$S/WriteBudget.java"
"$JAVA_HOME/bin/java" -cp "$O" org.neutrino.afdo.workload.CaseTest
python3 -I "$H/tests/test_peer.py"
