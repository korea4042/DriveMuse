#!/usr/bin/env bash
# Type-check core:domain without Gradle.
#
# Gradle cannot run in this container: services.gradle.org and Maven Central are outside the
# egress allowlist, so the wrapper cannot fetch a distribution and the Kotlin plugin cannot
# resolve. github.com is allowed, and the Kotlin compiler ships as a GitHub release, so the
# module can still be compiled directly. This catches redeclarations, signature mismatches and
# unresolved references in about a minute instead of a ten-minute CI round trip.
#
# It does NOT run the tests: JUnit is not fetchable here. CI still decides.
#
#   scripts/check_domain.sh            # main sources only
#   scripts/check_domain.sh --tests    # main plus the org.junit-based test sources
set -euo pipefail
KOTLIN_VERSION=${KOTLIN_VERSION:-2.1.20}
KOTLINC=${KOTLINC:-$HOME/kotlinc}

if [ ! -x "$KOTLINC/bin/kotlinc" ]; then
  echo "fetching kotlin $KOTLIN_VERSION"
  curl -sL -o /tmp/kotlinc.zip \
    "https://github.com/JetBrains/kotlin/releases/download/v$KOTLIN_VERSION/kotlin-compiler-$KOTLIN_VERSION.zip"
  unzip -q /tmp/kotlinc.zip -d "$(dirname "$KOTLINC")"
fi

SRC=(core/domain/src/main/kotlin/ai/drivemuse/domain/*.kt)
CP="$KOTLINC/lib/kotlin-test.jar"

if [ "${1:-}" = "--tests" ]; then
  # A stand-in for org.junit, which cannot be downloaded here. Only the shapes are needed, and it
  # has to be Java: `import org.junit.Assert.*` is a star import of static members, which Kotlin
  # cannot do from a Kotlin `object`. The Kotlin stub this replaces made kotlinc exit non-zero on
  # every run, and the old grep pipeline then reported PASS regardless.
  STUB=/tmp/junit-stub/org/junit
  mkdir -p "$STUB"
  cat > "$STUB/Test.java" <<'STUB'
package org.junit;
public @interface Test {}
STUB
  cat > "$STUB/Assert.java" <<'STUB'
package org.junit;
public class Assert {
    public static void assertEquals(Object a, Object b) {}
    public static void assertEquals(String m, Object a, Object b) {}
    public static void assertEquals(long a, long b) {}
    public static void assertEquals(String m, long a, long b) {}
    public static void assertEquals(double a, double b, double d) {}
    public static void assertTrue(boolean c) {}
    public static void assertTrue(String m, boolean c) {}
    public static void assertFalse(boolean c) {}
    public static void assertFalse(String m, boolean c) {}
    public static void assertNull(Object o) {}
    public static void assertNotNull(Object o) {}
}
STUB
  # Files using kotlin.test.Test need the real engine and are left to CI.
  mapfile -t TESTS < <(grep -rl "import org.junit" core/domain/src/test/kotlin/ai/drivemuse/domain/)
  SRC+=("$STUB/Test.java" "$STUB/Assert.java" "${TESTS[@]}")
fi

# Not a pipeline into grep: under `set -o pipefail` a non-zero kotlinc exit makes the whole
# pipeline non-zero, the `&&` branch never runs, and a failed compile prints PASS. The compiler's
# own status is checked, and its output is grepped separately.
STATUS=0
"$KOTLINC/bin/kotlinc" -cp "$CP" "${SRC[@]}" -d /tmp/domain-check -nowarn > /tmp/domain-check.log 2>&1 || STATUS=$?
if grep -qE "error:" /tmp/domain-check.log || [ "$STATUS" -ne 0 ]; then
  grep -E "error:" /tmp/domain-check.log || cat /tmp/domain-check.log
  echo "FAILED (kotlinc exit $STATUS)"
  exit 1
fi
echo "PASS: core:domain type-checks"
