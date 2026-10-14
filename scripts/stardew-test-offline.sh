#!/usr/bin/env bash
# Compiles and runs :game-stardew unit tests WITHOUT Gradle/Maven repositories.
# Use only in sandboxes where Maven Central / Google Maven are blocked; normal
# development should run `./gradlew :game-stardew:testDebugUnitTest`.
#
# Compiles :core main sources + game-stardew main sources EXCEPT the Hilt module
# (di/, needs Dagger), then the tests, and runs them on JUnit 4 (kotlin.test annotations
# map to JUnit 4 here and JUnit 5 under Gradle). The plugin has no kotlinx-serialization
# dependency (it uses its own TolerantJson), so only the Kotlin distribution is needed.
#
# Needs: a JDK 17+, a Kotlin 2.0.x compiler (downloaded from GitHub releases if
# KOTLINC_HOME is unset) and JUnit 4 + hamcrest (taken from a local Gradle install).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${WORK_DIR:-${TMPDIR:-/tmp}/modhub-stardew-test}"
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
CORO="$L/kotlinx-coroutines-core-jvm.jar"

rm -rf "$WORK/main" "$WORK/test"
"$KOTLINC_HOME/bin/kotlinc" -jvm-target 17 -cp "$CORO" -d "$WORK/main" \
  $(find "$ROOT/core/src/main" -name '*.kt') \
  $(find "$ROOT/game-stardew/src/main" -name '*.kt' ! -path '*/di/*')
"$KOTLINC_HOME/bin/kotlinc" -jvm-target 17 -Xfriend-paths="$WORK/main" \
  -cp "$WORK/main:$CORO:$L/kotlin-test.jar:$L/kotlin-test-junit.jar:$JUNIT4" \
  -d "$WORK/test" $(find "$ROOT/game-stardew/src/test" -name '*.kt')

CLASSES=$(cd "$WORK/test" && find . -name '*Test.class' ! -name '*$*' \
  | sed 's|^\./||; s|\.class$||; s|/|.|g')
java -cp "$WORK/main:$WORK/test:$L/kotlin-stdlib.jar:$CORO:$L/kotlin-test.jar:$L/kotlin-test-junit.jar:$JUNIT4:$HAMCREST" \
  org.junit.runner.JUnitCore $CLASSES
