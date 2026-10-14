#!/usr/bin/env bash
# Compiles and runs :core unit tests WITHOUT Gradle/Maven repositories.
# Use only in sandboxes where Maven Central / Google Maven are blocked; normal
# development should run `./gradlew :core:test`.
#
# Needs: a JDK 17+, a Kotlin 2.0.x compiler distribution (downloaded from GitHub
# releases if KOTLINC_HOME is unset) and JUnit 4 (taken from a local Gradle install).
# Tests use kotlin.test annotations, so they run unchanged on JUnit 4 here and JUnit 5 in Gradle.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${WORK_DIR:-${TMPDIR:-/tmp}/modhub-core-test}"
mkdir -p "$WORK"

if [[ -z "${KOTLINC_HOME:-}" ]]; then
  KOTLINC_HOME="$WORK/kotlinc"
  if [[ ! -x "$KOTLINC_HOME/bin/kotlinc" ]]; then
    curl -sSL -o "$WORK/kotlin-compiler.zip" \
      https://github.com/JetBrains/kotlin/releases/download/v2.0.21/kotlin-compiler-2.0.21.zip
    (cd "$WORK" && unzip -q -o kotlin-compiler.zip)
  fi
fi
GRADLE_LIB="${GRADLE_LIB:-$(dirname "$(readlink -f "$(command -v gradle)")")/../lib}"
JUNIT4="$(ls "$GRADLE_LIB"/junit-4*.jar | head -1)"
HAMCREST="$(ls "$GRADLE_LIB"/hamcrest-core-*.jar | head -1)"
L="$KOTLINC_HOME/lib"

rm -rf "$WORK/main" "$WORK/test"
"$KOTLINC_HOME/bin/kotlinc" -jvm-target 17 -cp "$L/kotlinx-coroutines-core-jvm.jar" \
  -d "$WORK/main" $(find "$ROOT/core/src/main" -name '*.kt')
"$KOTLINC_HOME/bin/kotlinc" -jvm-target 17 \
  -cp "$WORK/main:$L/kotlinx-coroutines-core-jvm.jar:$L/kotlin-test.jar:$L/kotlin-test-junit.jar:$JUNIT4" \
  -d "$WORK/test" $(find "$ROOT/core/src/test" -name '*.kt')

CLASSES=$(cd "$WORK/test" && find . -name '*Test.class' ! -name 'TempDirTest.class' ! -name '*$*' \
  | sed 's|^\./||; s|\.class$||; s|/|.|g')
java -cp "$WORK/main:$WORK/test:$L/kotlin-stdlib.jar:$L/kotlinx-coroutines-core-jvm.jar:$L/kotlin-test.jar:$L/kotlin-test-junit.jar:$JUNIT4:$HAMCREST" \
  org.junit.runner.JUnitCore $CLASSES
