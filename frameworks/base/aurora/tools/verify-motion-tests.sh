#!/usr/bin/env bash
# Build the host test jar and run every JUnit class in the Aurora tree.
#
# Run on the build VM:  bash frameworks/base/aurora/tools/verify-motion-tests.sh
#
# ## Why this script exists
#
# `verify-sprint06a5.sh` names its test classes in a JUnitCore invocation, above a comment saying
# "Every test class must be named here or it silently never runs." That comment was accurate and
# the list was not: by the end of Sprint 06B.3 it named eleven classes while the tree held
# twenty-one. The ten it omitted included `ContractSelfTest`, which is the harness that proves the
# contract assertions are able to refuse at all.
#
# A hand-maintained list that reports complete while covering half the tree is the failure this
# project keeps building gates against, and it had reached the runner itself. So the list here is
# checked rather than trusted: gate 0 derives the set of test classes from the tree, compares it to
# the set this script names, and fails on any difference in either direction. Adding a test file
# without adding it here is a red run, not a quiet omission.
#
# `atest` would discover them all and need no list. This uses JUnitCore because the sprint verify
# scripts do, and because a run whose class list is printed is a run whose coverage can be read off
# the log afterwards.
set -uo pipefail

cd /mnt/build/lineage || exit 1
export USE_CCACHE=1 CCACHE_EXEC=/usr/bin/ccache CCACHE_DIR=/mnt/build/ccache
export TMPDIR=/mnt/build/tmp LC_ALL=C
set +u
source build/envsetup.sh >/dev/null 2>&1
lunch lineage_sdk_phone_x86_64-bp4a-userdebug >/dev/null 2>&1
set -u

AURORA=frameworks/base/aurora
LOG=/mnt/build/motion-tests.log
FAILURES=0
pass() { echo "  ok    $1"; }
fail() { echo "  FAIL  $1"; FAILURES=$((FAILURES + 1)); }

# Every test class this script runs. Kept sorted, and checked against the tree by gate 0 below.
CLASSES=(
  aurora.runtime.AuroraRuntimeTest
  aurora.runtime.ServiceProviderTest
  aurora.runtime.animation.AnimationDeterminismTest
  aurora.runtime.animation.AnimationLifecycleTest
  aurora.runtime.animation.AnimationRegistryTest
  aurora.runtime.animation.AnimationStateMachineTest
  aurora.runtime.animation.DecayPipelineTest
  aurora.runtime.animation.SnapPipelineTest
  aurora.runtime.animation.SpringPipelineTest
  aurora.runtime.animation.SpringSamplerTest
  aurora.runtime.animation.TargetSelectionPolicyTest
  aurora.runtime.time.TimeInfrastructureTest
  aurora.runtime.volume.DefaultVolumeServiceTest
  aurora.sdk.AuroraVersionTest
  aurora.sdk.animation.AnimationApiTest
  aurora.sdk.design.DesignTokensTest
  aurora.sdk.event.AuroraEventBusTest
  aurora.testing.animation.ContractSelfTest
  aurora.testing.animation.DecayIntegrationTest
  aurora.testing.animation.PhysicsContractTest
  aurora.testing.animation.SpringContractTest
  aurora.testing.animation.TimedSamplerContractTest
)

echo "=== 0. The class list matches the tree ==="
# Derived from the sources rather than from the jar, so a class that fails to compile is still
# counted here - otherwise a build break would shrink the expected set to match the damage.
PRESENT=$(
  find "$AURORA/tests" \( -name '*.kt' -o -name '*.java' \) -print0 2>/dev/null |
  while IFS= read -r -d '' f; do
    cls=$(grep -oE '^(public )?(internal )?class [A-Za-z0-9_]+Test\b' "$f" | awk '{print $NF}')
    pkg=$(grep -oE '^package [a-z0-9_.]+' "$f" | awk '{print $2}')
    [ -n "$cls" ] && [ -n "$pkg" ] && echo "$pkg.$cls"
  done | sort -u
)
NAMED=$(printf '%s\n' "${CLASSES[@]}" | sort -u)

if [ -z "$PRESENT" ]; then
  fail "found no test classes under $AURORA/tests; this script is looking in the wrong place"
else
  UNRUN=$(comm -23 <(printf '%s\n' "$PRESENT") <(printf '%s\n' "$NAMED"))
  if [ -n "$UNRUN" ]; then
    fail "test class(es) in the tree that this script would never run:"
    printf '%s\n' "$UNRUN" | sed 's/^/          /'
  else
    pass "$(printf '%s\n' "$PRESENT" | wc -l) test classes present, all named"
  fi

  PHANTOM=$(comm -13 <(printf '%s\n' "$PRESENT") <(printf '%s\n' "$NAMED"))
  if [ -n "$PHANTOM" ]; then
    # JUnitCore exits non-zero on an unknown class, so this would be caught downstream anyway -
    # but it would be caught as "a test failed", which is a different and more alarming thing than
    # "a test was renamed".
    fail "class(es) named here that do not exist in the tree:"
    printf '%s\n' "$PHANTOM" | sed 's/^/          /'
  else
    pass "every class named here exists"
  fi
fi

echo
echo "=== 1. Compile ==="
m aurora-sdk aurora-runtime aurora-platform > "$LOG" 2>&1
RC_BUILD=$?
if [ "$RC_BUILD" -eq 0 ]; then
  pass "aurora-sdk aurora-runtime aurora-platform"
else
  fail "production build rc=$RC_BUILD"
  HITS="$(grep -nE '^e: |error:|FAILED:' "$LOG")"
  if [ -n "$HITS" ]; then
    echo "$HITS" | tail -25 | sed 's/^/          /'
  else
    tail -25 "$LOG" | sed 's/^/          /'
  fi
fi

echo
echo "=== 2. Test build ==="
m aurora-platform-tests >> "$LOG" 2>&1
RC_TB=$?
if [ "$RC_TB" -eq 0 ]; then
  pass "aurora-platform-tests"
else
  fail "test build rc=$RC_TB"
  HITS="$(grep -nE '^e: |error:|FAILED:' "$LOG")"
  if [ -n "$HITS" ]; then
    echo "$HITS" | tail -25 | sed 's/^/          /'
  else
    tail -25 "$LOG" | sed 's/^/          /'
  fi
fi

echo
echo "=== 3. Run ==="
JAR=out/host/linux-x86/testcases/aurora-platform-tests/aurora-platform-tests.jar
UT=/mnt/build/motion-ut.log
if [ "$RC_TB" -ne 0 ] || [ ! -f "$JAR" ]; then
  # Tell "tests failed" apart from "the jar was never built". A skipped run reported as a pass is
  # the same shape of lie as a grep that matched nothing.
  fail "not run: test-build rc=$RC_TB, jar present=$([ -f "$JAR" ] && echo yes || echo no)"
else
  java -cp "$JAR" org.junit.runner.JUnitCore "${CLASSES[@]}" > "$UT" 2>&1
  RC_UT=$?
  grep -E "^OK|^Tests run|^FAILURES" "$UT" | sed 's/^/  /'
  if [ "$RC_UT" -eq 0 ]; then
    pass "${#CLASSES[@]} classes green"
  else
    fail "junit exit=$RC_UT"
    sed -n '/^1)/,$p' "$UT" | head -40 | sed 's/^/          /'
  fi
fi

echo
echo "======================================"
if [ "$FAILURES" -eq 0 ]; then
  echo "MOTION TESTS PASS (${#CLASSES[@]} classes)"
  exit 0
fi
echo "MOTION TESTS FAIL ($FAILURES)"
exit 1
