#!/usr/bin/env bash
# Compiles and runs the pure-JVM devtools unit tests (.NET assembly store reader, LZ4 block
# decoder, ELF payload extraction; app/src/{main,test}/kotlin/com/modhub/app/devtools/assemblystore)
# WITHOUT Gradle/Maven repositories. Use only in sandboxes where Maven Central / Google Maven are
# blocked; normal development should run `./gradlew :app:testDebugUnitTest`.
#
# That package has no Android or library dependencies (Kotlin stdlib only), so it is compiled on
# its own, without the rest of :app. Tests use kotlin.test annotations: JUnit 4 here, JUnit 5 in Gradle.
#
# Needs: a JDK 17+, a Kotlin 2.0.x compiler (downloaded from GitHub releases if
# KOTLINC_HOME is unset) and JUnit 4 + hamcrest (taken from a local Gradle install).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${WORK_DIR:-${TMPDIR:-/tmp}/modhub-devtools-test}"
PKG="com/modhub/app/devtools/assemblystore"
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

# Guard: the package must stay pure JVM so it can be tested here.
if grep -rnE '^import (android|androidx|dagger|javax\.inject|kotlinx)\.' "$ROOT/app/src/main/kotlin/$PKG"; then
  echo "error: $PKG must not depend on Android or libraries (see imports above)" >&2
  exit 1
fi

rm -rf "$WORK/main" "$WORK/test"
"$KOTLINC_HOME/bin/kotlinc" -jvm-target 17 -d "$WORK/main" \
  $(find "$ROOT/app/src/main/kotlin/$PKG" -name '*.kt')
"$KOTLINC_HOME/bin/kotlinc" -jvm-target 17 -Xfriend-paths="$WORK/main" \
  -cp "$WORK/main:$L/kotlin-test.jar:$L/kotlin-test-junit.jar:$JUNIT4" \
  -d "$WORK/test" $(find "$ROOT/app/src/test/kotlin/$PKG" -name '*.kt')

CLASSES=$(cd "$WORK/test" && find . -name '*Test.class' ! -name '*$*' \
  | sed 's|^\./||; s|\.class$||; s|/|.|g')
java -cp "$WORK/main:$WORK/test:$L/kotlin-stdlib.jar:$L/kotlin-test.jar:$L/kotlin-test-junit.jar:$JUNIT4:$HAMCREST" \
  org.junit.runner.JUnitCore $CLASSES
