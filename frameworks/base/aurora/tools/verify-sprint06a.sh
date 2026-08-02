#!/usr/bin/env bash
# Sprint 06A verification: animation architecture.
# Run on the build VM:  bash frameworks/base/aurora/tools/verify-sprint06a.sh
cd /mnt/build/lineage || exit 1
export USE_CCACHE=1 CCACHE_EXEC=/usr/bin/ccache CCACHE_DIR=/mnt/build/ccache
export TMPDIR=/mnt/build/tmp LC_ALL=C
set +u
source build/envsetup.sh >/dev/null 2>&1
lunch lineage_sdk_phone_x86_64-bp4a-userdebug >/dev/null 2>&1

AURORA=frameworks/base/aurora

echo "=== Animation files ==="
find $AURORA/sdk/java/aurora/sdk/animation \
     $AURORA/runtime/java/aurora/runtime/animation -type f 2>/dev/null | sort | sed 's|.*/||'

echo
echo "=== 1. Compile ==="
m aurora-sdk aurora-runtime aurora-platform > /mnt/build/s06a-build.log 2>&1
RC_BUILD=$?
echo "rc=$RC_BUILD"
if [ "$RC_BUILD" -ne 0 ]; then
  HITS1="$(grep -nE '^e: |error:|FAILED:' /mnt/build/s06a-build.log)"
  if [ -n "$HITS1" ]; then
    echo "$HITS1" | tail -20 | sed 's/^/    /'
  else
    # Curated pattern matched nothing (e.g. soong_ui crash, OOM-killed
    # compiler) - fall back to the tail so a failure is never silent.
    tail -20 /mnt/build/s06a-build.log | sed 's/^/    /'
  fi
fi

echo
echo "=== 2. Tests ==="
m aurora-platform-tests >> /mnt/build/s06a-build.log 2>&1
RC_TB=$?
echo "test build rc=$RC_TB"
if [ "$RC_TB" -ne 0 ]; then
  HITS2="$(grep -nE '^e: |error:' /mnt/build/s06a-build.log)"
  if [ -n "$HITS2" ]; then
    echo "$HITS2" | tail -20 | sed 's/^/    /'
  else
    tail -20 /mnt/build/s06a-build.log | sed 's/^/    /'
  fi
fi
RC_UT=1
JAR=out/host/linux-x86/testcases/aurora-platform-tests/aurora-platform-tests.jar
if [ "$RC_TB" -eq 0 ] && [ -f "$JAR" ]; then
  # Every test class must be named here or it silently never runs.
  java -cp "$JAR" org.junit.runner.JUnitCore \
       aurora.sdk.AuroraVersionTest \
       aurora.runtime.AuroraRuntimeTest \
       aurora.sdk.design.DesignTokensTest \
       aurora.runtime.ServiceProviderTest \
       aurora.sdk.event.AuroraEventBusTest \
       aurora.runtime.time.TimeInfrastructureTest \
       aurora.sdk.animation.AnimationApiTest \
       aurora.runtime.animation.AnimationStateMachineTest \
       aurora.runtime.animation.AnimationLifecycleTest \
       aurora.runtime.animation.AnimationRegistryTest \
       aurora.runtime.animation.AnimationDeterminismTest > /mnt/build/s06a-ut.log 2>&1
  RC_UT=$?
  grep -E "^OK|^Tests run|^FAILURES" /mnt/build/s06a-ut.log
  sed -n '/^1)/,$p' /mnt/build/s06a-ut.log | head -30
else
  # Tell "tests failed" apart from "jar was never built".
  echo "  skipped: test-build rc=$RC_TB, jar present=$([ -f "$JAR" ] && echo yes || echo no) ($JAR)"
fi
echo "junit exit=$RC_UT"

echo
echo "=== 3. Architecture wall ==="
bash "$AURORA/tools/arch-test.sh" > /mnt/build/s06a-arch.log 2>&1
RC_ARCH=$?
grep -E "no call to|only in|nowhere under" /mnt/build/s06a-arch.log | head -20
tail -3 /mnt/build/s06a-arch.log
echo "arch exit=$RC_ARCH"

echo
echo "=== Animation sources present ==="
SRC_COUNT=$(find $AURORA/sdk/java/aurora/sdk/animation \
                 $AURORA/runtime/java/aurora/runtime/animation \
                 -name '*.kt' 2>/dev/null | wc -l)
echo "kotlin files under the animation packages: $SRC_COUNT"

echo
echo "=== 4. No Android dependency ==="
if [ "$SRC_COUNT" -eq 0 ]; then
  # An empty tree makes grep find nothing, which reads as "clean" unless
  # called out explicitly. By the time this script is trusted (Task 19)
  # the animation packages are required to be populated, so treat an
  # empty tree as a failure, not a pass with zero hits.
  echo "  NOTHING TO CHECK: no .kt files under the animation packages - not the same as clean"
  RC_NOANDROID=1
else
  ANDROID_HITS=$(grep -rhE "^import +android\." \
    $AURORA/sdk/java/aurora/sdk/animation \
    $AURORA/runtime/java/aurora/runtime/animation 2>/dev/null | wc -l)
  echo "android.* imports under animation packages: $ANDROID_HITS"
  RC_NOANDROID=0
  [ "$ANDROID_HITS" -ne 0 ] && RC_NOANDROID=1
fi

echo
echo "=== 5. API coverage: every public declaration named by a test ==="
if [ "$SRC_COUNT" -eq 0 ]; then
  echo "  NOTHING TO CHECK: no .kt files under the animation packages - not the same as covered"
  RC_COV=1
else
  DECLS=$(grep -rhoE "^(class|object|interface|fun interface|data class|enum class|sealed interface) [A-Z][A-Za-z0-9]*" \
    $AURORA/sdk/java/aurora/sdk/animation \
    $AURORA/runtime/java/aurora/runtime/animation 2>/dev/null \
    | awk '{print $NF}' | sort -u)
  MISSING=""
  # Unquoted $DECLS is safe here only because the regex above emits
  # bare alphanumeric identifier tokens exclusively, one per line, with
  # no whitespace or glob metacharacters. If that regex ever loosens,
  # this loop must be requoted.
  for d in $DECLS; do
    grep -rqF "$d" $AURORA/tests/java/aurora/sdk/animation $AURORA/tests/java/aurora/runtime/animation 2>/dev/null \
      || MISSING="$MISSING $d"
  done
  if [ -n "$MISSING" ]; then
    echo "  NOT NAMED BY ANY TEST:$MISSING"
    RC_COV=1
  else
    echo "  all $(echo "$DECLS" | grep -c .) public declarations are named by a test"
    RC_COV=0
  fi
fi

echo
echo "======================================"
if [ "$RC_BUILD" -eq 0 ] && [ "$RC_UT" -eq 0 ] && [ "$RC_ARCH" -eq 0 ] \
   && [ "$RC_NOANDROID" -eq 0 ] && [ "$RC_COV" -eq 0 ]; then
  echo "SPRINT06A_ALL_GREEN"
else
  echo "SPRINT06A_PROBLEM build=$RC_BUILD unit=$RC_UT arch=$RC_ARCH android=$RC_NOANDROID coverage=$RC_COV"
fi
