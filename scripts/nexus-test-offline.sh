#!/usr/bin/env bash
# Compiles and runs :source-nexus unit tests WITHOUT Gradle/Maven repositories.
# Use only in sandboxes where Maven Central / Google Maven are blocked; normal
# development should run `./gradlew :source-nexus:testDebugUnitTest`.
#
# What is compiled here:
#   :core main sources + source-nexus main sources EXCEPT
#     - NexusApiClient.kt  (needs OkHttp/Okio, not available offline)
#     - di/                (needs Dagger/Hilt)
#   and every test (none of them touch OkHttp: NexusModSource is tested against a fake NexusApi).
# kotlinx-serialization: the compiler plugin ships with kotlinc; the runtime jars come from the
# local Gradle distribution (1.6.2 there vs 1.7.3 in the catalog; only basic Json APIs are used).
#
# Needs: a JDK 17+, a Kotlin 2.0.x compiler (downloaded from GitHub releases if KOTLINC_HOME
# is unset) and JUnit 4 + hamcrest + kotlinx-serialization jars from a local Gradle install.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${WORK_DIR:-${TMPDIR:-/tmp}/modhub-nexus-test}"
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
SER_CORE="$(ls "$GRADLE_LIB"/kotlinx-serialization-core-jvm-*.jar | head -1)"
SER_JSON="$(ls "$GRADLE_LIB"/kotlinx-serialization-json-jvm-*.jar | head -1)"
L="$KOTLINC_HOME/lib"
CORO="$L/kotlinx-coroutines-core-jvm.jar"
SER_PLUGIN="$L/kotlinx-serialization-compiler-plugin.jar"
DEPS="$CORO:$SER_CORE:$SER_JSON"

rm -rf "$WORK/main" "$WORK/test"
"$KOTLINC_HOME/bin/kotlinc" -jvm-target 17 -Xplugin="$SER_PLUGIN" -cp "$DEPS" -d "$WORK/main" \
  $(find "$ROOT/core/src/main" -name '*.kt') \
  $(find "$ROOT/source-nexus/src/main" -name '*.kt' ! -path '*/di/*' ! -name 'NexusApiClient.kt')
"$KOTLINC_HOME/bin/kotlinc" -jvm-target 17 -Xplugin="$SER_PLUGIN" -Xfriend-paths="$WORK/main" \
  -cp "$WORK/main:$DEPS:$L/kotlin-test.jar:$L/kotlin-test-junit.jar:$JUNIT4" \
  -d "$WORK/test" $(find "$ROOT/source-nexus/src/test" -name '*.kt')

CLASSES=$(cd "$WORK/test" && find . -name '*Test.class' ! -name '*$*' \
  | sed 's|^\./||; s|\.class$||; s|/|.|g')
java -cp "$WORK/main:$WORK/test:$L/kotlin-stdlib.jar:$DEPS:$L/kotlin-test.jar:$L/kotlin-test-junit.jar:$JUNIT4:$HAMCREST" \
  org.junit.runner.JUnitCore $CLASSES
