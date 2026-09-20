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
  # A stand-in for org.junit, which cannot be downloaded here. Only the shapes are needed.
  cat > /tmp/junit_stub.kt <<'STUB'
package org.junit
@Target(AnnotationTarget.FUNCTION) annotation class Test
object Assert {
    @JvmStatic fun assertEquals(a: Any?, b: Any?) {}
    @JvmStatic fun assertEquals(m: String, a: Any?, b: Any?) {}
    @JvmStatic fun assertEquals(a: Long, b: Long) {}
    @JvmStatic fun assertEquals(m: String, a: Long, b: Long) {}
    @JvmStatic fun assertEquals(a: Double, b: Double, d: Double) {}
    @JvmStatic fun assertTrue(c: Boolean) {}
    @JvmStatic fun assertTrue(m: String, c: Boolean) {}
    @JvmStatic fun assertFalse(c: Boolean) {}
    @JvmStatic fun assertFalse(m: String, c: Boolean) {}
    @JvmStatic fun assertNull(o: Any?) {}
    @JvmStatic fun assertNotNull(o: Any?) {}
}
STUB
  # Files using kotlin.test.Test need the real engine and are left to CI.
  mapfile -t TESTS < <(grep -rl "import org.junit" core/domain/src/test/kotlin/ai/drivemuse/domain/)
  SRC+=(/tmp/junit_stub.kt "${TESTS[@]}")
fi

"$KOTLINC/bin/kotlinc" -cp "$CP" "${SRC[@]}" -d /tmp/domain-check -nowarn 2>&1 \
  | grep -v "cannot import on demand from object 'Assert'" \
  | grep -E "error:" && { echo "FAILED"; exit 1; }
echo "PASS: core:domain type-checks"
