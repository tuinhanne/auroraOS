# Sprint 06A — Animation Architecture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the animation engine's API and lifecycle — states, handles, registry, driver — so that Volume Overlay, Dynamic Island, Launcher, Notification, Quick Settings and Gesture can all be built on it later without the engine changing. No solver is written.

**Architecture:** Two tiers mirroring `time`. `aurora.sdk.animation` holds data, interfaces and contracts; `aurora.runtime.animation` holds the machine. One `FrameCallback` drives everything, building exactly one `FrameTime` per frame and ticking an insertion-ordered registry. A pure `(state, event) → state` function owns the seven-state lifecycle. Progress comes from an `AnimationStrategy`, of which Sprint 06A implements exactly one: `TimedStrategy`.

**Tech Stack:** Kotlin (Soong `java_library`, `sdk_version: "core_current"`), JUnit 4 host tests, bash (`arch-test.sh`), Soong `Android.bp`.

**Spec:** [`docs/specs/2026-08-02-sprint-06a-animation-architecture-design.md`](../specs/2026-08-02-sprint-06a-animation-architecture-design.md) — approved and frozen.
**Decisions:** [`docs/adr/ADR-001..006`](../adr/).
**Branch:** `sprint-06a-animation-architecture` (already created; commits `becce43`, `ec1d081` hold the spec and ADRs).

---

## Read this before Task 1

### Nothing compiles on this workstation

There is no Gradle and no `out/` here. The LineageOS tree lives on a Linux VM at
`/mnt/build/lineage`. That splits the three gates in two:

| Gate | Where | When |
|---|---|---|
| **Architecture gate** — `frameworks/base/aurora/tools/arch-test.sh` | Locally, in Git Bash | **Every task.** It is a bash script over source text; it runs on Windows and reports `skip` for the negative compiles because `javac` and the built jars are absent. |
| **Compile gate** — `m aurora-sdk aurora-runtime aurora-platform` | VM | At the two checkpoints (Task 7, Task 19) |
| **Test gate** — `JUnitCore` over the host test jar | VM | At the two checkpoints (Task 7, Task 19) |

So the loop is: implement and commit locally with the arch gate on every task, then push
everything to the VM at a checkpoint. This is honest about the tooling rather than pretending
each task can run `pytest`. Do not skip the local arch gate — it is the only automated check
between checkpoints.

### The VM round-trip

```powershell
.\sync-to-vm.ps1
```
then on the VM:
```bash
bash /mnt/build/lineage/frameworks/base/aurora/tools/verify-sprint06a.sh
```
`vm-apply-code.sh` rsyncs `frameworks/base/aurora`, so the verify script created in Task 0
travels with the code and cannot be lost the way the old scratchpad scripts were.

### Kotlin visibility

Tests live in `aurora-platform-tests`, a **different Soong module** from `aurora-runtime`.
Kotlin `internal` is module-scoped, so anything marked `internal` is invisible to the tests.
**Every class in this plan is public**, including `AnimationHandleImpl`. Do not add `internal`.

### One addendum to the frozen spec

`TimedSpec` gains one method, `elapsedForProgress(progress: Float): Long`. `handle.seek(p)`
must move `ExecutionTimeline` to a specific elapsed time, and `AnimationStrategy.seekTo()`
returns nothing, so the progress→elapsed conversion has to live somewhere reachable. It is
pure arithmetic over `Timeline`, so it belongs on the data. Everything else follows the spec
exactly.

---

## File Structure

```
frameworks/base/aurora/
├── sdk/java/aurora/sdk/animation/          NEW — the language (9 files)
│   ├── AnimationState.kt                   7 states + isActive / isResting / isTerminal
│   ├── Interpolator.kt                     fun interface + LINEAR identity
│   ├── AnimationSpec.kt                    sealed: TimedSpec | PhysicsSpec (+ Spring/Decay/Snap)
│   ├── Animation.kt                        name, spec, from, to, valueAt()
│   ├── AnimationStrategy.kt                progress / easedProgress / isFinished / advance
│   ├── AnimationListener.kt                onStateChanged, onUpdate — both defaulted
│   ├── AnimationHandle.kt                  the public control surface
│   ├── Animator.kt                         create / play / cancelAll / activeCount
│   └── AnimationController.kt              start / stop / tick(FrameTime)
├── sdk/java/aurora/sdk/service/
│   └── AnimationService.kt                 MODIFY — drop its local AnimationHandle
├── runtime/java/aurora/runtime/animation/  NEW — the machine (7 files)
│   ├── AnimationStateMachine.kt            AnimationEvent + pure next()
│   ├── ExecutionTimeline.kt                origin, elapsed, pause shift, seek
│   ├── TimedStrategy.kt                    the only AnimationStrategy in 06A
│   ├── AnimationRegistry.kt                insertion-ordered, deferred mutation
│   ├── AnimationHandleImpl.kt              binds machine + timeline + strategy
│   ├── DefaultAnimator.kt                  handle factory
│   ├── DefaultAnimationController.kt       frame entry point
│   └── AnimationDriver.kt                  FrameScheduler → one FrameTime → registry
├── tests/java/aurora/
│   ├── sdk/animation/AnimationApiTest.kt           NEW
│   └── runtime/animation/
│       ├── AnimationStateMachineTest.kt            NEW
│       ├── AnimationLifecycleTest.kt               NEW
│       ├── AnimationRegistryTest.kt                NEW
│       └── AnimationDeterminismTest.kt             NEW
├── contracts/runtime.contract              MODIFY — forbid-call-under entries
├── tools/arch-test.sh                      MODIFY — implement forbid-call-under
├── tools/verify-sprint06a.sh               NEW — the three gates, in the repo
└── README.md                               MODIFY — RULE-009 … RULE-014
```

`AnimationDriver.kt` makes eight files in `runtime/animation`, not seven; the spec's inventory
lists eight and the tree above matches it.

---

## Task 0: Verify script, in the repo this time

**Files:**
- Create: `frameworks/base/aurora/tools/verify-sprint06a.sh`

**Why first:** the previous sprints' verify scripts lived in a temp scratchpad and are gone.
This one sits inside a synced path, so it reaches the VM with the code.

- [ ] **Step 1: Create the script**

```bash
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
```

- [ ] **Step 2: Check it parses**

Run: `bash -n frameworks/base/aurora/tools/verify-sprint06a.sh`
Expected: no output, exit 0.

- [ ] **Step 3: Commit**

```bash
git add frameworks/base/aurora/tools/verify-sprint06a.sh
git commit -m "Sprint 06A: verify script, kept in the repo

The scripts for sprints 01-05.5b lived in a session scratchpad and are gone.
This one sits under frameworks/base/aurora, which vm-apply-code.sh rsyncs, so
it reaches the VM with the code it checks.

Five gates: compile, host tests, arch-test, no android.* under the animation
packages, and every public declaration named by at least one test. Gates 4
and 5 fail loudly rather than pass vacuously when the animation source
directories are empty."
```

---

## Task 1: `AnimationState`

**Files:**
- Create: `frameworks/base/aurora/sdk/java/aurora/sdk/animation/AnimationState.kt`
- Test: `frameworks/base/aurora/tests/java/aurora/sdk/animation/AnimationApiTest.kt`

- [ ] **Step 1: Write the failing test**

Create `frameworks/base/aurora/tests/java/aurora/sdk/animation/AnimationApiTest.kt`:

```kotlin
/*
 * Copyright (C) 2026 The AuroraOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package aurora.sdk.animation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure SDK animation types, exercised with no engine and no frames.
 *
 * Everything here is data or a pure function, so every assertion is exact. Nothing in this
 * file constructs a controller, a registry or a driver — if a test here needs one, it belongs
 * in one of the runtime test classes instead.
 */
class AnimationApiTest {

    // --- AnimationState ------------------------------------------------------

    @Test
    fun everyStateIsClassifiedDeliberately() {
        // The growth canary. A state added in a later sprint fails HERE, on the row that needs
        // a decision, rather than on a count that can be satisfied by editing a number.
        //
        // "unclassified" is a legitimate answer - IDLE and PAUSED are both - but it has to be
        // written down per state rather than defaulted into by omission.
        val expected = mapOf(
            AnimationState.IDLE to "unclassified",
            AnimationState.SCHEDULED to "active",
            AnimationState.RUNNING to "active",
            AnimationState.PAUSED to "unclassified",
            AnimationState.COMPLETED to "resting",
            AnimationState.CANCELLED to "resting",
            AnimationState.DISPOSED to "terminal",
        )
        assertEquals(
            "a state added later must be classified in this table, not omitted from it",
            AnimationState.values().toSet(),
            expected.keys,
        )
        AnimationState.values().forEach { state ->
            val actual = when {
                state.isActive -> "active"
                state.isResting -> "resting"
                state.isTerminal -> "terminal"
                else -> "unclassified"
            }
            assertEquals("classification of $state", expected[state], actual)
        }
    }

    @Test
    fun onlyScheduledAndRunningAreActive() {
        val active = AnimationState.values().filter { it.isActive }.toSet()
        assertEquals(setOf(AnimationState.SCHEDULED, AnimationState.RUNNING), active)
    }

    @Test
    fun onlyCompletedAndCancelledAreResting() {
        val resting = AnimationState.values().filter { it.isResting }.toSet()
        assertEquals(setOf(AnimationState.COMPLETED, AnimationState.CANCELLED), resting)
    }

    @Test
    fun onlyDisposedIsTerminal() {
        val terminal = AnimationState.values().filter { it.isTerminal }.toSet()
        assertEquals(setOf(AnimationState.DISPOSED), terminal)
    }

    @Test
    fun theThreePredicatesNeverOverlap() {
        // A state is at most one of active / resting / terminal. If two were ever true at
        // once, callers branching on them would take two paths for one state.
        AnimationState.values().forEach { state ->
            val count = listOf(state.isActive, state.isResting, state.isTerminal).count { it }
            assertTrue("$state matches more than one predicate", count <= 1)
        }
    }

    @Test
    fun idleIsNoneOfThem() {
        assertFalse(AnimationState.IDLE.isActive)
        assertFalse(AnimationState.IDLE.isResting)
        assertFalse(AnimationState.IDLE.isTerminal)
    }
}
```

- [ ] **Step 2: Verify it cannot compile yet**

There is no local compiler, so confirm the type genuinely does not exist:

Run: `ls frameworks/base/aurora/sdk/java/aurora/sdk/animation/AnimationState.kt`
Expected: `No such file or directory`. The test references a type that does not exist, so the
compile gate at Task 7 would fail.

- [ ] **Step 3: Write the implementation**

Create `frameworks/base/aurora/sdk/java/aurora/sdk/animation/AnimationState.kt`:

```kotlin
/*
 * Copyright (C) 2026 The AuroraOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package aurora.sdk.animation

/**
 * Where an animation is in its lifecycle.
 *
 * ## Two kinds of ending
 *
 * [COMPLETED] and [CANCELLED] end an *execution*. [DISPOSED] ends the *handle*. The
 * distinction is RULE-012 and it is what lets Volume, Dynamic Island and Control Center
 * re-run one handle indefinitely instead of allocating a new one for every gesture: a
 * cancelled animation is resting, not dead, and [aurora.sdk.animation.AnimationHandle.restart]
 * starts a fresh execution on the same object.
 *
 * `resume()` is deliberately *not* legal from a resting state. Resuming continues an
 * execution; restarting begins another. Collapsing the two would make the API unable to say
 * which one a caller meant.
 *
 * ## The three predicates do not partition the states
 *
 * [IDLE] and [PAUSED] match none of them, deliberately. Neither is ticking and neither has
 * finished: a paused animation is live, resumable and seekable. Both are queried by direct
 * equality rather than through a predicate, so `else` in a chain of these three is not
 * "disposed" — it is "idle or paused", and treating it as disposed would strand a handle the
 * user is still holding.
 */
enum class AnimationState {

    /** Created, never played. The state a handle from `Animator.create` starts in. */
    IDLE,

    /** Registered with the engine, has not yet received a tick for this execution. */
    SCHEDULED,

    /**
     * Has received at least one tick and has not been held or ended.
     *
     * Not a promise that time is passing. Whether frames are arriving is a property of the
     * engine, not of the animation: `AnimationController.stop()` deliberately leaves in-flight
     * animations in this state rather than cancelling them, so that a display turning off does
     * not visibly reset the interface when it comes back. A handle in this state is advancing
     * *whenever the engine is running*.
     */
    RUNNING,

    /** Held. Time does not accumulate; see `ExecutionTimeline`. */
    PAUSED,

    /** The execution reached its end. Restartable. */
    COMPLETED,

    /** The execution was stopped where it stood, without jumping to the end. Restartable. */
    CANCELLED,

    /** The handle is finished for good. Nothing recovers from here. */
    DISPOSED;

    /** Receiving, or about to receive, ticks. */
    val isActive: Boolean
        get() = this == SCHEDULED || this == RUNNING

    /** The execution ended; the handle is still usable. RULE-012. */
    val isResting: Boolean
        get() = this == COMPLETED || this == CANCELLED

    /** [DISPOSED] only. The handle is dead. */
    val isTerminal: Boolean
        get() = this == DISPOSED
}
```

- [ ] **Step 4: Run the architecture gate**

Run: `bash frameworks/base/aurora/tools/arch-test.sh`
Expected: last two lines are `checks passed: N   failures: 0` and `ARCH TEST PASS`.

- [ ] **Step 5: Commit**

```bash
git add frameworks/base/aurora/sdk/java/aurora/sdk/animation/AnimationState.kt \
        frameworks/base/aurora/tests/java/aurora/sdk/animation/AnimationApiTest.kt
git commit -m "Sprint 06A: AnimationState

Seven states, three predicates. COMPLETED and CANCELLED end an execution;
only DISPOSED ends the handle (RULE-012), which is what makes a handle
reusable on the volume and notification hot paths.

The predicates are asserted to be mutually exclusive: if a state were ever
both active and resting, callers branching on them would take two paths."
```

---

## Task 2: `Interpolator`

**Files:**
- Create: `frameworks/base/aurora/sdk/java/aurora/sdk/animation/Interpolator.kt`
- Modify: `frameworks/base/aurora/tests/java/aurora/sdk/animation/AnimationApiTest.kt`

- [ ] **Step 1: Write the failing test**

Append to `AnimationApiTest`, immediately after `idleIsNoneOfThem()`:

```kotlin
    // --- Interpolator --------------------------------------------------------

    @Test
    fun linearIsTheIdentity() {
        // Not "approximately linear" — the identity. LINEAR computes nothing, which is why
        // it is allowed to live in the SDK alongside the tokens (RULE-004, RULE-010).
        listOf(0f, 0.25f, 0.5f, 0.75f, 1f, -0.5f, 1.5f).forEach {
            assertEquals(it, Interpolator.LINEAR.transform(it), 0f)
        }
    }

    @Test
    fun anInterpolatorIsWritableAsALambda() {
        val easeOut = Interpolator { p -> 1f - (1f - p) * (1f - p) }
        assertEquals(0f, easeOut.transform(0f), 1e-6f)
        assertEquals(0.75f, easeOut.transform(0.5f), 1e-6f)
        assertEquals(1f, easeOut.transform(1f), 1e-6f)
    }
```

- [ ] **Step 2: Verify the type does not exist yet**

Run: `ls frameworks/base/aurora/sdk/java/aurora/sdk/animation/Interpolator.kt`
Expected: `No such file or directory`.

- [ ] **Step 3: Write the implementation**

Create `frameworks/base/aurora/sdk/java/aurora/sdk/animation/Interpolator.kt`:

```kotlin
/*
 * Copyright (C) 2026 The AuroraOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package aurora.sdk.animation

/**
 * Shapes linear progress into eased progress.
 *
 * ## Stateless, without exception
 *
 * RULE-009 requires that all mutable state live in an [AnimationStrategy]. An interpolator
 * that remembered anything between calls — a previous velocity, a last progress — would make
 * `seek()` and `restart()` non-repeatable, because `transform(0.5f)` twice would return two
 * different numbers. Implementations must be pure functions.
 *
 * That is also why the cubic Bézier solver is not here. It arrives in Sprint 06B as an
 * implementation of this interface; the four control points that describe it are already
 * design data in [aurora.sdk.design.Easing].
 */
fun interface Interpolator {

    /**
     * @param progress usually 0..1, but not clamped: an overshooting curve legitimately
     *     produces values outside the range, and clamping here would silently flatten it
     * @return the shaped progress
     */
    fun transform(progress: Float): Float

    companion object {

        /**
         * The identity element: returns its argument.
         *
         * Sprint 06A ships no solver, so this is the only interpolator that exists. It is
         * not an exception to "no executable code in the SDK" — it computes nothing.
         */
        @JvmField
        val LINEAR = Interpolator { it }
    }
}
```

- [ ] **Step 4: Run the architecture gate**

Run: `bash frameworks/base/aurora/tools/arch-test.sh`
Expected: `ARCH TEST PASS`.

- [ ] **Step 5: Commit**

```bash
git add frameworks/base/aurora/sdk/java/aurora/sdk/animation/Interpolator.kt \
        frameworks/base/aurora/tests/java/aurora/sdk/animation/AnimationApiTest.kt
git commit -m "Sprint 06A: Interpolator

A fun interface plus LINEAR, which returns its argument. No solver: the
Bezier arrives in 06B as an implementation of this, with its control points
already sitting in aurora.sdk.design.Easing.

transform() does not clamp. An overshooting curve legitimately leaves 0..1
and clamping here would flatten it invisibly."
```

---

## Task 3: `AnimationSpec`

**Files:**
- Create: `frameworks/base/aurora/sdk/java/aurora/sdk/animation/AnimationSpec.kt`
- Modify: `frameworks/base/aurora/tests/java/aurora/sdk/animation/AnimationApiTest.kt`

- [ ] **Step 1: Write the failing test**

Add these imports at the top of `AnimationApiTest.kt`, after the existing ones:

```kotlin
import aurora.sdk.design.MotionTokens
import aurora.sdk.time.AuroraClock
import aurora.sdk.time.Timeline
import org.junit.Assert.fail
```

Append to `AnimationApiTest`:

```kotlin
    // --- AnimationSpec -------------------------------------------------------

    private val ms = AuroraClock.NANOS_PER_MILLI

    @Test
    fun timedSpecDefaultsToLinear() {
        val spec = TimedSpec(Timeline.ofMillis(300))
        assertEquals(Interpolator.LINEAR, spec.interpolator)
    }

    @Test
    fun elapsedForProgressIsTheInverseOfProgressAt() {
        // THE invariant. Both directions of the elapsed-progress mapping live on TimedSpec,
        // and this is what keeps them honest: whatever elapsed time a progress maps to must
        // map back to the same progress.
        //
        // An earlier draft defined progress as spanning the whole repeated sequence, which
        // looked reasonable and was wrong: Timeline.progressAt counts per iteration and resets
        // to 0 each time round. The two were not inverses, and seek(0.25) on a three-times
        // timeline landed on progress 0.75. This test is why that is not still true.
        val specs = listOf(
            TimedSpec(Timeline.ofMillis(200)),
            TimedSpec(Timeline(durationNanos = 300 * ms, delayNanos = 100 * ms)),
            TimedSpec(Timeline(durationNanos = 300 * ms, delayNanos = 100 * ms, repeatCount = 2)),
            TimedSpec(Timeline(durationNanos = 200 * ms, repeatCount = 3, reverseOnRepeat = true)),
            TimedSpec(Timeline(durationNanos = 200 * ms, repeatCount = Timeline.REPEAT_INFINITE)),
        )
        specs.forEach { spec ->
            (0..100).map { it / 100f }.forEach { p ->
                val elapsed = spec.elapsedForProgress(p)
                assertEquals(
                    "round trip failed for $p on ${spec.timeline}",
                    p,
                    spec.timeline.progressAt(elapsed),
                    1e-5f
                )
            }
        }
    }

    @Test
    fun progressOneMeansTheEndOfTheFirstIterationNotTheEndOfTheSequence() {
        // Positions are per iteration, matching progressAt. Seeking a repeating animation
        // therefore lands at the end of its FIRST iteration and the remaining repeats play out
        // from there.
        val once = TimedSpec(Timeline(durationNanos = 300 * ms, delayNanos = 100 * ms))
        assertEquals(100 * ms, once.elapsedForProgress(0f))
        assertEquals(250 * ms, once.elapsedForProgress(0.5f))
        assertEquals(400 * ms, once.elapsedForProgress(1f))

        val thrice = TimedSpec(
            Timeline(durationNanos = 300 * ms, delayNanos = 100 * ms, repeatCount = 2)
        )
        assertEquals("same position, regardless of how many repeats follow",
            250 * ms, thrice.elapsedForProgress(0.5f))

        // The boundary case. Landing exactly on 400ms would be read by progressAt as the START
        // of iteration 1, so seeking to the far end would snap the animation back to its
        // beginning. One nanosecond inside is what makes seek(1f) mean what it says.
        assertEquals(399 * ms + 999_999L, thrice.elapsedForProgress(1f))
        assertEquals(1f, thrice.timeline.progressAt(thrice.elapsedForProgress(1f)), 1e-5f)
    }

    @Test
    fun elapsedForProgressClampsOutOfRangeInput() {
        val spec = TimedSpec(Timeline.ofMillis(200))
        assertEquals(0L, spec.elapsedForProgress(-1f))
        assertEquals(200 * ms, spec.elapsedForProgress(2f))
    }

    @Test
    fun everyPhysicsSpecCarriesVelocityAndRestThresholds() {
        // The three fields are what a solver needs and a Timeline cannot express. Declared
        // now so that 06B adds solvers without changing this file.
        val specs: List<PhysicsSpec> = listOf(
            SpringSpec(),
            DecaySpec(),
            SnapSpec(targets = listOf(0f, 1f)),
        )
        // javaClass.simpleName, not ::class.simpleName: KClass pulls in Kotlin reflection,
        // which is not on the core_current classpath.
        specs.forEach {
            assertTrue("${it.javaClass.simpleName} restVelocity", it.restVelocity > 0f)
            assertTrue("${it.javaClass.simpleName} restDelta", it.restDelta > 0f)
        }
    }

    @Test
    fun springSpecWrapsADesignTokenRatherThanReplacingIt() {
        // The token says which spring the design chose; the spec says how to run it. Two
        // decisions made by two different people, so two types.
        val spec = SpringSpec(spring = MotionTokens.SPRING_BOUNCY, initialVelocity = 2f)
        assertEquals(MotionTokens.SPRING_BOUNCY, spec.spring)
        assertEquals(2f, spec.initialVelocity, 0f)
    }

    @Test
    fun aSnapSpecWithoutTargetsIsRejected() {
        try {
            SnapSpec(targets = emptyList())
            fail("a snap with nowhere to snap to must not be constructible")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun aDecayWithoutFrictionIsRejected() {
        try {
            DecaySpec(friction = 0f)
            fail("frictionless decay never settles")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun aPhysicsSpecWithoutRestThresholdsIsRejected() {
        // A solver with a zero threshold never reports settling, so the animation runs forever.
        // As fatal as frictionless decay, and rejected as loudly.
        listOf<Pair<String, () -> PhysicsSpec>>(
            "spring restVelocity" to { SpringSpec(restVelocity = 0f) },
            "spring restDelta" to { SpringSpec(restDelta = 0f) },
            "decay restVelocity" to { DecaySpec(restVelocity = -1f) },
            "snap restDelta" to { SnapSpec(targets = listOf(0f), restDelta = 0f) },
        ).forEach { (name, construct) ->
            try {
                construct()
                fail("$name must be rejected")
            } catch (expected: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun physicsThresholdsAreNormalisedSoTheDefaultsAreSaneAtAnyScale() {
        // The defaults are only meaningful in normalised progress. If they were value units,
        // restDelta = 0.001f would mean a thousandth of a pixel on a full-screen slide, which no
        // solver would ever reach, and the animation would never report settling.
        //
        // This test cannot check units - nothing can - so it checks the consequence: the
        // thresholds are small fractions of the unit interval, which is the only reading under
        // which they work for both an alpha fade over 0..1 and a 1000px slide.
        val spec = SpringSpec()
        assertTrue("restDelta must be a fraction of the unit interval", spec.restDelta < 0.01f)
        assertTrue("restVelocity must be a fraction of the unit interval", spec.restVelocity < 0.1f)
    }

    @Test
    fun physicsSpecsAreAnimationSpecs() {
        val spec: AnimationSpec = SpringSpec()
        assertTrue(spec is PhysicsSpec)
    }
```

- [ ] **Step 2: Verify the type does not exist yet**

Run: `ls frameworks/base/aurora/sdk/java/aurora/sdk/animation/AnimationSpec.kt`
Expected: `No such file or directory`.

- [ ] **Step 3: Write the implementation**

Create `frameworks/base/aurora/sdk/java/aurora/sdk/animation/AnimationSpec.kt`:

```kotlin
/*
 * Copyright (C) 2026 The AuroraOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package aurora.sdk.animation

import aurora.sdk.design.MotionTokens
import aurora.sdk.design.Spring
import aurora.sdk.time.Timeline

/**
 * How an animation decides where it is.
 *
 * ## Why this is sealed, and why it has exactly two branches
 *
 * Spring, decay and fling animations have **no duration**. They run until their energy is
 * spent, and how long that takes depends on the velocity they were handed. [Timeline] --
 * duration, delay, repeat, reverse -- cannot describe them. Forcing it to, by estimating a
 * settle time up front, breaks the moment such an animation is interrupted: the new velocity
 * implies a new settle time, so the timeline would have to be swapped mid-flight and the
 * state machine would need a retargeting state.
 *
 * Splitting the two here means Sprint 06B adds solvers as new files and changes nothing that
 * already exists. See ADR-002.
 *
 * Sprint 06A implements [TimedSpec] only. A [PhysicsSpec] is accepted by the type system and
 * rejected loudly by the engine, in a message naming the sprint that will implement it.
 */
sealed interface AnimationSpec

/**
 * Time decides progress.
 *
 * @param timeline where in the sequence a given elapsed time falls. Stateless, so seeking is
 *     a query rather than a rewind.
 * @param interpolator shapes the linear progress. Defaults to [Interpolator.LINEAR] because
 *     Sprint 06A ships no other.
 */
data class TimedSpec(
    val timeline: Timeline,
    val interpolator: Interpolator = Interpolator.LINEAR,
) : AnimationSpec {

    /**
     * Elapsed time at which [timeline] reports [progress].
     *
     * The inverse of [Timeline.progressAt], and the **only** place the progress-to-elapsed
     * direction is computed. Both directions of the mapping belong to this one type: scattering
     * them across `ExecutionTimeline`, `AnimationStrategy` and the handle is how the two halves
     * of an inverse pair drift apart without anyone noticing.
     *
     * ## Positions are per iteration
     *
     * [Timeline.progressAt] counts within an iteration and resets to 0 each time round, so a
     * progress value identifies a position **in one iteration**, not in the whole repeated
     * sequence. Seeking a three-times animation to 0.5 therefore lands halfway through its
     * first iteration and the remaining repeats play out from there.
     *
     * A draft of this method spanned the whole sequence instead, which reads more naturally
     * and is wrong: it made the two functions stop being inverses, and `seek(0.25f)` on a
     * three-times timeline produced a progress of 0.75. `AnimationApiTest` asserts the round
     * trip across five timeline shapes so that cannot come back.
     *
     * Out-of-range input is clamped rather than rejected, unlike [Animation.valueAt], which
     * must let an overshooting curve through. The two take different things: this takes a
     * *seek position*, which is normalised 0..1 by definition, while `valueAt` takes *eased
     * progress*, which a bouncy spring legitimately pushes past 1. `AnimationHandle.seek`
     * rejects out-of-range input loudly before it ever reaches here, so this clamp is a
     * second line rather than the policy.
     *
     * Pure arithmetic on the timeline own fields; it runs no animation, so it stays on the data.
     */
    fun elapsedForProgress(progress: Float): Long {
        val p = progress.coerceIn(0f, 1f)
        // An iteration boundary is ambiguous: it is both the end of one iteration and the
        // start of the next, and Timeline.progressAt resolves it as the start. On a timeline
        // with another iteration to come, landing exactly on the boundary would therefore
        // report progress 0 - seeking a repeating animation to its far end would snap it back
        // to its beginning. Land one nanosecond inside instead, which progressAt reports as 1.
        if (p == 1f && timeline.durationNanos > 0L &&
            (timeline.isInfinite || timeline.repeatCount > 0)
        ) {
            return timeline.delayNanos + timeline.durationNanos - 1L
        }
        return timeline.delayNanos + (timeline.durationNanos * p.toDouble()).toLong()
    }
}

/**
 * Energy decides progress. Declared in Sprint 06A, solved in Sprint 06B.
 *
 * The three properties are exactly what a solver needs and a [Timeline] cannot express: how fast
 * the motion was already going, and when it is close enough to its target to stop. Fixing their
 * shape now is what lets 06B be additive.
 *
 * ## Everything here is in normalised progress, not value units
 *
 * A solver built from this spec integrates progress from 0 toward 1 and reports it as
 * [AnimationStrategy.easedProgress]; `Animation.valueAt` then maps that into value space. That is
 * what lets a strategy be constructed from the spec alone, with no knowledge of the animation's
 * `from` and `to` — and that in turn is what keeps Sprint 06B additive, since the engine's
 * `strategyFor(spec)` never has to grow a second parameter.
 *
 * Working in normalised space costs a spring nothing. Substituting `x = from + (to - from) * p`
 * into `x'' = -k(x - target) - c * x'` leaves `p'' = -k(p - 1) - c * p'`: the `(to - from)` factor
 * cancels, so stiffness, damping and settle time are all unchanged. Only velocity scales, which
 * is why the conversion below belongs at the call site rather than in the solver.
 *
 * A caller releasing a gesture at 800 pixels per second over a 400 pixel travel therefore passes
 * `initialVelocity = 2f`, not `800f`. `AnimationService.springTo` takes value units and does that
 * division, so ordinary callers never see it.
 *
 * The default values on the implementing specs are placeholders, chosen to be plausible rather
 * than measured. Sprint 06B is the first sprint with a solver that can say whether they are
 * right, and is free to change them.
 */
sealed interface PhysicsSpec : AnimationSpec {

    /**
     * Progress per second at the moment the animation starts.
     *
     * Normalised: 1.0 means the motion was crossing its whole range every second. Divide a
     * measured gesture velocity by the distance the animation spans to get this.
     */
    val initialVelocity: Float

    /** Below this speed, in progress per second, the motion counts as stopped. */
    val restVelocity: Float

    /** Within this distance, in progress, the motion counts as arrived. */
    val restDelta: Float
}

/**
 * A spring pulling toward its target.
 *
 * Wraps a design token rather than replacing it: [Spring] says *which* spring the design
 * chose, this says *how to run it*. Two decisions made by two different people, so two types.
 */
data class SpringSpec(
    val spring: Spring = MotionTokens.SPRING_GENTLE,
    override val initialVelocity: Float = 0f,
    override val restVelocity: Float = 0.01f,
    override val restDelta: Float = 0.001f,
) : PhysicsSpec {

    init {
        require(restVelocity > 0f) {
            "restVelocity must be positive; $restVelocity would never report the motion stopped"
        }
        require(restDelta > 0f) {
            "restDelta must be positive; $restDelta would never report the motion arrived"
        }
    }
}

/** Motion coasting to a stop under friction. A fling with nothing to land on. */
data class DecaySpec(
    val friction: Float = 0.5f,
    override val initialVelocity: Float = 0f,
    override val restVelocity: Float = 0.01f,
    override val restDelta: Float = 0.001f,
) : PhysicsSpec {

    init {
        require(friction > 0f) { "friction must be positive; $friction would never settle" }
        require(restVelocity > 0f) {
            "restVelocity must be positive; $restVelocity would never report the motion stopped"
        }
        require(restDelta > 0f) {
            "restDelta must be positive; $restDelta would never report the motion arrived"
        }
    }
}

/** Motion settling onto the nearest of several resting positions. */
data class SnapSpec(
    val targets: List<Float>,
    val spring: Spring = MotionTokens.SPRING_SNAPPY,
    override val initialVelocity: Float = 0f,
    override val restVelocity: Float = 0.01f,
    override val restDelta: Float = 0.001f,
) : PhysicsSpec {

    init {
        require(targets.isNotEmpty()) { "a snap spec needs at least one target to snap to" }
        require(restVelocity > 0f) {
            "restVelocity must be positive; $restVelocity would never report the motion stopped"
        }
        require(restDelta > 0f) {
            "restDelta must be positive; $restDelta would never report the motion arrived"
        }
    }
}
```

- [ ] **Step 4: Run the architecture gate**

Run: `bash frameworks/base/aurora/tools/arch-test.sh`
Expected: `ARCH TEST PASS`.

- [ ] **Step 5: Commit**

```bash
git add frameworks/base/aurora/sdk/java/aurora/sdk/animation/AnimationSpec.kt \
        frameworks/base/aurora/tests/java/aurora/sdk/animation/AnimationApiTest.kt
git commit -m "Sprint 06A: AnimationSpec, sealed on timed vs physics"
```

---

## Task 4: `Animation`

**Files:**
- Create: `frameworks/base/aurora/sdk/java/aurora/sdk/animation/Animation.kt`
- Modify: `frameworks/base/aurora/tests/java/aurora/sdk/animation/AnimationApiTest.kt`

- [ ] **Step 1: Write the failing test**

Append to `AnimationApiTest`:

```kotlin
    // --- Animation -----------------------------------------------------------

    @Test
    fun valueAtInterpolatesBetweenFromAndTo() {
        val a = Animation("fade", TimedSpec(Timeline.ofMillis(200)), from = 0f, to = 1f)
        assertEquals(0f, a.valueAt(0f), 0f)
        assertEquals(0.5f, a.valueAt(0.5f), 0f)
        assertEquals(1f, a.valueAt(1f), 0f)
    }

    @Test
    fun valueAtHandlesADescendingRange() {
        // Dismissing a sheet animates 1 -> 0. Nothing may assume from < to.
        val a = Animation("dismiss", TimedSpec(Timeline.ofMillis(200)), from = 1f, to = 0f)
        assertEquals(1f, a.valueAt(0f), 0f)
        assertEquals(0.25f, a.valueAt(0.75f), 1e-6f)
        assertEquals(0f, a.valueAt(1f), 0f)
    }

    @Test
    fun valueAtHandlesARangeThatIsNotZeroToOne() {
        val a = Animation("slide", TimedSpec(Timeline.ofMillis(200)), from = 100f, to = 340f)
        assertEquals(220f, a.valueAt(0.5f), 1e-4f)
    }

    @Test
    fun valueAtLandsExactlyOnTheEndpoints() {
        // Not a tolerance check - a bit-exactness check, with delta 0f. An animation that
        // finishes must come to rest on `to` itself, because that is the value callers compare
        // against.
        //
        // The bounds are un-representable in binary, so `from + (to - from) * 1f` lands an ULP
        // away on some of them and this test fails under that formula. Not on all four: -5..-1.9
        // and -0.7..0.35 diverge, while 0.1..0.3 and 1.1..2.7 happen to round back. One
        // diverging pair is enough to make the test load-bearing, and the four are kept because
        // the exactness claim is about every pair, not about the ones that expose the old bug.
        //
        // Every other test in this file uses 0f or integer bounds, for which both formulas
        // agree - which is exactly why the original suite could not tell them apart.
        listOf(
            -5f to -1.9f,
            0.1f to 0.3f,
            1.1f to 2.7f,
            -0.7f to 0.35f,
        ).forEach { (from, to) ->
            val a = Animation("edge", TimedSpec(Timeline.ofMillis(200)), from = from, to = to)
            assertEquals("start of $from..$to", from, a.valueAt(0f), 0f)
            assertEquals("end of $from..$to", to, a.valueAt(1f), 0f)
        }
    }

    @Test
    fun valueAtIsPureSoTheSameProgressAlwaysGivesTheSameValue() {
        // RULE-009 at its smallest scale. If this were not exact, no amount of care in the
        // engine above it could make a replay reproduce.
        val a = Animation("fade", TimedSpec(Timeline.ofMillis(200)), from = 3f, to = 17f)
        repeat(100) { assertEquals(a.valueAt(0.37f), a.valueAt(0.37f), 0f) }
    }

    @Test
    fun valueAtDoesNotClampSoAnOvershootSurvives() {
        // A bouncy spring in 06B produces eased progress above 1. Clamping here would flatten
        // the overshoot and the bounce would silently disappear.
        val a = Animation("bounce", TimedSpec(Timeline.ofMillis(200)), from = 0f, to = 100f)
        assertEquals(110f, a.valueAt(1.1f), 1e-4f)
    }

    @Test
    fun anAnimationWithoutANameIsRejected() {
        try {
            Animation("  ", TimedSpec(Timeline.ofMillis(200)))
            fail("an unnamed animation is undiagnosable in a log")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun nonFiniteBoundsAreRejected() {
        // A NaN bound would produce NaN at every frame, forever, with nothing to point at.
        listOf(
            "NaN from" to { Animation("x", TimedSpec(Timeline.ofMillis(200)), from = Float.NaN) },
            "NaN to" to { Animation("x", TimedSpec(Timeline.ofMillis(200)), to = Float.NaN) },
            "infinite from" to
                { Animation("x", TimedSpec(Timeline.ofMillis(200)), from = Float.POSITIVE_INFINITY) },
            "infinite to" to
                { Animation("x", TimedSpec(Timeline.ofMillis(200)), to = Float.NEGATIVE_INFINITY) },
        ).forEach { (name, construct) ->
            try {
                construct()
                fail("$name must be rejected")
            } catch (expected: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun fromAndToDefaultToTheUnitRange() {
        val a = Animation("fade", TimedSpec(Timeline.ofMillis(200)))
        assertEquals(0f, a.from, 0f)
        assertEquals(1f, a.to, 0f)
    }
```

- [ ] **Step 2: Verify the type does not exist yet**

Run: `ls frameworks/base/aurora/sdk/java/aurora/sdk/animation/Animation.kt`
Expected: `No such file or directory`.

- [ ] **Step 3: Write the implementation**

Create `frameworks/base/aurora/sdk/java/aurora/sdk/animation/Animation.kt`:

```kotlin
/*
 * Copyright (C) 2026 The AuroraOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package aurora.sdk.animation

/**
 * What is being animated, described as data.
 *
 * Immutable and free of behaviour, so one instance can be played, restarted and played again
 * on a different handle with nothing to reset. That matters more than it sounds: a component
 * animating on every volume key press builds this once and reuses it.
 *
 * @param name for logs and diagnostics only. Never a lookup key -- handles are held, not
 *     looked up, so a duplicate name is harmless and a rename breaks nothing.
 * @param spec how progress is decided: [TimedSpec] now, [PhysicsSpec] from Sprint 06B
 * @param from the value at progress 0
 * @param to the value at progress 1
 *
 * ## Equality
 *
 * Generated structurally, which reaches through [spec] into its [Interpolator]. An interpolator
 * written as a lambda has identity equality, so two otherwise identical animations built with
 * separately constructed interpolators are not equal. Animations sharing [Interpolator.LINEAR],
 * which is a singleton, are. Nothing depends on this today; it is written down so that Sprint
 * 06B, which adds the second interpolator, is not surprised by it.
 */
data class Animation(
    val name: String,
    val spec: AnimationSpec,
    val from: Float = 0f,
    val to: Float = 1f,
) {

    init {
        require(name.isNotBlank()) {
            "an animation needs a name; an unnamed one is undiagnosable in a log"
        }
        // from and to are the payload. A NaN bound poisons every value the animation will ever
        // produce, silently and forever, and an infinite one is no better. Caught here, where
        // the caller that computed the bad number is still on the stack.
        require(from.isFinite()) { "animation '$name' has a non-finite from: $from" }
        require(to.isFinite()) { "animation '$name' has a non-finite to: $to" }
    }

    /**
     * The value at a given *eased* progress.
     *
     * Pure, and the entire progress-to-value mapping in Aurora: keeping it here rather than
     * inside the engine is what puts it within reach of a unit test.
     *
     * ## Why this form and not `from + (to - from) * t`
     *
     * Both are deterministic, so RULE-009 holds either way. They differ at the endpoints. The
     * other form is exact at 0 but not guaranteed exact at 1, so an animation that finishes can
     * land an ULP short of [to] — and [to] is the value a caller compares against and the
     * position the interface comes to rest at. This form is exact at both ends, in exchange for
     * giving up a guarantee of monotonicity in the interior, where an ULP of wobble is invisible.
     *
     * Deliberately unclamped. A bouncy spring produces eased progress above 1, and clamping
     * would flatten the overshoot into nothing while still looking correct. Note this is the
     * opposite of [TimedSpec.elapsedForProgress], which does clamp: that one takes a normalised
     * seek position, this one takes eased progress.
     */
    fun valueAt(easedProgress: Float): Float =
        from * (1f - easedProgress) + to * easedProgress
}
```

- [ ] **Step 4: Run the architecture gate**

Run: `bash frameworks/base/aurora/tools/arch-test.sh`
Expected: `ARCH TEST PASS`.

- [ ] **Step 5: Commit**

```bash
git add frameworks/base/aurora/sdk/java/aurora/sdk/animation/Animation.kt \
        frameworks/base/aurora/tests/java/aurora/sdk/animation/AnimationApiTest.kt
git commit -m "Sprint 06A: Animation"
```

---

## Task 5: `AnimationStrategy`, and the `FrameTime` immutability checks

**Files:**
- Create: `frameworks/base/aurora/sdk/java/aurora/sdk/animation/AnimationStrategy.kt`
- Modify: `frameworks/base/aurora/tests/java/aurora/sdk/animation/AnimationApiTest.kt`

- [ ] **Step 1: Write the failing test**

Add these imports to `AnimationApiTest.kt`:

```kotlin
import aurora.sdk.time.FrameTime
import java.lang.reflect.Modifier
```

Append to `AnimationApiTest`:

```kotlin
    // --- AnimationStrategy ---------------------------------------------------

    /** A strategy with no physics, proving the interface is implementable as declared. */
    private class HalfWayStrategy : AnimationStrategy {
        override var progress: Float = 0f
            private set
        override var easedProgress: Float = 0f
            private set
        override var isFinished: Boolean = false
            private set

        override fun advance(elapsedNanos: Long, deltaNanos: Long) {
            progress = 0.5f
            easedProgress = 0.5f
            isFinished = elapsedNanos > 0L
        }

        override fun reset() {
            progress = 0f
            easedProgress = 0f
            isFinished = false
        }

        override fun seekTo(progress: Float) = Unit
    }

    @Test
    fun aStrategyReportsBothRawAndShapedProgress() {
        // Two values, not one. The engine reads easedProgress for the value and progress for
        // diagnostics; conflating them is the contradiction this design was corrected for.
        val s = HalfWayStrategy()
        s.advance(elapsedNanos = 10L, deltaNanos = 10L)
        assertEquals(0.5f, s.progress, 0f)
        assertEquals(0.5f, s.easedProgress, 0f)
        assertTrue(s.isFinished)
    }

    @Test
    fun resetReturnsAStrategyToItsStartingState() {
        val s = HalfWayStrategy()
        s.advance(10L, 10L)
        s.reset()
        assertEquals(0f, s.progress, 0f)
        assertFalse(s.isFinished)
    }

    @Test
    fun aStrategyMayRejectSeeking() {
        // Optional operation, by design and not by omission: a spring position comes from
        // integrating its previous state, so there is no elapsed time to jump to.
        val physics = object : AnimationStrategy {
            override val progress = 0f
            override val easedProgress = 0f
            override val isFinished = false
            override fun advance(elapsedNanos: Long, deltaNanos: Long) = Unit
            override fun reset() = Unit
            override fun seekTo(progress: Float): Unit =
                throw UnsupportedOperationException("a spring cannot be seeked")
        }
        try {
            physics.seekTo(0.5f)
            fail("a physics strategy must be allowed to reject seeking")
        } catch (expected: UnsupportedOperationException) {
            // expected
        }
    }

    // --- RULE-011 and RULE-014: FrameTime is an immutable value ---------------

    @Test
    fun everyFrameTimeFieldIsFinal() {
        // RULE-014. Every animation in a frame reads the same FrameTime instance, so one callback
        // mutating it would corrupt the whole frame. FrameTime is a data class of vals today;
        // this fails the day someone adds a var.
        //
        // What it does NOT catch: a `val` holding a mutable object. `val tags: MutableList<String>`
        // is final and has no setter, yet a callback could still add to it. Reflection cannot see
        // that, so the rule that every FrameTime field must itself be of an immutable type is
        // review's job, not this test's. Said here rather than left implied, because a test that
        // is believed to prove more than it does is worse than one nobody trusts.
        FrameTime::class.java.declaredFields
            .filterNot { it.isSynthetic }
            .forEach {
                assertTrue(
                    "FrameTime.${it.name} is not final; RULE-014 requires an immutable frame",
                    Modifier.isFinal(it.modifiers)
                )
            }
    }

    @Test
    fun frameTimeExposesNoSetters() {
        val setters = FrameTime::class.java.methods.filter { it.name.startsWith("set") }
        assertTrue("FrameTime must expose no setters, found $setters", setters.isEmpty())
    }

    @Test
    fun nextKeepsDeltaAndIndexConsistentWithTheTimestamps() {
        // RULE-011 leans on this: one FrameTime is built per frame and handed out, so the
        // delta it reports must be the real gap rather than a nominal interval.
        val first = FrameTime.first(1_000L)
        val second = first.next(1_016L)
        assertEquals(1_016L, second.frameTimeNanos)
        assertEquals(16L, second.deltaNanos)
        assertEquals(1L, second.frameIndex)
    }
```

- [ ] **Step 2: Verify the type does not exist yet**

Run: `ls frameworks/base/aurora/sdk/java/aurora/sdk/animation/AnimationStrategy.kt`
Expected: `No such file or directory`.

- [ ] **Step 3: Write the implementation**

Create `frameworks/base/aurora/sdk/java/aurora/sdk/animation/AnimationStrategy.kt`:

```kotlin
/*
 * Copyright (C) 2026 The AuroraOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package aurora.sdk.animation

/**
 * Turns elapsed time into progress.
 *
 * ## The seam Sprint 06B plugs into
 *
 * `ExecutionTimeline` works out *how long this execution has been running*; a strategy works
 * out *how far through it therefore is*. Splitting them there is what lets 06B add
 * `SpringStrategy`, `DecayStrategy`, `SnapStrategy` and `FlingStrategy` as new files while
 * the handle, the registry, the driver and the state machine stay untouched. See ADR-006.
 *
 * ## The one legitimate home for mutable state
 *
 * RULE-009 puts all mutable animation state here. A physics strategy is inherently
 * incremental -- its position is the result of integrating from its previous position -- and
 * that is fine, because the sequence of [advance] calls is itself deterministic. What is not
 * fine is the same state hiding in an [Interpolator] or a design token, where seeking and
 * restarting would silently stop being repeatable.
 *
 * ## Allocation
 *
 * [advance] returns nothing and the results are read from properties. Returning a result
 * object would allocate once per animation per frame: at 120Hz with twenty animations that is
 * 2400 short-lived objects a second, on the path that runs during every gesture.
 */
interface AnimationStrategy {

    /** Unshaped progress, 0..1. For a timed animation, linear in elapsed time. */
    val progress: Float

    /**
     * Progress after shaping. Equal to [progress] when the strategy applies no curve.
     *
     * The value a caller sees is `animation.valueAt(easedProgress)`. Keeping the shaping
     * inside the strategy is what stops the engine having to ask what kind of spec it holds:
     * a [PhysicsSpec] has no interpolator to apply.
     *
     * May legitimately leave 0..1. An overshooting spring is supposed to.
     */
    val easedProgress: Float

    /**
     * Whether the motion has ended.
     *
     * A timed animation ends at the end of its timeline; a spring ends when it settles.
     */
    val isFinished: Boolean

    /**
     * Advances to [elapsedNanos].
     *
     * @param elapsedNanos time since this execution began, from `ExecutionTimeline`. Never a
     *     clock reading (RULE-008).
     * @param deltaNanos time since the previous frame. Physics integration needs it; a timed
     *     strategy ignores it, because deriving progress from elapsed time rather than
     *     accumulating deltas is what stops a dropped frame causing drift.
     */
    fun advance(elapsedNanos: Long, deltaNanos: Long)

    /** Returns to the state of a fresh execution. Called by restart. */
    fun reset()

    /**
     * Optional operation.
     *
     * A physics strategy may reject seeking with `UnsupportedOperationException`. That is a
     * deliberate part of this design, not a gap: a spring position is the result of
     * integrating from its previous state, so there is no elapsed time to jump to. See
     * ADR-002.
     *
     * Callers that must support both kinds should offer seeking only when the animation spec
     * is a [TimedSpec].
     */
    fun seekTo(progress: Float)
}
```

- [ ] **Step 4: Run the architecture gate**

Run: `bash frameworks/base/aurora/tools/arch-test.sh`
Expected: `ARCH TEST PASS`.

- [ ] **Step 5: Commit**

```bash
git add frameworks/base/aurora/sdk/java/aurora/sdk/animation/AnimationStrategy.kt \
        frameworks/base/aurora/tests/java/aurora/sdk/animation/AnimationApiTest.kt
git commit -m "Sprint 06A: AnimationStrategy, and the FrameTime immutability checks"
```

---

## Task 6: The four remaining SDK interfaces

**Files:**
- Create: `frameworks/base/aurora/sdk/java/aurora/sdk/animation/AnimationListener.kt`
- Create: `frameworks/base/aurora/sdk/java/aurora/sdk/animation/AnimationHandle.kt`
- Create: `frameworks/base/aurora/sdk/java/aurora/sdk/animation/Animator.kt`
- Create: `frameworks/base/aurora/sdk/java/aurora/sdk/animation/AnimationController.kt`
- Modify: `frameworks/base/aurora/tests/java/aurora/sdk/animation/AnimationApiTest.kt`

These four go together because each references the others; splitting them would leave
uncompilable intermediate states.

- [ ] **Step 1: Write the failing test**

Add this import to `AnimationApiTest.kt`:

```kotlin
import aurora.sdk.event.Disposable
```

Append to `AnimationApiTest`:

```kotlin
    // --- The interfaces ------------------------------------------------------

    /** A handle with no engine behind it, used to prove the defaults behave. */
    private class StubHandle(
        override val animation: Animation,
        override var state: AnimationState,
    ) : AnimationHandle {
        override val executionId = 1L
        override val progress = 0f
        override val value = 0f
        override fun play() = Unit
        override fun pause() = Unit
        override fun resume() = Unit
        override fun cancel() = Unit
        override fun restart() = Unit
        override fun seek(progress: Float) = Unit
        override fun addListener(listener: AnimationListener): Disposable =
            object : Disposable {
                override val isDisposed = false
                override fun dispose() = Unit
            }
        override fun dispose() { state = AnimationState.DISPOSED }
    }

    private fun stub(state: AnimationState) =
        StubHandle(Animation("stub", TimedSpec(Timeline.ofMillis(100))), state)

    @Test
    fun isRunningIsTrueInExactlyOneState() {
        AnimationState.values().forEach { s ->
            assertEquals("isRunning for $s", s == AnimationState.RUNNING, stub(s).isRunning)
        }
    }

    @Test
    fun isDisposedTracksTheStateRatherThanASeparateFlag() {
        // A handle is a Disposable, and two sources of truth for "is it dead" would
        // eventually disagree. isDisposed is derived, not stored.
        AnimationState.values().forEach { s ->
            assertEquals("isDisposed for $s", s == AnimationState.DISPOSED, stub(s).isDisposed)
        }
    }

    @Test
    fun aListenerNeedOverrideNothing() {
        // Both callbacks are defaulted, so 06B and 06C can add more without breaking any
        // implementor. This compiles only while that stays true.
        val silent = object : AnimationListener {}
        val h = stub(AnimationState.RUNNING)
        silent.onStateChanged(h, 1L, AnimationState.SCHEDULED, AnimationState.RUNNING)
        silent.onUpdate(h, 1L, 0.5f, 0.5f)
    }

    @Test
    fun aListenerMayOverrideJustOneCallback() {
        var updates = 0
        val listener = object : AnimationListener {
            override fun onUpdate(
                handle: AnimationHandle,
                executionId: Long,
                progress: Float,
                value: Float,
            ) {
                updates++
            }
        }
        val h = stub(AnimationState.RUNNING)
        listener.onStateChanged(h, 1L, AnimationState.SCHEDULED, AnimationState.RUNNING)
        listener.onUpdate(h, 1L, 0.5f, 0.5f)
        assertEquals(1, updates)
    }
```

- [ ] **Step 2: Verify the types do not exist yet**

Run: `ls frameworks/base/aurora/sdk/java/aurora/sdk/animation/`
Expected: only `Animation.kt`, `AnimationSpec.kt`, `AnimationState.kt`, `AnimationStrategy.kt`,
`Interpolator.kt`.

- [ ] **Step 3a: Write `AnimationListener.kt`**

```kotlin
/*
 * Copyright (C) 2026 The AuroraOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package aurora.sdk.animation

/**
 * Observes one animation.
 *
 * ## Why every callback carries an execution id
 *
 * A handle outlives its executions (RULE-012). A listener registered while execution 3 was
 * running is still attached when `restart()` begins execution 4, and without the id there is
 * nothing in the callback to say which run it belongs to. That is a bug class which is
 * extremely hard to find, because the callback looks correct in isolation and only misbehaves
 * when a component holds state across runs.
 *
 * ## Why both methods are defaulted
 *
 * So the interface can grow. Sprint 06B and 06C will want more callbacks, and an implementor
 * that overrode only [onUpdate] must not stop compiling when they arrive.
 *
 * ## Threading
 *
 * Callbacks arrive on whichever thread called `AnimationController.tick`, which on device is
 * the frame thread. Do no work here that a frame cannot afford, and expect re-entrancy: it is
 * legal to start, cancel or dispose animations from inside a callback, and the engine defers
 * the structural effect to the end of the frame (RULE-013).
 */
interface AnimationListener {

    /**
     * The animation moved between lifecycle states.
     *
     * Not called when a state transition is a no-op, such as cancelling an already cancelled
     * animation, so a subscriber counting transitions counts real ones.
     */
    fun onStateChanged(
        handle: AnimationHandle,
        executionId: Long,
        from: AnimationState,
        to: AnimationState,
    ) {
    }

    /**
     * The animation advanced.
     *
     * @param progress unshaped, 0..1
     * @param value the animated value: `handle.animation.valueAt(easedProgress)`
     */
    fun onUpdate(
        handle: AnimationHandle,
        executionId: Long,
        progress: Float,
        value: Float,
    ) {
    }
}
```

- [ ] **Step 3b: Write `AnimationHandle.kt`**

```kotlin
/*
 * Copyright (C) 2026 The AuroraOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package aurora.sdk.animation

import aurora.sdk.event.Disposable

/**
 * A running animation, from the caller point of view.
 *
 * ## Three rules cover the whole surface
 *
 * 1. **Queries never throw.** [state], [isRunning], [progress], [value], [executionId] and
 *    [animation] are readable in every state including [AnimationState.DISPOSED]. They read
 *    volatile fields, take no lock and trigger no lazy computation, so reading one from a
 *    listener during a frame costs nothing.
 * 2. **Repeating a call that has already taken effect is not an error.** [dispose] on a disposed
 *    handle, [cancel] on a resting one and [pause] on a paused one all do nothing rather than
 *    throwing. Teardown paths run more than once far more often than anyone expects, and a
 *    throwing second call turns a harmless duplicate into a crash. This is the same reasoning
 *    [Disposable] already records.
 * 3. **[play], [pause], [resume], [restart] and [seek] throw [IllegalStateException]** when the
 *    current state does not permit them (RULE-003). [cancel] throws only on a disposed handle.
 *    After [dispose], everything throws except the queries and [dispose] itself.
 *
 * Rule 2 applies only while the handle is alive: `dispose(); cancel()` throws, because the
 * handle is gone, not because cancelling twice is wrong.
 *
 * ## Threading
 *
 * Not thread safe. Every mutating call — [play], [pause], [resume], [cancel], [restart], [seek],
 * [dispose], [addListener] — must happen on the same thread that drives
 * `AnimationController.tick`, which on device is the frame thread. The queries are volatile
 * reads and are safe from anywhere.
 *
 * This is a contract, not an oversight. Making the engine thread safe would mean locking on the
 * path that runs for every animation on every frame, to serve callers who almost always are on
 * the frame thread already. A caller genuinely elsewhere should post to the frame thread rather
 * than have every animation pay for it. RULE-013 is about re-entrancy from a listener, which is
 * a different problem and is handled.
 *
 * ## Reuse
 *
 * A completed or cancelled handle is *resting*, not dead: [restart] begins a new execution on
 * the same object. Volume, Dynamic Island and Control Center re-run the same animation
 * constantly, and allocating a fresh handle each time would put garbage on a gesture path.
 * Only [dispose] is final.
 */
interface AnimationHandle : Disposable {

    /** What this handle plays. Immutable, so it survives any number of executions. */
    val animation: Animation

    /** Where the handle is in its lifecycle. */
    val state: AnimationState

    /**
     * Which run this is. Starts at 0 and increments on every [restart].
     *
     * RULE-012. Compare it against the id a callback carries before acting on state held
     * across runs.
     */
    val executionId: Long

    /** Unshaped progress of the current execution, 0..1. */
    val progress: Float

    /** The animated value: `animation.valueAt(easedProgress)`. */
    val value: Float

    /**
     * Whether this animation is mid-execution.
     *
     * True from the first tick until the animation ends or is held. It does not mean frames are
     * currently arriving — see [AnimationState.RUNNING]. A caller that needs to know whether the
     * engine itself is ticking asks [AnimationController.isRunning].
     */
    val isRunning: Boolean
        get() = state == AnimationState.RUNNING

    /** Derived from [state], never stored separately: two sources of truth would drift. */
    override val isDisposed: Boolean
        get() = state == AnimationState.DISPOSED

    /** Schedules the first execution. Legal only from [AnimationState.IDLE]. */
    fun play()

    /**
     * Holds the animation where it is.
     *
     * Legal from [AnimationState.SCHEDULED] as well as [AnimationState.RUNNING]: forbidding
     * the former would make the outcome depend on whether a frame happened to arrive first,
     * which is API behaviour varying with machine load.
     *
     * Throws from [AnimationState.IDLE] and from either resting state: there is no execution to
     * hold. Calling it on an already paused handle does nothing.
     */
    fun pause()

    /**
     * Continues the paused execution, losing no elapsed time.
     *
     * Legal only from [AnimationState.PAUSED]. Resuming a completed or cancelled animation
     * throws -- that is what [restart] is for, and collapsing the two would make the API
     * unable to say which the caller meant.
     */
    fun resume()

    /**
     * Stops the execution where it stands.
     *
     * Does not jump to the end value. An animation cancelled mid-flight should stay where the
     * user last saw it, or the interface appears to teleport.
     */
    fun cancel()

    /** Begins a new execution with a fresh [executionId]. Legal from every state but disposed. */
    fun restart()

    /**
     * Moves the current execution to [progress].
     *
     * Legal from [AnimationState.SCHEDULED], [AnimationState.RUNNING] and
     * [AnimationState.PAUSED] only: seeking positions a live execution, and a finished one has
     * no position to move. Scrubbing a finished animation is `restart(); pause(); seek(p)`.
     *
     * Throws `UnsupportedOperationException` when the animation spec is a [PhysicsSpec]; see
     * [AnimationStrategy.seekTo].
     */
    fun seek(progress: Float)

    /**
     * Observes this handle.
     *
     * @return a handle that unsubscribes. Registering during a callback is safe; the listener
     *     starts receiving from the next dispatch.
     *
     * Listeners are released when the handle is disposed, so a component that disposes its
     * handle does not also have to dispose each subscription to avoid retaining itself.
     */
    fun addListener(listener: AnimationListener): Disposable
}
```

- [ ] **Step 3c: Write `Animator.kt`**

```kotlin
/*
 * Copyright (C) 2026 The AuroraOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package aurora.sdk.animation

/**
 * What feature code calls to animate something.
 *
 * Deliberately small. Everything about *running* an animation lives on the
 * [AnimationHandle] it returns; this interface only makes them.
 *
 * ## One value per handle
 *
 * A handle animates a single `Float`. A component animating several things at once — a shape
 * morph with a width, a height and a corner radius — runs one handle per value. They cannot
 * drift apart, because RULE-011 gives every animation in a frame the same `FrameTime`, but
 * keeping their lifecycles in step is the caller's job. If that becomes a burden, the answer is
 * a composite handle layered on top of this interface, in the shape of
 * `aurora.sdk.event.CompositeDisposable` — not a wider value type here.
 */
interface Animator {

    /**
     * Makes a handle without starting it.
     *
     * The reason this exists alongside [play]: listeners attached before the first frame are
     * guaranteed to observe every update of the first execution. Attaching after `play()` is
     * a race with the frame source on device, and a race is not something a caller should have
     * to reason about.
     */
    fun create(animation: Animation): AnimationHandle

    /** [create] followed by [AnimationHandle.play]. */
    fun play(animation: Animation): AnimationHandle

    /**
     * Cancels every animation this animator is currently driving.
     *
     * Cancels, not disposes: the handles stay usable, so a caller holding one can restart it.
     */
    fun cancelAll()

    /** How many animations are scheduled or running. For diagnostics and tests. */
    val activeCount: Int
}
```

- [ ] **Step 3d: Write `AnimationController.kt`**

```kotlin
/*
 * Copyright (C) 2026 The AuroraOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package aurora.sdk.animation

import aurora.sdk.time.FrameTime

/**
 * The animation engine, from below.
 *
 * Where [Animator] is what feature code calls, this is what the *frame source* calls. Two
 * audiences, two interfaces, so neither has to see the other methods.
 *
 * ## Why tick is public contract rather than a runtime detail
 *
 * Both drivers must enter through the same door:
 *
 * ```
 *   Host test  -->  AnimationController.tick(FrameTime)  <--  Android Choreographer
 * ```
 *
 * That is what turns RULE-009 from a convention into something a test can assert. A host test
 * hands out frames at any spacing it likes, including pathological ones, and Sprint 08
 * `ChoreographerAnimationDriver` calls exactly the same method.
 */
interface AnimationController {

    /** The animator backed by this engine. */
    val animator: Animator

    /** Whether the engine accepts frames. */
    val isRunning: Boolean

    /** Begins accepting frames. */
    fun start()

    /**
     * Stops accepting frames.
     *
     * Running animations are left where they are rather than cancelled, so a display turning
     * off does not visibly reset the interface when it comes back.
     *
     * In-flight handles keep reporting [AnimationState.RUNNING] while stopped. That is
     * deliberate — they have not ended and have not been held — but it means a caller holding
     * only a handle cannot tell a stopped engine from a slow animation. [isRunning] on this
     * interface is the answer for anyone who needs to.
     */
    fun stop()

    /**
     * The only legal entry point of time into the animation engine.
     *
     * Exactly one [FrameTime] is built per frame and handed to every animation (RULE-011), so
     * animations cannot drift apart. The instance must not be mutated (RULE-014).
     *
     * @throws IllegalArgumentException if [FrameTime.frameIndex] does not increase --
     *     RULE-006 monotonicity, applied to frames
     * @throws IllegalStateException if the engine was not started
     */
    fun tick(frameTime: FrameTime)
}
```

- [ ] **Step 4: Run the architecture gate**

Run: `bash frameworks/base/aurora/tools/arch-test.sh`
Expected: `ARCH TEST PASS`.

- [ ] **Step 5: Commit**

```bash
git add frameworks/base/aurora/sdk/java/aurora/sdk/animation/ \
        frameworks/base/aurora/tests/java/aurora/sdk/animation/AnimationApiTest.kt
git commit -m "Sprint 06A: AnimationHandle, AnimationListener, Animator, AnimationController

Four interfaces that reference each other, so they land together.

isDisposed is derived from state rather than stored: two sources of truth
for is-it-dead would eventually disagree.

Both listener callbacks are defaulted, so 06B and 06C can add more without
breaking any implementor, and every callback carries an executionId so a
listener from run 3 can tell it is being handed an event from run 4.

Animator.create() exists alongside play() because attaching a listener after
play() races the frame source on device, and a caller should not have to
reason about that race.

tick() is public contract, not a runtime detail: host tests and the Sprint 08
Choreographer bridge must enter through the same door, which is what makes
RULE-009 assertable."
```

---

## Task 7: VM checkpoint 1 -- the SDK compiles

The SDK layer is complete. Prove it before building the runtime on top of it.

- [ ] **Step 1: Sync to the VM**

Run: `.\sync-to-vm.ps1`
Expected: ends with `=== APPLY_DONE_OK ===`.

- [ ] **Step 2: Compile**

On the VM:
```bash
cd /mnt/build/lineage
source build/envsetup.sh
lunch lineage_sdk_phone_x86_64-bp4a-userdebug
m aurora-sdk
```
Expected: `#### build completed successfully ####`.

If Kotlin rejects `sealed interface`, the tree Kotlin version predates 1.5; report that rather
than working around it, because the whole spec-branch design rests on it.

- [ ] **Step 3: Build and run the tests**

```bash
m aurora-platform-tests
java -cp out/host/linux-x86/testcases/aurora-platform-tests/aurora-platform-tests.jar \
     org.junit.runner.JUnitCore aurora.sdk.animation.AnimationApiTest
```
Expected: `OK (N tests)`.

- [ ] **Step 4: Record the result**

If anything fails, fix it in a commit of its own before starting Task 8. Do not begin the
runtime layer on an SDK that does not compile -- every later error would have two possible
causes.

---

## Task 8: `AnimationStateMachine`

**Files:**
- Create: `frameworks/base/aurora/runtime/java/aurora/runtime/animation/AnimationStateMachine.kt`
- Test: `frameworks/base/aurora/tests/java/aurora/runtime/animation/AnimationStateMachineTest.kt`

The most important piece of the sprint, and the easiest to test exhaustively because it is a
pure function with no clock, no registry, no listener and no handle.

- [ ] **Step 1: Write the failing test**

Create `frameworks/base/aurora/tests/java/aurora/runtime/animation/AnimationStateMachineTest.kt`:

```kotlin
/*
 * Copyright (C) 2026 The AuroraOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package aurora.runtime.animation

import aurora.sdk.animation.AnimationState
import aurora.sdk.animation.AnimationState.CANCELLED
import aurora.sdk.animation.AnimationState.COMPLETED
import aurora.sdk.animation.AnimationState.DISPOSED
import aurora.sdk.animation.AnimationState.IDLE
import aurora.sdk.animation.AnimationState.PAUSED
import aurora.sdk.animation.AnimationState.RUNNING
import aurora.sdk.animation.AnimationState.SCHEDULED
import aurora.runtime.animation.AnimationEvent.CANCEL
import aurora.runtime.animation.AnimationEvent.DISPOSE
import aurora.runtime.animation.AnimationEvent.FINISH
import aurora.runtime.animation.AnimationEvent.PAUSE
import aurora.runtime.animation.AnimationEvent.PLAY
import aurora.runtime.animation.AnimationEvent.RESTART
import aurora.runtime.animation.AnimationEvent.RESUME
import aurora.runtime.animation.AnimationEvent.TICK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Every cell of the transition table, legal and illegal alike.
 *
 * Seven states times eight events is fifty-six outcomes, and all fifty-six are named below
 * rather than sampled. A state machine tested by example is tested only where someone
 * happened to look, and the cells nobody looks at are exactly where an interface ends up
 * wedged in a state it cannot leave.
 */
class AnimationStateMachineTest {

    /**
     * The whole table. `null` means the event is illegal in that state.
     *
     * Read it against the spec: docs/specs/2026-08-02-sprint-06a-animation-architecture-design.md
     */
    private val table: Map<Pair<AnimationState, AnimationEvent>, AnimationState?> = mapOf(
        //                       PLAY        TICK      PAUSE     RESUME    CANCEL      RESTART     FINISH      DISPOSE
        (IDLE to PLAY) to SCHEDULED,
        (IDLE to TICK) to null,
        (IDLE to PAUSE) to null,
        (IDLE to RESUME) to null,
        (IDLE to CANCEL) to CANCELLED,
        (IDLE to RESTART) to SCHEDULED,
        (IDLE to FINISH) to null,
        (IDLE to DISPOSE) to DISPOSED,

        (SCHEDULED to PLAY) to null,
        (SCHEDULED to TICK) to RUNNING,
        (SCHEDULED to PAUSE) to PAUSED,
        (SCHEDULED to RESUME) to null,
        (SCHEDULED to CANCEL) to CANCELLED,
        (SCHEDULED to RESTART) to SCHEDULED,
        (SCHEDULED to FINISH) to null,
        (SCHEDULED to DISPOSE) to DISPOSED,

        (RUNNING to PLAY) to null,
        (RUNNING to TICK) to RUNNING,
        (RUNNING to PAUSE) to PAUSED,
        (RUNNING to RESUME) to null,
        (RUNNING to CANCEL) to CANCELLED,
        (RUNNING to RESTART) to SCHEDULED,
        (RUNNING to FINISH) to COMPLETED,
        (RUNNING to DISPOSE) to DISPOSED,

        (PAUSED to PLAY) to null,
        (PAUSED to TICK) to null,
        (PAUSED to PAUSE) to PAUSED,
        (PAUSED to RESUME) to RUNNING,      // with the default pausedFrom
        (PAUSED to CANCEL) to CANCELLED,
        (PAUSED to RESTART) to SCHEDULED,
        (PAUSED to FINISH) to null,
        (PAUSED to DISPOSE) to DISPOSED,

        (COMPLETED to PLAY) to null,
        (COMPLETED to TICK) to null,
        (COMPLETED to PAUSE) to null,
        (COMPLETED to RESUME) to null,
        (COMPLETED to CANCEL) to COMPLETED, // idempotent no-op
        (COMPLETED to RESTART) to SCHEDULED,
        (COMPLETED to FINISH) to null,
        (COMPLETED to DISPOSE) to DISPOSED,

        (CANCELLED to PLAY) to null,
        (CANCELLED to TICK) to null,
        (CANCELLED to PAUSE) to null,
        (CANCELLED to RESUME) to null,
        (CANCELLED to CANCEL) to CANCELLED, // idempotent no-op
        (CANCELLED to RESTART) to SCHEDULED,
        (CANCELLED to FINISH) to null,
        (CANCELLED to DISPOSE) to DISPOSED,

        (DISPOSED to PLAY) to null,
        (DISPOSED to TICK) to null,
        (DISPOSED to PAUSE) to null,
        (DISPOSED to RESUME) to null,
        (DISPOSED to CANCEL) to null,
        (DISPOSED to RESTART) to null,
        (DISPOSED to FINISH) to null,
        (DISPOSED to DISPOSE) to DISPOSED,  // idempotent
    )

    @Test
    fun theTableCoversEveryCell() {
        // Guards the test itself. A missing entry would silently reduce coverage.
        assertEquals(
            AnimationState.values().size * AnimationEvent.values().size,
            table.size
        )
    }

    @Test
    fun everyLegalCellProducesTheExpectedState() {
        table.forEach { (key, expected) ->
            val (from, event) = key
            if (expected == null) return@forEach
            assertEquals("$from + $event", expected, AnimationStateMachine.next(from, event))
        }
    }

    @Test
    fun everyIllegalCellThrows() {
        table.forEach { (key, expected) ->
            val (from, event) = key
            if (expected != null) return@forEach
            try {
                val got = AnimationStateMachine.next(from, event)
                fail("$from + $event should be illegal but produced $got")
            } catch (expectedFailure: IllegalStateException) {
                assertNotNull(expectedFailure.message)
                assertTrue(
                    "the message must name the state and the event, got: ${expectedFailure.message}",
                    expectedFailure.message!!.contains(from.name) &&
                        expectedFailure.message!!.contains(event.name)
                )
            }
        }
    }

    @Test
    fun canTransitionAgreesWithNext() {
        table.forEach { (key, expected) ->
            val (from, event) = key
            assertEquals(
                "canTransition disagrees with next for $from + $event",
                expected != null,
                AnimationStateMachine.canTransition(from, event)
            )
        }
    }

    // --- The cases the sprint contract calls out by name ----------------------

    @Test
    fun cancelThenResumeFails() {
        // Named in the Sprint 06A contract. Cancelling ends the execution; resuming would
        // have to continue one that no longer exists.
        val cancelled = AnimationStateMachine.next(RUNNING, CANCEL)
        assertEquals(CANCELLED, cancelled)
        try {
            AnimationStateMachine.next(cancelled, RESUME)
            fail("resuming a cancelled animation must fail")
        } catch (expected: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun cancelThenRestartSucceeds() {
        // The most common gesture case: a swipe interrupted, then started again. RULE-012.
        val cancelled = AnimationStateMachine.next(RUNNING, CANCEL)
        assertEquals(SCHEDULED, AnimationStateMachine.next(cancelled, RESTART))
    }

    @Test
    fun resumeReturnsToWhicheverStateThePauseCameFrom() {
        // Pausing before the first frame is legal, so resuming has to be able to go back to
        // SCHEDULED rather than assuming RUNNING. Without this, an animation paused before it
        // started would be treated as already running and lose its first frame.
        assertEquals(RUNNING, AnimationStateMachine.next(PAUSED, RESUME, pausedFrom = RUNNING))
        assertEquals(SCHEDULED, AnimationStateMachine.next(PAUSED, RESUME, pausedFrom = SCHEDULED))
    }

    @Test
    fun resumeRejectsAPausedFromThatNoPauseCouldHaveProduced() {
        // The 56-cell table only ever supplies RUNNING or SCHEDULED, so this corner of the input
        // domain is otherwise unexercised. Without the guard, RESUME would hand back DISPOSED as
        // the next state without DISPOSE ever being dispatched.
        listOf(IDLE, PAUSED, COMPLETED, CANCELLED, DISPOSED).forEach { bogus ->
            assertFalse(
                "pausedFrom=$bogus must not be a legal resume target",
                AnimationStateMachine.canTransition(PAUSED, RESUME, pausedFrom = bogus)
            )
            try {
                AnimationStateMachine.next(PAUSED, RESUME, pausedFrom = bogus)
                fail("resume with pausedFrom=$bogus must fail")
            } catch (expected: IllegalStateException) {
                assertTrue(
                    "the message must name the offending pausedFrom, got: ${expected.message}",
                    expected.message!!.contains(bogus.name)
                )
            }
        }
    }

    @Test
    fun disposeIsLegalFromEveryStateAndAlwaysTerminal() {
        AnimationState.values().forEach {
            assertEquals("dispose from $it", DISPOSED, AnimationStateMachine.next(it, DISPOSE))
        }
    }

    @Test
    fun nothingButDisposeEscapesDisposed() {
        AnimationEvent.values().filter { it != DISPOSE }.forEach { event ->
            assertFalse(
                "$event must be illegal on a disposed handle",
                AnimationStateMachine.canTransition(DISPOSED, event)
            )
        }
    }

    @Test
    fun theMachineHoldsNoState() {
        // A pure function: calling it a thousand times in any order changes nothing. This is
        // what makes the rest of RULE-009 possible, so it is asserted rather than assumed.
        repeat(1000) {
            assertEquals(SCHEDULED, AnimationStateMachine.next(IDLE, PLAY))
            assertEquals(CANCELLED, AnimationStateMachine.next(RUNNING, CANCEL))
            assertEquals(COMPLETED, AnimationStateMachine.next(RUNNING, FINISH))
        }
    }
}
```

- [ ] **Step 2: Verify the type does not exist yet**

Run: `ls frameworks/base/aurora/runtime/java/aurora/runtime/animation/`
Expected: `No such file or directory`.

- [ ] **Step 3: Write the implementation**

Create `frameworks/base/aurora/runtime/java/aurora/runtime/animation/AnimationStateMachine.kt`:

```kotlin
/*
 * Copyright (C) 2026 The AuroraOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package aurora.runtime.animation

import aurora.sdk.animation.AnimationState
import aurora.sdk.animation.AnimationState.CANCELLED
import aurora.sdk.animation.AnimationState.COMPLETED
import aurora.sdk.animation.AnimationState.DISPOSED
import aurora.sdk.animation.AnimationState.IDLE
import aurora.sdk.animation.AnimationState.PAUSED
import aurora.sdk.animation.AnimationState.RUNNING
import aurora.sdk.animation.AnimationState.SCHEDULED

/**
 * What can happen to an animation.
 *
 * The alphabet of the machine, and deliberately not part of the SDK: RULE-010 gives the SDK
 * the nouns (the states a caller can observe) and leaves the runtime the verbs of its own
 * implementation. Seeking is absent because it moves an execution without changing its state.
 */
enum class AnimationEvent {

    /** A caller started the first execution. */
    PLAY,

    /** A frame arrived. */
    TICK,

    /** A caller held the animation. */
    PAUSE,

    /** A caller continued a held animation. */
    RESUME,

    /** A caller stopped the execution where it stood. */
    CANCEL,

    /** A caller began a new execution. */
    RESTART,

    /** The strategy reported that the motion ended. */
    FINISH,

    /** A caller finished with the handle for good. */
    DISPOSE,
}

/**
 * The animation lifecycle, as a pure function.
 *
 * No fields, no clock, no registry, no listener, no handle. Given a state and an event it
 * returns the next state or refuses, and it will answer the same way a million times running.
 * That is what makes the whole of RULE-009 reachable: if the lifecycle itself could drift,
 * nothing built on top of it could be deterministic.
 *
 * It is also why this is the piece with the most exhaustive test in the sprint -- fifty-six
 * cells, every one named.
 */
object AnimationStateMachine {

    /** Whether [event] is legal in [from]. Never throws. */
    @JvmStatic
    @JvmOverloads
    fun canTransition(
        from: AnimationState,
        event: AnimationEvent,
        pausedFrom: AnimationState = RUNNING,
    ): Boolean = nextOrNull(from, event, pausedFrom) != null

    /**
     * The state after [event].
     *
     * @param pausedFrom which state a [AnimationEvent.PAUSE] came from, so
     *     [AnimationEvent.RESUME] returns to it. Ignored for every other event.
     * @throws IllegalStateException when the event is not legal in [from]. Loudly, per
     *     RULE-003: a lifecycle call that quietly did nothing would leave the caller believing
     *     an animation was running when it was not.
     */
    @JvmStatic
    @JvmOverloads
    fun next(
        from: AnimationState,
        event: AnimationEvent,
        pausedFrom: AnimationState = RUNNING,
    ): AnimationState = nextOrNull(from, event, pausedFrom)
        ?: throw IllegalStateException(
            if (event == AnimationEvent.RESUME && from == PAUSED) {
                "RESUME from PAUSED is not legal with pausedFrom=$pausedFrom; a pause can only " +
                    "have come from SCHEDULED or RUNNING"
            } else {
                "$event is not legal in state $from"
            }
        )

    private fun nextOrNull(
        from: AnimationState,
        event: AnimationEvent,
        pausedFrom: AnimationState,
    ): AnimationState? = when (event) {

        // Legal everywhere, and idempotent. Teardown paths run more than once.
        AnimationEvent.DISPOSE -> DISPOSED

        AnimationEvent.PLAY -> if (from == IDLE) SCHEDULED else null

        // A tick starts a scheduled execution and keeps a running one running. Illegal
        // anywhere else: the registry does not tick a paused or resting handle, so a tick
        // arriving in those states means the engine lost track of something.
        AnimationEvent.TICK -> when (from) {
            SCHEDULED, RUNNING -> RUNNING
            else -> null
        }

        // Legal from SCHEDULED as well as RUNNING. Forbidding the former would make the
        // outcome of play() then pause() depend on whether a frame happened to arrive in
        // between, which is API behaviour varying with machine load.
        AnimationEvent.PAUSE -> when (from) {
            SCHEDULED, RUNNING, PAUSED -> PAUSED
            else -> null
        }

        // Only from PAUSED, and back to wherever the pause came from. Resuming continues an
        // execution; restarting begins one. A resting animation has no execution to continue.
        //
        // pausedFrom is validated rather than trusted. A pause can only have come from a state
        // that was about to advance, and returning anything else would let RESUME produce a
        // state no event ever transitioned to - handing back DISPOSED without DISPOSE having
        // been dispatched. The one caller cannot do this today; the machine does not rely on
        // that (RULE-003).
        AnimationEvent.RESUME -> when {
            from != PAUSED -> null
            pausedFrom != RUNNING && pausedFrom != SCHEDULED -> null
            else -> pausedFrom
        }

        AnimationEvent.CANCEL -> when (from) {
            IDLE, SCHEDULED, RUNNING, PAUSED -> CANCELLED
            COMPLETED, CANCELLED -> from
            DISPOSED -> null
        }

        // RULE-012: a resting handle is reusable. Only a disposed one is not.
        AnimationEvent.RESTART -> if (from == DISPOSED) null else SCHEDULED

        AnimationEvent.FINISH -> if (from == RUNNING) COMPLETED else null
    }
}
```

- [ ] **Step 4: Run the architecture gate**

Run: `bash frameworks/base/aurora/tools/arch-test.sh`
Expected: `ARCH TEST PASS`. In particular `runtime: no call to System.nanoTime` must still
hold -- the machine has no notion of time at all.

- [ ] **Step 5: Commit**

```bash
git add frameworks/base/aurora/runtime/java/aurora/runtime/animation/AnimationStateMachine.kt \
        frameworks/base/aurora/tests/java/aurora/runtime/animation/AnimationStateMachineTest.kt
git commit -m "Sprint 06A: AnimationStateMachine, with all 56 cells asserted

A pure function: no fields, no clock, no registry, no listener, no handle.
If the lifecycle itself could drift, nothing built on it could be
deterministic, so this is where RULE-009 starts.

The test names all seven states times eight events rather than sampling. A
state machine tested by example is tested only where someone happened to
look, and the unlooked-at cells are exactly where an interface ends up
wedged in a state it cannot leave.

PAUSE is legal from SCHEDULED. Forbidding it would make play()-then-pause()
throw or not depending on whether a frame arrived first - API behaviour
varying with machine load, which RULE-009 exists to forbid. RESUME therefore
takes pausedFrom and returns there."
```

---

## Task 9: `ExecutionTimeline`

**Files:**
- Create: `frameworks/base/aurora/runtime/java/aurora/runtime/animation/ExecutionTimeline.kt`
- Test: `frameworks/base/aurora/tests/java/aurora/runtime/animation/AnimationLifecycleTest.kt`

Renamed from the brief `TimelineRunner`: after ADR-006 it holds no `Timeline` and computes no
progress, so the old name would leave a reader asking which timeline.

- [ ] **Step 1: Write the failing test**

Create `frameworks/base/aurora/tests/java/aurora/runtime/animation/AnimationLifecycleTest.kt`:

```kotlin
/*
 * Copyright (C) 2026 The AuroraOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package aurora.runtime.animation

import aurora.sdk.time.AuroraClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The lifecycle of one animation, driven frame by frame with no clock and no device.
 *
 * Every timestamp here is a number the test chose. Nothing sleeps, nothing samples a clock,
 * and every assertion is exact -- which is the whole reason RULE-008 hands frame time to the
 * engine rather than letting it fetch its own.
 */
class AnimationLifecycleTest {

    private val ms = AuroraClock.NANOS_PER_MILLI

    // --- ExecutionTimeline ---------------------------------------------------

    @Test
    fun elapsedIsMeasuredFromTheFirstFrameNotFromZero() {
        // Frame timestamps come from a monotonic source with an arbitrary origin, often a
        // large number. An execution that treated the raw timestamp as elapsed would finish
        // instantly.
        val t = ExecutionTimeline()
        assertEquals(0L, t.advanceTo(9_000_000_000L))
        assertEquals(16 * ms, t.advanceTo(9_000_000_000L + 16 * ms))
    }

    @Test
    fun elapsedTracksTheFrameTimestampsRatherThanCountingFrames()
    {
        // A dropped frame makes the real gap a multiple of the nominal interval. Counting
        // frames would drift; measuring from timestamps cannot.
        val t = ExecutionTimeline()
        t.advanceTo(0L)
        t.advanceTo(16 * ms)
        assertEquals(116 * ms, t.advanceTo(116 * ms))   // 100ms hitch, one frame delivered
    }

    @Test
    fun aPauseCostsNoElapsedTime() {
        // The heart of pause. Time passes in the world while an animation is held; none of it
        // belongs to the animation.
        val t = ExecutionTimeline()
        t.advanceTo(0L)
        assertEquals(50 * ms, t.advanceTo(50 * ms))

        t.pause()
        // 500ms of wall time goes by. The handle is not ticked while paused.
        t.resume()

        // The first frame after resuming reports the elapsed time it was paused at.
        assertEquals(50 * ms, t.advanceTo(550 * ms))
        // And then time advances normally again.
        assertEquals(66 * ms, t.advanceTo(566 * ms))
    }

    @Test
    fun pausingBeforeTheFirstFrameLosesNothing() {
        // play() then pause() before a frame arrives is legal (see the state machine). There
        // is no elapsed time to preserve, so the first frame after resuming is the first frame.
        val t = ExecutionTimeline()
        t.pause()
        t.resume()
        assertEquals(0L, t.advanceTo(1_000 * ms))
        assertEquals(16 * ms, t.advanceTo(1_016 * ms))
    }

    @Test
    fun repeatedPausesAndResumesEachCostNothing() {
        val t = ExecutionTimeline()
        t.advanceTo(0L)
        t.advanceTo(10 * ms)

        t.pause(); t.resume()
        assertEquals(10 * ms, t.advanceTo(100 * ms))

        t.pause(); t.resume()
        assertEquals(10 * ms, t.advanceTo(500 * ms))

        assertEquals(26 * ms, t.advanceTo(516 * ms))
    }

    @Test
    fun seekingBeforeTheFirstFrameStartsThere() {
        val t = ExecutionTimeline()
        t.seekTo(80 * ms)
        assertEquals(80 * ms, t.elapsedNanos)
        assertEquals(80 * ms, t.advanceTo(5_000 * ms))
        assertEquals(96 * ms, t.advanceTo(5_016 * ms))
    }

    @Test
    fun seekingAfterStartingMovesTheOrigin() {
        val t = ExecutionTimeline()
        t.advanceTo(0L)
        t.advanceTo(30 * ms)
        t.seekTo(200 * ms)
        assertEquals(200 * ms, t.elapsedNanos)
        assertEquals(216 * ms, t.advanceTo(46 * ms))
    }

    @Test
    fun seekingWhilePausedSurvivesTheResume() {
        // Scrubbing a paused animation. If the resume shift and the seek both applied, the
        // position would jump by the length of the pause the moment the finger lifted.
        val t = ExecutionTimeline()
        t.advanceTo(0L)
        t.advanceTo(30 * ms)
        t.pause()
        t.seekTo(500 * ms)
        t.resume()
        assertEquals(500 * ms, t.advanceTo(9_000 * ms))
    }

    @Test
    fun seekingBackwardsIsAllowedBecauseTheClockIsNotMoving() {
        // RULE-006 forbids a clock going backwards. An execution timeline is not a clock: it
        // is a position, and moving a position back is what scrubbing means.
        val t = ExecutionTimeline()
        t.advanceTo(0L)
        t.advanceTo(200 * ms)
        t.seekTo(50 * ms)
        assertEquals(50 * ms, t.elapsedNanos)
    }

    @Test
    fun seekingToANegativeElapsedIsRejected() {
        try {
            ExecutionTimeline().seekTo(-1L)
            fail("an execution cannot be positioned before it began")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun tickingAPausedExecutionIsRejected() {
        // Without this the paused interval would be counted as elapsed - a plausible-looking
        // wrong number rather than a failure. The state machine makes it unreachable; the class
        // does not rely on that.
        val t = ExecutionTimeline()
        t.advanceTo(0L)
        t.pause()
        try {
            t.advanceTo(500 * ms)
            fail("a paused execution must not advance")
        } catch (expected: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun pausingBeforeTheFirstFrameAlsoRefusesToBeTicked() {
        // The pause-before-start case, which cannot be inferred from the frame bookkeeping and
        // is why the flag is explicit.
        val t = ExecutionTimeline()
        t.pause()
        try {
            t.advanceTo(0L)
            fail("a paused execution must not advance, started or not")
        } catch (expected: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun aFrameTimeThatGoesBackwardsIsRejected() {
        val t = ExecutionTimeline()
        t.advanceTo(100 * ms)
        try {
            t.advanceTo(50 * ms)
            fail("frame time is monotonic (RULE-006)")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun theSameFrameTimeTwiceIsAllowed() {
        // Two animations advanced to one frame is normal; only going backwards is not. Repeating
        // a timestamp must leave elapsed where it was, because no time has passed.
        //
        // The second frame is the one repeated, not the first. Repeating the first would compare
        // 0 against 0 and pass no matter what the class did, since the opening frame is what
        // establishes the origin.
        val t = ExecutionTimeline()
        t.advanceTo(100 * ms)
        assertEquals(16 * ms, t.advanceTo(116 * ms))
        assertEquals(16 * ms, t.advanceTo(116 * ms))
    }

    @Test
    fun resumingClearsThePauseSoTickingWorksAgain() {
        val t = ExecutionTimeline()
        t.advanceTo(0L)
        t.pause()
        t.resume()
        assertEquals(0L, t.advanceTo(500 * ms))
    }

    @Test
    fun resetClearsAPauseSoTheNextExecutionCanRun() {
        // restart() while paused must not leave the new execution unable to advance.
        val t = ExecutionTimeline()
        t.advanceTo(0L)
        t.pause()
        t.reset()
        assertEquals(0L, t.advanceTo(9_000 * ms))
    }

    @Test
    fun resetReturnsToAFreshExecution() {
        // restart() reuses the handle, so this has to leave nothing behind. A leftover origin
        // would make execution 2 start part-way through.
        val t = ExecutionTimeline()
        t.advanceTo(0L)
        t.advanceTo(200 * ms)
        t.pause()
        t.seekTo(90 * ms)

        t.reset()

        assertFalse(t.hasStarted)
        assertEquals(0L, t.elapsedNanos)
        assertEquals(0L, t.advanceTo(7_000 * ms))
        assertEquals(16 * ms, t.advanceTo(7_016 * ms))
    }

    @Test
    fun hasStartedIsFalseUntilTheFirstFrame() {
        val t = ExecutionTimeline()
        assertFalse(t.hasStarted)
        t.advanceTo(0L)
        assertTrue(t.hasStarted)
    }
}
```

- [ ] **Step 2: Verify the type does not exist yet**

Run: `ls frameworks/base/aurora/runtime/java/aurora/runtime/animation/ExecutionTimeline.kt`
Expected: `No such file or directory`.

- [ ] **Step 3: Write the implementation**

Create `frameworks/base/aurora/runtime/java/aurora/runtime/animation/ExecutionTimeline.kt`:

```kotlin
/*
 * Copyright (C) 2026 The AuroraOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package aurora.runtime.animation

/**
 * The time base of one execution.
 *
 * Answers exactly one question -- *how long has this execution been running?* -- and leaves
 * *how far through is it* to an `AnimationStrategy`. That split is ADR-006, and it is what
 * lets Sprint 06B add spring and decay solvers without opening this file.
 *
 * ## No clock, anywhere
 *
 * Elapsed time is `frameTimeNanos - origin`, where the origin comes from the first frame this
 * execution was given. Nothing here reads `AuroraClock`, and nothing can: there is no clock
 * to read (RULE-008). A pause is measured in frame timestamps too, so a test that hands out
 * frames a simulated hour apart gets exactly the behaviour the device would.
 *
 * ## Two things it refuses
 *
 * It will not advance while paused, and it will not accept a frame time earlier than the last
 * one it saw. Neither should ever happen: the state machine makes `TICK` illegal from `PAUSED`,
 * and `FrameTime` is built from a monotonic source. Both are checked anyway, because the symptom
 * of either would otherwise be a silently wrong elapsed time -- a number that looks plausible and
 * is not -- rather than a failure anyone would notice (RULE-003).
 *
 * ## Why seeking while paused needs no special handling
 *
 * A paused execution is not ticked, so [lastFrameNanos] stays frozen at the frame the pause
 * happened on, which means `pausedAtFrameNanos == lastFrameNanos` for as long as a pause is in
 * play. Seeking then moves the origin relative to that frozen frame, and the resume shift
 * cancels it out exactly.
 *
 * Not thread safe, and does not need to be: one execution is only ever advanced from the
 * frame that is currently being processed.
 */
class ExecutionTimeline {

    private var originNanos: Long = 0L
    private var lastFrameNanos: Long = UNSET
    private var pausedAtFrameNanos: Long = UNSET
    private var pendingResume: Boolean = false
    private var seekTargetNanos: Long = 0L
    private var started: Boolean = false
    private var paused: Boolean = false

    /** Elapsed time as of the last [advanceTo] or [seekTo]. */
    var elapsedNanos: Long = 0L
        private set

    /** Whether this execution has been given a frame yet. */
    val hasStarted: Boolean
        get() = started

    /**
     * Advances to a frame and returns the elapsed time for it.
     *
     * @param frameTimeNanos the instant the frame is being composed for, from [FrameTime].
     *     Never a clock reading.
     */
    fun advanceTo(frameTimeNanos: Long): Long {
        check(!paused) {
            "a paused execution must not be ticked. The state machine makes TICK illegal from " +
                "PAUSED, so reaching here means the caller advanced a handle it should have " +
                "skipped - and the symptom would otherwise be a silently wrong elapsed time " +
                "rather than a failure (RULE-003)."
        }
        require(!started || frameTimeNanos >= lastFrameNanos) {
            "frame time must not go backwards: $frameTimeNanos after $lastFrameNanos (RULE-006). " +
                "FrameTime guards this at construction; this class takes a raw Long and so has " +
                "to guard it itself."
        }
        if (!started) {
            started = true
            // A seek before the first frame chooses where the execution begins.
            originNanos = frameTimeNanos - seekTargetNanos
        } else if (pendingResume) {
            // Shift the origin by exactly the time that passed while paused, so the animation
            // continues instead of jumping forward by the length of the pause.
            originNanos += frameTimeNanos - pausedAtFrameNanos
            pendingResume = false
            pausedAtFrameNanos = UNSET
        }
        lastFrameNanos = frameTimeNanos
        elapsedNanos = frameTimeNanos - originNanos
        return elapsedNanos
    }

    /** Holds the execution. Records where to resume from. */
    fun pause() {
        if (started) pausedAtFrameNanos = lastFrameNanos
        pendingResume = false
        paused = true
    }

    /**
     * Continues the execution.
     *
     * The shift is applied on the next frame, because that is the first moment the new frame
     * time is known. Pausing before the first frame leaves nothing to shift.
     */
    fun resume() {
        pendingResume = started && pausedAtFrameNanos != UNSET
        paused = false
    }

    /**
     * Moves the execution to [elapsedTargetNanos].
     *
     * Moving backwards is allowed. RULE-006 forbids a *clock* going backwards; this is a
     * position, and moving a position back is what scrubbing means.
     */
    fun seekTo(elapsedTargetNanos: Long) {
        require(elapsedTargetNanos >= 0) {
            "cannot position an execution before it began: $elapsedTargetNanos"
        }
        if (started) {
            originNanos = lastFrameNanos - elapsedTargetNanos
        } else {
            seekTargetNanos = elapsedTargetNanos
        }
        elapsedNanos = elapsedTargetNanos
    }

    /** Returns to the state of a fresh execution. Called by restart. */
    fun reset() {
        originNanos = 0L
        lastFrameNanos = UNSET
        pausedAtFrameNanos = UNSET
        pendingResume = false
        seekTargetNanos = 0L
        started = false
        elapsedNanos = 0L
        paused = false
    }

    private companion object {
        /** No frame seen. Not zero, which is a legitimate frame timestamp. */
        const val UNSET = Long.MIN_VALUE
    }
}
```

- [ ] **Step 4: Run the architecture gate**

Run: `bash frameworks/base/aurora/tools/arch-test.sh`
Expected: `ARCH TEST PASS`, and `runtime: no call to System.nanoTime` still listed as `ok`.

- [ ] **Step 5: Commit**

```bash
git add frameworks/base/aurora/runtime/java/aurora/runtime/animation/ExecutionTimeline.kt \
        frameworks/base/aurora/tests/java/aurora/runtime/animation/AnimationLifecycleTest.kt
git commit -m "Sprint 06A: ExecutionTimeline

The time base of one execution: how long has this been running, and nothing
about how far through it therefore is. That is ADR-006, and it means 06B
adds spring and decay solvers without opening this file.

No clock anywhere. Elapsed is frameTimeNanos minus an origin taken from the
execution first frame, and a pause is measured in frame timestamps too - so
a test handing out frames a simulated hour apart gets exactly the device
behaviour.

Seeking backwards is allowed. RULE-006 forbids a clock going backwards; this
is a position, and moving a position back is what scrubbing means."
```

---

## Task 10: `TimedStrategy`

**Files:**
- Create: `frameworks/base/aurora/runtime/java/aurora/runtime/animation/TimedStrategy.kt`
- Modify: `frameworks/base/aurora/tests/java/aurora/runtime/animation/AnimationLifecycleTest.kt`

- [ ] **Step 1: Write the failing test**

Add these imports to `AnimationLifecycleTest.kt`:

```kotlin
import aurora.sdk.animation.Interpolator
import aurora.sdk.animation.TimedSpec
import aurora.sdk.time.Timeline
```

Append to `AnimationLifecycleTest`:

```kotlin
    // --- TimedStrategy -------------------------------------------------------

    @Test
    fun timedProgressIsLinearInElapsedTime() {
        val s = TimedStrategy(TimedSpec(Timeline.ofMillis(200)))
        s.advance(0L, 0L)
        assertEquals(0f, s.progress, 1e-6f)
        s.advance(100 * ms, 100 * ms)
        assertEquals(0.5f, s.progress, 1e-6f)
        s.advance(200 * ms, 100 * ms)
        assertEquals(1f, s.progress, 1e-6f)
    }

    @Test
    fun easedProgressAppliesTheInterpolatorAndProgressDoesNot() {
        // The two must not be conflated. progress is what the timeline says; easedProgress is
        // what the curve makes of it, and the value a caller sees comes from the latter.
        val square = Interpolator { it * it }
        val s = TimedStrategy(TimedSpec(Timeline.ofMillis(200), square))
        s.advance(100 * ms, 16 * ms)
        assertEquals(0.5f, s.progress, 1e-6f)
        assertEquals(0.25f, s.easedProgress, 1e-6f)
    }

    @Test
    fun aTimedStrategyIgnoresTheDelta() {
        // Progress comes from elapsed time, never from accumulating deltas. Two runs reaching
        // the same elapsed time by different routes must agree, or a dropped frame drifts.
        val spec = TimedSpec(Timeline.ofMillis(200))
        val smooth = TimedStrategy(spec)
        val hitched = TimedStrategy(spec)

        smooth.advance(50 * ms, 50 * ms)
        smooth.advance(100 * ms, 50 * ms)

        hitched.advance(100 * ms, 100 * ms)   // one long frame instead of two short ones

        assertEquals(smooth.progress, hitched.progress, 0f)
    }

    @Test
    fun isFinishedFollowsTheTimeline() {
        val s = TimedStrategy(TimedSpec(Timeline.ofMillis(200)))
        s.advance(199 * ms, 16 * ms)
        assertFalse(s.isFinished)
        s.advance(200 * ms, 1 * ms)
        assertTrue(s.isFinished)
    }

    @Test
    fun aDelayHoldsProgressAtZero() {
        val s = TimedStrategy(
            TimedSpec(Timeline(durationNanos = 200 * ms, delayNanos = 100 * ms))
        )
        s.advance(50 * ms, 16 * ms)
        assertEquals(0f, s.progress, 1e-6f)
        assertFalse(s.isFinished)
        s.advance(200 * ms, 16 * ms)
        assertEquals(0.5f, s.progress, 1e-6f)
    }

    @Test
    fun anInfiniteTimelineNeverFinishes() {
        val s = TimedStrategy(
            TimedSpec(Timeline(durationNanos = 100 * ms, repeatCount = Timeline.REPEAT_INFINITE))
        )
        s.advance(10_000 * ms, 16 * ms)
        assertFalse(s.isFinished)
    }

    @Test
    fun resetClearsProgressAndTheFinishedFlag() {
        val s = TimedStrategy(TimedSpec(Timeline.ofMillis(200)))
        s.advance(300 * ms, 16 * ms)
        assertTrue(s.isFinished)
        s.reset()
        assertEquals(0f, s.progress, 1e-6f)
        assertFalse(s.isFinished)
    }

    @Test
    fun seekToOnATimedStrategyDoesNotThrow() {
        // The elapsed move is ExecutionTimeline job; this hook exists for strategies that
        // carry integrator state, and a timed one has none to clear.
        TimedStrategy(TimedSpec(Timeline.ofMillis(200))).seekTo(0.5f)
    }

    @Test
    fun aFreshStrategyReportsTheCurveAtZeroRatherThanZero() {
        // Pins the initialisation contract. easedProgress is computed from the interpolator in a
        // property initialiser that reads the constructor parameter, and a strategy handed out
        // before its first advance() must already report the shaped value, not a bare 0f.
        val offset = Interpolator { p -> 0.25f + 0.5f * p }
        val s = TimedStrategy(TimedSpec(Timeline.ofMillis(200), offset))
        assertEquals(0f, s.progress, 0f)
        assertEquals(0.25f, s.easedProgress, 1e-6f)
        assertFalse(s.isFinished)
    }

    @Test
    fun anOvershootingCurveSurvivesUnclamped() {
        // AnimationStrategy documents that easedProgress may legitimately leave 0..1, and
        // advance() applies no clamp. Nothing else enforces that, so a clamp added later would
        // flatten every bouncy spring in Sprint 06B while still looking correct.
        val overshoot = Interpolator { p -> p * 1.4f }
        val s = TimedStrategy(TimedSpec(Timeline.ofMillis(200), overshoot))
        s.advance(200 * ms, 16 * ms)
        assertEquals(1f, s.progress, 1e-6f)
        assertEquals(1.4f, s.easedProgress, 1e-6f)
    }

    @Test
    fun aPingPongEndingOnAReverseIterationFinishesAtZero() {
        // Surprising and correct. A timeline that reverses on repeat with an odd repeatCount runs
        // its last iteration backwards, so it ends where it started. Written down because it
        // reads like a bug the first time someone sees a finished animation reporting 0.
        val s = TimedStrategy(
            TimedSpec(Timeline(durationNanos = 100 * ms, repeatCount = 1, reverseOnRepeat = true))
        )
        s.advance(200 * ms, 16 * ms)
        assertTrue(s.isFinished)
        assertEquals(0f, s.progress, 1e-6f)
    }
```

- [ ] **Step 2: Verify the type does not exist yet**

Run: `ls frameworks/base/aurora/runtime/java/aurora/runtime/animation/TimedStrategy.kt`
Expected: `No such file or directory`.

- [ ] **Step 3: Write the implementation**

Create `frameworks/base/aurora/runtime/java/aurora/runtime/animation/TimedStrategy.kt`:

```kotlin
/*
 * Copyright (C) 2026 The AuroraOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package aurora.runtime.animation

import aurora.sdk.animation.AnimationStrategy
import aurora.sdk.animation.TimedSpec

/**
 * The only strategy Sprint 06A ships: time decides progress.
 *
 * Almost all of the work is already done by [aurora.sdk.time.Timeline], which is stateless,
 * so this holds nothing but the last values it computed. That is deliberate: progress is
 * derived from elapsed time on every frame rather than accumulated from deltas, so a dropped
 * frame cannot make it drift, and two runs that reach the same elapsed time by different
 * routes agree exactly.
 *
 * Sprint 06B adds `SpringStrategy`, `DecayStrategy`, `SnapStrategy` and `FlingStrategy`
 * beside this file. None of them will need to change it.
 */
class TimedStrategy(private val spec: TimedSpec) : AnimationStrategy {

    override var progress: Float = 0f
        private set

    override var easedProgress: Float = spec.interpolator.transform(0f)
        private set

    override var isFinished: Boolean = false
        private set

    override fun advance(elapsedNanos: Long, deltaNanos: Long) {
        // deltaNanos is unused, and that is the point. See the class documentation.
        progress = spec.timeline.progressAt(elapsedNanos)
        easedProgress = spec.interpolator.transform(progress)
        isFinished = spec.timeline.isFinishedAt(elapsedNanos)
    }

    override fun reset() {
        progress = 0f
        easedProgress = spec.interpolator.transform(0f)
        isFinished = false
    }

    /**
     * Nothing to do.
     *
     * Moving the execution is `ExecutionTimeline.seekTo`; this hook exists for strategies
     * that carry integrator state across frames, and a timed one carries none. It is not
     * empty by oversight.
     */
    override fun seekTo(progress: Float) = Unit
}
```

- [ ] **Step 4: Run the architecture gate**

Run: `bash frameworks/base/aurora/tools/arch-test.sh`
Expected: `ARCH TEST PASS`.

- [ ] **Step 5: Commit**

```bash
git add frameworks/base/aurora/runtime/java/aurora/runtime/animation/TimedStrategy.kt \
        frameworks/base/aurora/tests/java/aurora/runtime/animation/AnimationLifecycleTest.kt
git commit -m "Sprint 06A: TimedStrategy

The only strategy 06A ships. Timeline is already stateless, so this holds
nothing but the values it last computed.

deltaNanos is deliberately unused: progress is derived from elapsed time
every frame rather than accumulated, so a dropped frame cannot drift and two
runs reaching the same elapsed time by different routes agree exactly. The
test asserts that directly - one long frame and two short ones must land on
the same progress."
```

---

## Task 11: `AnimationRegistry`

**Files:**
- Create: `frameworks/base/aurora/runtime/java/aurora/runtime/animation/AnimationRegistry.kt`
- Test: `frameworks/base/aurora/tests/java/aurora/runtime/animation/AnimationRegistryTest.kt`

Where RULE-013 lives. Tested here against a fake `Tickable` so the rules can be proven without
a handle, a strategy or a driver in the way.

- [ ] **Step 1: Write the failing test**

Create `frameworks/base/aurora/tests/java/aurora/runtime/animation/AnimationRegistryTest.kt`:

```kotlin
/*
 * Copyright (C) 2026 The AuroraOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package aurora.runtime.animation

import aurora.sdk.time.FrameTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * RULE-013, proven against a fake tickable.
 *
 * No handle, no strategy, no driver: the rules about *when* something advances are worth
 * being able to check without anything that could also be wrong.
 */
class AnimationRegistryTest {

    private lateinit var registry: AnimationRegistry
    private val log = mutableListOf<String>()

    /** Records every tick it receives, and can act on the registry while being ticked. */
    private inner class Fake(
        val name: String,
        var tickable: Boolean = true,
        val onTick: (Fake) -> Unit = {},
    ) : AnimationRegistry.Tickable {

        var ticks = 0
            private set

        override val isTickable: Boolean get() = tickable

        override fun tick(frameTime: FrameTime) {
            ticks++
            log += "$name@${frameTime.frameIndex}"
            onTick(this)
        }
    }

    private fun frame(index: Long) =
        FrameTime(frameTimeNanos = index * 16_000_000L, deltaNanos = 16_000_000L, frameIndex = index)

    @org.junit.Before
    fun setUp() {
        registry = AnimationRegistry()
        log.clear()
    }

    // --- ordering ------------------------------------------------------------

    @Test
    fun tickOrderIsInsertionOrder() {
        // Never a hash container: iteration order is observable behaviour here, so a hash
        // would make two runs of the same program tick in different orders.
        listOf("a", "b", "c", "d").forEach { registry.add(Fake(it)) }
        registry.tick(frame(0))
        assertEquals(listOf("a@0", "b@0", "c@0", "d@0"), log)
    }

    @Test
    fun insertionOrderSurvivesRemovalOfAMiddleEntry() {
        val a = Fake("a"); val b = Fake("b"); val c = Fake("c")
        registry.add(a); registry.add(b); registry.add(c)
        registry.remove(b)
        registry.tick(frame(0))
        assertEquals(listOf("a@0", "c@0"), log)
    }

    @Test
    fun addingTheSameTickableTwiceDoesNotTickItTwice() {
        val a = Fake("a")
        registry.add(a)
        registry.add(a)
        registry.tick(frame(0))
        assertEquals(1, a.ticks)
        assertEquals(1, registry.size)
    }

    // --- RULE-013: deferred mutation ----------------------------------------

    @Test
    fun somethingAddedDuringAFrameFirstTicksOnTheNextOne() {
        // If B could join the frame already in progress, whether it advanced on frame N or
        // N+1 would depend on where in the listener order it was added - and listener order
        // is not something a caller controls.
        val b = Fake("b")
        registry.add(Fake("a", onTick = { registry.add(b) }))

        registry.tick(frame(0))
        assertEquals(listOf("a@0"), log)
        assertEquals(0, b.ticks)

        registry.tick(frame(1))
        assertEquals(listOf("a@0", "a@1", "b@1"), log)
    }

    @Test
    fun somethingRemovedDuringAFrameIsNotTickedLaterInThatFrame() {
        // The dispose contract: after removal, never ticked again - including in the frame
        // the removal happened in, where a naive snapshot would still hold a reference.
        val c = Fake("c")
        registry.add(Fake("a", onTick = { registry.remove(c) }))
        registry.add(Fake("b"))
        registry.add(c)

        registry.tick(frame(0))
        assertEquals(listOf("a@0", "b@0"), log)
        assertEquals(0, c.ticks)
    }

    @Test
    fun aTickableThatStopsBeingTickableMidFrameIsSkipped() {
        // Cancelling animation C from animation A callback. C is still in the collection this
        // frame; it must not advance.
        val c = Fake("c")
        registry.add(Fake("a", onTick = { c.tickable = false }))
        registry.add(c)

        registry.tick(frame(0))
        assertEquals(listOf("a@0"), log)
    }

    @Test
    fun removingSomethingAlreadyTickedThisFrameStillTakesEffectNextFrame() {
        val a = Fake("a")
        val b = Fake("b", onTick = { registry.remove(a) })
        registry.add(a); registry.add(b)

        registry.tick(frame(0))
        assertEquals(listOf("a@0", "b@0"), log)   // a had already run when b removed it

        registry.tick(frame(1))
        assertEquals(listOf("a@0", "b@0", "b@1"), log)
    }

    @Test
    fun addsAndRemovesDuringAFrameCommitInAStableOrder() {
        // Two animations each starting one from their callback. B before C, on replay too.
        val b = Fake("b"); val c = Fake("c")
        registry.add(Fake("a1", onTick = { registry.add(b) }))
        registry.add(Fake("a2", onTick = { registry.add(c) }))

        registry.tick(frame(0))
        registry.tick(frame(1))

        assertEquals(listOf("a1@0", "a2@0", "a1@1", "a2@1", "b@1", "c@1"), log)
    }

    @Test
    fun anAddCancelledLaterInTheSameFrameNeverTicks() {
        val b = Fake("b")
        registry.add(Fake("a1", onTick = { registry.add(b) }))
        registry.add(Fake("a2", onTick = { registry.remove(b) }))

        registry.tick(frame(0))
        registry.tick(frame(1))

        assertEquals(0, b.ticks)
    }

    // --- frame position ------------------------------------------------------

    @Test
    fun theRegistryReportsWhichFrameItIsProcessing() {
        // A handle uses this to record which frame its execution was scheduled in, so it can
        // refuse to advance in that same frame.
        var seen = -99L
        registry.add(Fake("a", onTick = { seen = registry.tickingFrameIndex }))
        assertEquals(AnimationRegistry.NOT_TICKING, registry.tickingFrameIndex)
        registry.tick(frame(7))
        assertEquals(7L, seen)
        assertEquals(AnimationRegistry.NOT_TICKING, registry.tickingFrameIndex)
    }

    @Test
    fun theFrameIndexIsResetEvenIfATickableThrows() {
        registry.add(Fake("boom", onTick = { throw RuntimeException("from a listener") }))
        try {
            registry.tick(frame(3))
        } catch (expected: RuntimeException) {
            // expected
        }
        assertEquals(AnimationRegistry.NOT_TICKING, registry.tickingFrameIndex)
        assertFalse(registry.isTicking)
    }

    // --- wake ----------------------------------------------------------------

    @Test
    fun theRegistryAnnouncesWhenItStopsBeingEmpty() {
        // The driver stops posting frames when nothing is running, so it has to be told when
        // something starts. Otherwise an idle engine never wakes up again.
        var wakes = 0
        registry.onWake = { wakes++ }

        registry.add(Fake("a"))
        assertEquals(1, wakes)
        registry.add(Fake("b"))
        assertEquals("only the transition out of empty wakes the driver", 1, wakes)

        registry.clear()
        registry.add(Fake("c"))
        assertEquals(2, wakes)
    }

    // --- snapshot ------------------------------------------------------------

    @Test
    fun aSnapshotIsSafeToMutateTheRegistryFrom() {
        // cancelAll() walks this and cancels each entry, which removes it.
        registry.add(Fake("a")); registry.add(Fake("b"))
        val seen = mutableListOf<String>()
        registry.snapshot().forEach {
            seen += (it as Fake).name
            registry.remove(it)
        }
        assertEquals(listOf("a", "b"), seen)
        assertEquals(0, registry.size)
    }

    @Test
    fun clearEmptiesEverythingIncludingPendingWork() {
        registry.add(Fake("a", onTick = { registry.add(Fake("late")) }))
        registry.tick(frame(0))
        registry.clear()
        registry.tick(frame(1))
        assertEquals(listOf("a@0"), log)
        assertTrue(registry.size == 0)
    }

    @Test
    fun clearingDuringAFrameIsRejected() {
        // Without the guard this is an IndexOutOfBoundsException: tick() captures the list size
        // once, and clear() empties the list underneath it. Loud beats crashing, and both beat
        // emptying the registry behind the handles' backs.
        registry.add(Fake("a", onTick = { registry.clear() }))
        registry.add(Fake("b"))
        try {
            registry.tick(frame(0))
            fail("clear() during a frame must be rejected")
        } catch (expected: IllegalStateException) {
            // expected
        }
        // The guard must not leave the registry stuck mid-frame.
        assertEquals(AnimationRegistry.NOT_TICKING, registry.tickingFrameIndex)
        assertFalse(registry.isTicking)
    }

    @Test
    fun reEnteringTickIsRejected() {
        registry.add(Fake("a", onTick = { registry.tick(frame(1)) }))
        try {
            registry.tick(frame(0))
            fail("tick() must not be re-entrant")
        } catch (expected: IllegalStateException) {
            // expected
        }
    }
}
```

- [ ] **Step 2: Verify the type does not exist yet**

Run: `ls frameworks/base/aurora/runtime/java/aurora/runtime/animation/AnimationRegistry.kt`
Expected: `No such file or directory`.

- [ ] **Step 3: Write the implementation**

Create `frameworks/base/aurora/runtime/java/aurora/runtime/animation/AnimationRegistry.kt`:

```kotlin
/*
 * Copyright (C) 2026 The AuroraOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package aurora.runtime.animation

import aurora.sdk.time.FrameTime

/**
 * Everything the engine is currently advancing.
 *
 * ## Insertion-ordered, never hashed
 *
 * Tick order is observable behaviour: listeners fire in it, and animations started from those
 * listeners are queued in it. A hash container would let two runs of the same program tick in
 * different orders, which is enough on its own to break RULE-009. `contracts/runtime.contract`
 * forbids `HashMap` and `HashSet` in this file specifically -- the hazard is iteration order,
 * not the container, so the ban is scoped to the one file where order becomes behaviour.
 *
 * ## RULE-013: deferred mutation
 *
 * Structural changes made *during* a tick are queued and applied when the frame ends. An
 * animation started from a listener therefore first advances on the following frame.
 *
 * The reason is determinism, not thread safety. If a listener could inject an animation into
 * the frame already in progress, whether it advanced on frame N or N+1 would depend on where
 * in the listener order the call happened -- and listener order is not something a caller
 * controls. See ADR-005.
 *
 * Removal is guarded twice: a queued removal is checked before each entry is ticked, and
 * [Tickable.isTickable] is re-read as well. So a handle cancelled or disposed by an earlier
 * listener in the same frame is skipped rather than advanced and then discarded. That is the
 * mechanism behind the frozen contract *after dispose returns, the handle never receives
 * another tick*.
 *
 * Not thread safe. The engine is driven from one frame source.
 */
class AnimationRegistry {

    /** Something the engine advances once per frame. */
    interface Tickable {

        /** Whether this should advance right now. Re-read immediately before every tick. */
        val isTickable: Boolean

        /** Advance by one frame. */
        fun tick(frameTime: FrameTime)
    }

    private val active = ArrayList<Tickable>()
    private val pendingAdd = ArrayList<Tickable>()
    private val pendingRemove = ArrayList<Tickable>()

    /** Whether a frame is being processed right now. */
    var isTicking: Boolean = false
        private set

    /**
     * The frame index being processed, or [NOT_TICKING] outside a frame.
     *
     * A handle records this when its execution is scheduled, so it can refuse to advance in
     * the same frame it was scheduled in. That is RULE-013 seen from the other side.
     */
    var tickingFrameIndex: Long = NOT_TICKING
        private set

    /**
     * Called when the registry has something to advance and may not have had before.
     *
     * The driver stops posting frames when nothing is running, so that an idle engine wakes no
     * core between refreshes. This is how it is told to start again.
     *
     * Two things a consumer has to know. It can fire **re-entrantly**, from inside the `tick()`
     * call the driver itself is on the stack for, because [commit] runs at the end of a frame.
     * And it can fire when the registry was never actually idle: a frame that removes its last
     * entry and adds another sees `active` empty in between. So it means *there is work now*,
     * not *there was none a moment ago*, and a consumer must be idempotent.
     */
    var onWake: (() -> Unit)? = null

    /** How many animations are being advanced. */
    val size: Int
        get() = active.size

    /** Registers [tickable], or queues the registration when called during a frame. */
    fun add(tickable: Tickable) {
        if (isTicking) {
            pendingRemove.remove(tickable)
            if (!pendingAdd.contains(tickable) && !active.contains(tickable)) {
                pendingAdd.add(tickable)
            }
            return
        }
        if (active.contains(tickable)) return
        val wasEmpty = active.isEmpty()
        active.add(tickable)
        if (wasEmpty) onWake?.invoke()
    }

    /** Unregisters [tickable], or queues the removal when called during a frame. */
    fun remove(tickable: Tickable) {
        if (isTicking) {
            pendingAdd.remove(tickable)
            if (!pendingRemove.contains(tickable)) pendingRemove.add(tickable)
            return
        }
        active.remove(tickable)
    }

    /**
     * Advances everything, in insertion order, with one shared [frameTime].
     *
     * The loop bound is read once, so entries queued during the frame cannot extend it, and no
     * copy of the list is made -- this runs on every frame of every gesture.
     */
    fun tick(frameTime: FrameTime) {
        check(!isTicking) {
            "tick() re-entered from inside a frame. The inner call would commit the outer " +
                "frame's queued work early and break RULE-013 silently."
        }
        isTicking = true
        tickingFrameIndex = frameTime.frameIndex
        try {
            var i = 0
            val bound = active.size
            while (i < bound) {
                val t = active[i]
                if (t.isTickable && !pendingRemove.contains(t)) {
                    t.tick(frameTime)
                }
                i++
            }
        } finally {
            isTicking = false
            tickingFrameIndex = NOT_TICKING
            commit()
        }
    }

    /** A copy, safe to mutate the registry while walking. Used by `cancelAll`. */
    fun snapshot(): List<Tickable> = ArrayList(active)

    /**
     * Drops everything.
     *
     * Illegal during a frame. Emptying the registry behind the handles' backs would leave every
     * one of them reporting [aurora.sdk.animation.AnimationState.RUNNING] while never being
     * ticked again — a lie the API has no way to detect. Cancelling mid-frame is
     * `Animator.cancelAll()`, which goes through the handles so their state stays true.
     *
     * @throws IllegalStateException if called while [tick] is running
     */
    fun clear() {
        check(!isTicking) {
            "clear() during a frame would empty the list tick() is iterating, and would leave " +
                "every handle reporting RUNNING with nothing advancing it. Use cancelAll()."
        }
        active.clear()
        // Empty by construction while not ticking, since commit() drains both at the end of
        // every frame. Cleared anyway so this stays correct if commit() ever stops doing that.
        pendingAdd.clear()
        pendingRemove.clear()
    }

    /**
     * Applies the frame's queued changes.
     *
     * Removals first, so that a frame which empties `active` and then refills it computes
     * `wasEmpty` correctly for [onWake]. Add-then-remove within one frame nets to nothing for a
     * different reason: [add] and [remove] each evict the tickable from the opposite queue, so
     * the two are disjoint by the time this runs.
     */
    private fun commit() {
        if (pendingRemove.isNotEmpty()) {
            var i = 0
            while (i < pendingRemove.size) {
                active.remove(pendingRemove[i])
                i++
            }
            pendingRemove.clear()
        }
        if (pendingAdd.isNotEmpty()) {
            val wasEmpty = active.isEmpty()
            active.addAll(pendingAdd)
            pendingAdd.clear()
            if (wasEmpty && active.isNotEmpty()) onWake?.invoke()
        }
    }

    companion object {
        /** [tickingFrameIndex] outside a frame. Negative, so every real frame index beats it. */
        const val NOT_TICKING: Long = -1L
    }
}
```

- [ ] **Step 4: Run the architecture gate**

Run: `bash frameworks/base/aurora/tools/arch-test.sh`
Expected: `ARCH TEST PASS`.

- [ ] **Step 5: Commit**

```bash
git add frameworks/base/aurora/runtime/java/aurora/runtime/animation/AnimationRegistry.kt \
        frameworks/base/aurora/tests/java/aurora/runtime/animation/AnimationRegistryTest.kt
git commit -m "Sprint 06A: AnimationRegistry, with deferred mutation

RULE-013 lives here. Structural changes made during a tick are queued and
applied at the end of the frame, so an animation started from a listener
first advances on the following frame.

The reason is determinism, not thread safety: if a listener could inject an
animation into the frame already in progress, whether it advanced on frame N
or N+1 would depend on where in the listener order the call happened, and
listener order is not something a caller controls (ADR-005).

Removal is guarded twice - a queued removal is checked and isTickable is
re-read before each entry - so a handle disposed by an earlier listener in
the same frame is skipped rather than advanced and then discarded. That is
the mechanism behind the frozen contract that a disposed handle never
receives another tick.

Insertion-ordered and never hashed. Iteration order is observable behaviour
here, so a hash container alone would break RULE-009.

The loop bound is read once and no copy is made: this runs on every frame of
every gesture."
```

---

## Task 12: `AnimationHandleImpl` and `DefaultAnimator`

**Files:**
- Create: `frameworks/base/aurora/runtime/java/aurora/runtime/animation/AnimationHandleImpl.kt`
- Create: `frameworks/base/aurora/runtime/java/aurora/runtime/animation/DefaultAnimator.kt`
- Modify: `frameworks/base/aurora/tests/java/aurora/runtime/animation/AnimationLifecycleTest.kt`

Both together: the animator does nothing but make handles, and a handle with no animator to
make it cannot be tested.

**Not `internal`.** Tests live in a different Soong module, and Kotlin `internal` is
module-scoped, so an internal handle would be invisible to every test in this plan.

- [ ] **Step 1: Write the failing test**

Add these imports to `AnimationLifecycleTest.kt`:

```kotlin
import aurora.sdk.animation.AnimationHandle
import aurora.sdk.animation.AnimationListener
import aurora.sdk.animation.AnimationState
import aurora.sdk.animation.Animation
import aurora.sdk.animation.SpringSpec
import aurora.sdk.time.FrameTime
```

Append to `AnimationLifecycleTest`:

```kotlin
    // --- handle lifecycle ----------------------------------------------------

    private fun registry() = AnimationRegistry()

    private fun handle(
        registry: AnimationRegistry,
        durationMs: Long = 100,
    ): AnimationHandle = DefaultAnimator(registry)
        .create(Animation("test", TimedSpec(Timeline.ofMillis(durationMs))))

    private fun frame(index: Long) = FrameTime(
        frameTimeNanos = index * 16 * ms,
        deltaNanos = 16 * ms,
        frameIndex = index,
    )

    /** Records every callback, so ordering and execution ids can be asserted. */
    private class Recorder : AnimationListener {
        val events = mutableListOf<String>()
        override fun onStateChanged(
            handle: AnimationHandle,
            executionId: Long,
            from: AnimationState,
            to: AnimationState,
        ) {
            events += "state#$executionId:$from->$to"
        }

        override fun onUpdate(
            handle: AnimationHandle,
            executionId: Long,
            progress: Float,
            value: Float,
        ) {
            events += "update#$executionId:$progress"
        }
    }

    @Test
    fun aFreshHandleIsIdle() {
        assertEquals(AnimationState.IDLE, handle(registry()).state)
    }

    @Test
    fun playSchedulesAndTheFirstFrameStartsIt() {
        val r = registry()
        val h = handle(r)
        h.play()
        assertEquals(AnimationState.SCHEDULED, h.state)
        r.tick(frame(0))
        assertEquals(AnimationState.RUNNING, h.state)
    }

    @Test
    fun theWholeContractSequenceWorks() {
        // play -> pause -> resume -> cancel -> restart -> dispose, exactly as the sprint
        // contract names it.
        val r = registry()
        val h = handle(r, durationMs = 1000)

        h.play();                assertEquals(AnimationState.SCHEDULED, h.state)
        r.tick(frame(0));        assertEquals(AnimationState.RUNNING, h.state)
        h.pause();               assertEquals(AnimationState.PAUSED, h.state)
        h.resume();              assertEquals(AnimationState.RUNNING, h.state)
        h.cancel();              assertEquals(AnimationState.CANCELLED, h.state)
        h.restart();             assertEquals(AnimationState.SCHEDULED, h.state)
        h.dispose();             assertEquals(AnimationState.DISPOSED, h.state)
        assertTrue(h.isDisposed)
    }

    @Test
    fun cancelThenResumeThrows() {
        // The case the sprint contract calls out. Named here as well as in the state machine
        // test, because a handle could still get it wrong on its own.
        val r = registry()
        val h = handle(r)
        h.play()
        r.tick(frame(0))
        h.cancel()
        try {
            h.resume()
            fail("resuming a cancelled animation must fail")
        } catch (expected: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun aCancelledAnimationStaysWhereItWasRatherThanJumpingToTheEnd() {
        // Cancelling mid-flight must not teleport the interface to the end value.
        val r = registry()
        val h = handle(r, durationMs = 100)
        h.play()
        r.tick(frame(0))
        r.tick(frame(3))       // 48ms of a 100ms animation
        val whereItWas = h.value
        h.cancel()
        assertEquals(whereItWas, h.value, 0f)
        assertTrue("expected mid-flight, got $whereItWas", whereItWas > 0f && whereItWas < 1f)
    }

    @Test
    fun aPausedAnimationDoesNotAdvanceWhileTheEngineKeepsTicking() {
        val r = registry()
        val h = handle(r, durationMs = 1000)
        h.play()
        r.tick(frame(0))
        r.tick(frame(1))
        val held = h.progress
        h.pause()

        repeat(30) { r.tick(frame(2L + it)) }
        assertEquals(held, h.progress, 0f)

        h.resume()
        r.tick(frame(40))
        assertEquals("resuming loses no elapsed time", held, h.progress, 1e-6f)
        r.tick(frame(41))
        assertTrue(h.progress > held)
    }

    @Test
    fun aCompletedAnimationLeavesTheRegistry() {
        val r = registry()
        val h = handle(r, durationMs = 32)
        h.play()
        r.tick(frame(0))
        r.tick(frame(1))
        r.tick(frame(2))
        assertEquals(AnimationState.COMPLETED, h.state)
        assertEquals(0, r.size)
    }

    // --- RULE-012: execution identity -----------------------------------------

    @Test
    fun restartIncrementsTheExecutionIdButKeepsTheHandle() {
        val r = registry()
        val h = handle(r)
        h.play()
        assertEquals(0L, h.executionId)
        h.restart()
        assertEquals(1L, h.executionId)
        h.restart()
        assertEquals(2L, h.executionId)
    }

    @Test
    fun aListenerCanTellWhichExecutionAnEventBelongsTo() {
        // The bug this prevents: a component holding state across runs acts on a callback
        // from a run it already abandoned. Without the id there is nothing in the callback
        // to say which run it is.
        val r = registry()
        val h = handle(r, durationMs = 1000)
        val rec = Recorder()
        h.addListener(rec)

        h.play()
        r.tick(frame(0))
        h.restart()
        r.tick(frame(1))
        r.tick(frame(2))

        assertTrue(rec.events.any { it.startsWith("update#0") })
        assertTrue(rec.events.any { it.startsWith("update#1") })
        val firstOfExecutionOne = rec.events.indexOfFirst { it.startsWith("update#1") }
        val lastOfExecutionZero = rec.events.indexOfLast { it.startsWith("update#0") }
        assertTrue(
            "execution 0 events must all precede execution 1 events",
            lastOfExecutionZero < firstOfExecutionOne
        )
    }

    @Test
    fun restartStartsTheNewExecutionFromTheBeginning() {
        val r = registry()
        val h = handle(r, durationMs = 100)
        h.play()
        r.tick(frame(0))
        r.tick(frame(4))
        assertTrue(h.progress > 0.5f)

        h.restart()
        r.tick(frame(5))
        assertEquals("a new execution starts at zero", 0f, h.progress, 1e-6f)
    }

    @Test
    fun aCompletedHandleIsRestartable() {
        // RULE-012: the volume slider re-runs one handle instead of allocating per key press.
        val r = registry()
        val h = handle(r, durationMs = 16)
        h.play()
        r.tick(frame(0))
        r.tick(frame(1))
        r.tick(frame(2))
        assertEquals(AnimationState.COMPLETED, h.state)

        h.restart()
        assertEquals(AnimationState.SCHEDULED, h.state)
        r.tick(frame(3))
        assertEquals(AnimationState.RUNNING, h.state)
    }

    // --- dispose --------------------------------------------------------------

    @Test
    fun everyMutatingCallAfterDisposeThrows() {
        val r = registry()
        val h = handle(r)
        h.dispose()

        listOf<Pair<String, () -> Unit>>(
            "play" to { h.play() },
            "pause" to { h.pause() },
            "resume" to { h.resume() },
            "cancel" to { h.cancel() },
            "restart" to { h.restart() },
            "seek" to { h.seek(0.5f) },
        ).forEach { (name, call) ->
            try {
                call()
                fail("$name after dispose must throw")
            } catch (expected: IllegalStateException) {
                // expected
            }
        }
    }

    @Test
    fun everyQueryAfterDisposeStillAnswers() {
        // Queries never throw, in any state. A teardown path reading progress to log it must
        // not become the thing that crashes teardown.
        val r = registry()
        val h = handle(r)
        h.play()
        r.tick(frame(0))
        h.dispose()

        assertEquals(AnimationState.DISPOSED, h.state)
        assertTrue(h.isDisposed)
        assertFalse(h.isRunning)
        assertEquals(0L, h.executionId)
        h.progress
        h.value
        h.animation
    }

    @Test
    fun disposeIsIdempotent() {
        val h = handle(registry())
        h.dispose()
        h.dispose()
        assertEquals(AnimationState.DISPOSED, h.state)
    }

    @Test
    fun cancelIsIdempotent() {
        val h = handle(registry())
        h.play()
        h.cancel()
        h.cancel()
        assertEquals(AnimationState.CANCELLED, h.state)
    }

    @Test
    fun aDisposedHandleIsNeverTickedAgain() {
        // The frozen contract, asserted directly: after dispose() returns, no further tick.
        //
        // Counting updates rather than all events, because disposal itself IS announced. The
        // state change is published before the listener array is cleared, deliberately, so a
        // component hears about the teardown it caused. That is a state change, not a tick, and
        // conflating the two would make this test forbid a behaviour the design chose.
        val r = registry()
        val h = handle(r, durationMs = 1000)
        val rec = Recorder()
        h.addListener(rec)
        h.play()
        r.tick(frame(0))
        val updatesBeforeDispose = rec.events.count { it.startsWith("update") }

        h.dispose()
        assertTrue(
            "a listener hears about the disposal, got ${rec.events.last()}",
            rec.events.last().endsWith("->DISPOSED")
        )

        repeat(10) { r.tick(frame(1L + it)) }

        assertEquals(
            "a disposed handle receives no further ticks",
            updatesBeforeDispose,
            rec.events.count { it.startsWith("update") }
        )
        assertEquals(0, r.size)
    }

    // --- seek -----------------------------------------------------------------

    @Test
    fun seekMovesTheExecutionAndPublishesImmediately() {
        val r = registry()
        val h = handle(r, durationMs = 100)
        h.play()
        r.tick(frame(0))
        h.seek(0.25f)
        assertEquals(0.25f, h.progress, 1e-6f)
        assertEquals(0.25f, h.value, 1e-6f)
    }

    @Test
    fun seekingTwiceToTheSameProgressGivesTheSameValue() {
        // RULE-009 at the API surface. Timeline is stateless, so this holds by construction;
        // the test is here to notice if it stops.
        val r = registry()
        val h = handle(r, durationMs = 100)
        h.play()
        r.tick(frame(0))
        h.seek(0.4f)
        val first = h.value
        h.seek(0.9f)
        h.seek(0.4f)
        assertEquals(first, h.value, 0f)
    }

    @Test
    fun seekIsLegalWhileScheduledAndWhilePaused() {
        val r = registry()
        val h = handle(r, durationMs = 100)
        h.play()
        h.seek(0.5f)                       // SCHEDULED
        assertEquals(0.5f, h.progress, 1e-6f)

        r.tick(frame(0))
        h.pause()
        h.seek(0.75f)                      // PAUSED
        assertEquals(0.75f, h.progress, 1e-6f)
    }

    @Test
    fun seekIsIllegalOnceTheExecutionHasEnded() {
        val r = registry()
        val h = handle(r, durationMs = 16)
        h.play()
        r.tick(frame(0))
        r.tick(frame(1))
        r.tick(frame(2))
        assertEquals(AnimationState.COMPLETED, h.state)
        try {
            h.seek(0.5f)
            fail("a finished execution has no position to move")
        } catch (expected: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun seekRejectsProgressOutsideTheUnitRange() {
        val r = registry()
        val h = handle(r)
        h.play()
        try {
            h.seek(1.5f)
            fail("seek takes 0..1")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    // --- physics is declared but not yet solved -------------------------------

    @Test
    fun aPhysicsAnimationIsRejectedWithAMessageNamingTheSprint() {
        val animator = DefaultAnimator(registry())
        try {
            animator.create(Animation("spring", SpringSpec()))
            fail("06A has no solver; the failure must be loud")
        } catch (expected: UnsupportedOperationException) {
            assertTrue(
                "the message must say where the solver is coming from, got: ${expected.message}",
                expected.message!!.contains("06B")
            )
        }
    }

    // --- animator -------------------------------------------------------------

    @Test
    fun playIsCreateFollowedByPlay() {
        val r = registry()
        val h = DefaultAnimator(r).play(Animation("x", TimedSpec(Timeline.ofMillis(100))))
        assertEquals(AnimationState.SCHEDULED, h.state)
    }

    @Test
    fun activeCountCountsScheduledAndRunningOnly() {
        val r = registry()
        val animator = DefaultAnimator(r)
        val a = animator.play(Animation("a", TimedSpec(Timeline.ofMillis(1000))))
        animator.play(Animation("b", TimedSpec(Timeline.ofMillis(1000))))
        assertEquals(2, animator.activeCount)

        a.pause()
        assertEquals("a paused animation is not being advanced", 1, animator.activeCount)

        a.resume()
        assertEquals(2, animator.activeCount)
    }

    @Test
    fun cancelAllCancelsButLeavesTheHandlesUsable() {
        val r = registry()
        val animator = DefaultAnimator(r)
        val a = animator.play(Animation("a", TimedSpec(Timeline.ofMillis(1000))))
        val b = animator.play(Animation("b", TimedSpec(Timeline.ofMillis(1000))))

        animator.cancelAll()

        assertEquals(AnimationState.CANCELLED, a.state)
        assertEquals(AnimationState.CANCELLED, b.state)
        assertEquals(0, animator.activeCount)

        a.restart()
        assertEquals(AnimationState.SCHEDULED, a.state)
    }

    @Test
    fun aListenerCanBeRemoved() {
        val r = registry()
        val h = handle(r, durationMs = 1000)
        val rec = Recorder()
        val subscription = h.addListener(rec)
        h.play()
        r.tick(frame(0))
        val before = rec.events.size

        subscription.dispose()
        r.tick(frame(1))
        assertEquals(before, rec.events.size)
        assertTrue(subscription.isDisposed)
    }

    // --- re-entrancy: a listener acting on the handle it observes -------------

    @Test
    fun everyCallbackTripleDescribesOneRealExecution() {
        // The regression test for the bug this pattern hides: a listener that restarts the
        // handle from inside onUpdate must not leave a LATER listener holding the previous
        // execution's id beside the new execution's numbers.
        //
        // Ticking to a frame where progress is already non-zero before the restart fires is
        // what makes this test able to fail: at elapsed 0 the pre- and post-restart progress
        // are both 0f by coincidence, so a version built on that one frame alone cannot tell
        // captured values from live ones that happen to match. Advancing once first, then
        // attaching the listeners and restarting from the following tick, gives the two paths
        // different numbers.
        val r = registry()
        val h = handle(r, durationMs = 32)
        h.play()
        r.tick(frame(0))

        val seen = mutableListOf<Triple<Long, Float, Float>>()
        var restarted = false
        h.addListener(object : AnimationListener {
            override fun onUpdate(
                handle: AnimationHandle,
                executionId: Long,
                progress: Float,
                value: Float,
            ) {
                if (!restarted) {
                    restarted = true
                    handle.restart()
                }
            }
        })
        h.addListener(object : AnimationListener {
            override fun onUpdate(
                handle: AnimationHandle,
                executionId: Long,
                progress: Float,
                value: Float,
            ) {
                seen += Triple(executionId, progress, value)
            }
        })

        r.tick(frame(1))       // 16ms of 32ms: progress 0.5 before the first listener restarts it

        // The second listener is called during the dispatch the first one restarted from.
        // Whatever id it is given, the progress and value must be the ones that id's execution
        // actually reached this frame - not the zero the restart just reset the live fields to.
        assertEquals(1, seen.size)
        val (id, progress, value) = seen.single()
        assertEquals("the restart must not rewrite the numbers this id was paired with", 0L, id)
        assertEquals(0.5f, progress, 1e-6f)
        assertEquals(0.5f, value, 1e-6f)
    }

    @Test
    fun aListenerMayRestartTheHandleItObserves() {
        // Re-entrancy at the handle layer, which nothing exercised before. The restart takes
        // effect, and RULE-013 keeps the new execution off this frame.
        val r = registry()
        val h = handle(r, durationMs = 1000)
        var restarts = 0
        h.addListener(object : AnimationListener {
            override fun onUpdate(
                handle: AnimationHandle,
                executionId: Long,
                progress: Float,
                value: Float,
            ) {
                if (restarts == 0) {
                    restarts++
                    handle.restart()
                }
            }
        })
        h.play()
        r.tick(frame(0))
        assertEquals(1L, h.executionId)
        assertEquals(AnimationState.SCHEDULED, h.state)

        r.tick(frame(1))
        assertEquals(AnimationState.RUNNING, h.state)
    }

    @Test
    fun aListenerMayDisposeTheHandleItObserves() {
        val r = registry()
        val h = handle(r, durationMs = 1000)
        h.addListener(object : AnimationListener {
            override fun onUpdate(
                handle: AnimationHandle,
                executionId: Long,
                progress: Float,
                value: Float,
            ) {
                handle.dispose()
            }
        })
        h.play()
        r.tick(frame(0))
        assertTrue(h.isDisposed)
        assertEquals(0, r.size)
        // And the frame that disposed it completes without the registry throwing.
        r.tick(frame(1))
    }
```

- [ ] **Step 2: Verify the types do not exist yet**

Run: `ls frameworks/base/aurora/runtime/java/aurora/runtime/animation/`
Expected: `AnimationRegistry.kt`, `AnimationStateMachine.kt`, `ExecutionTimeline.kt`,
`TimedStrategy.kt` and nothing else.

- [ ] **Step 3a: Write `AnimationHandleImpl.kt`**

```kotlin
/*
 * Copyright (C) 2026 The AuroraOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package aurora.runtime.animation

import aurora.sdk.animation.Animation
import aurora.sdk.animation.AnimationHandle
import aurora.sdk.animation.AnimationListener
import aurora.sdk.animation.AnimationSpec
import aurora.sdk.animation.AnimationState
import aurora.sdk.animation.AnimationStrategy
import aurora.sdk.animation.PhysicsSpec
import aurora.sdk.animation.TimedSpec
import aurora.sdk.event.Disposable
import aurora.sdk.time.FrameTime

/**
 * One animation, bound to the engine.
 *
 * Holds four things and coordinates them: the pure [AnimationStateMachine], an
 * [ExecutionTimeline] for elapsed time, an [AnimationStrategy] for progress, and the listener
 * list. Nothing here decides *what* is legal or *how far through* anything is; both of those
 * questions belong to objects that can be tested without a handle.
 *
 * Public rather than `internal` on purpose: the host tests are a separate Soong module, and
 * Kotlin `internal` is module-scoped, so an internal handle would be untestable.
 */
class AnimationHandleImpl(
    override val animation: Animation,
    private val registry: AnimationRegistry,
) : AnimationHandle, AnimationRegistry.Tickable {

    private val strategy: AnimationStrategy = strategyFor(animation.spec)
    private val execution = ExecutionTimeline()

    @Volatile
    override var state: AnimationState = AnimationState.IDLE
        private set

    @Volatile
    override var executionId: Long = 0L
        private set

    @Volatile
    override var progress: Float = 0f
        private set

    @Volatile
    override var value: Float = animation.valueAt(strategy.easedProgress)
        private set

    /** Which state a pause came from, so resuming returns there. */
    private var pausedFrom: AnimationState = AnimationState.RUNNING

    /**
     * The frame this execution entered the registry on, or [AnimationRegistry.NOT_TICKING].
     *
     * RULE-013: an execution scheduled from inside frame N must not also advance in frame N,
     * or where it started would depend on listener order.
     */
    private var scheduledOnFrame: Long = AnimationRegistry.NOT_TICKING

    /**
     * Copy-on-write, so dispatch allocates nothing.
     *
     * A frame walks this array for every running animation. Copying on subscribe instead --
     * which is rare -- keeps the per-frame path free of garbage, and makes a listener added or
     * removed during a dispatch take effect on the next one rather than corrupting this one.
     */
    @Volatile
    private var listeners: Array<AnimationListener> = emptyArray()

    override val isTickable: Boolean
        get() = state.isActive

    // --- lifecycle -----------------------------------------------------------

    override fun play() = dispatch(AnimationEvent.PLAY)

    override fun pause() = dispatch(AnimationEvent.PAUSE)

    override fun resume() = dispatch(AnimationEvent.RESUME)

    override fun cancel() = dispatch(AnimationEvent.CANCEL)

    override fun restart() = dispatch(AnimationEvent.RESTART)

    override fun dispose() = dispatch(AnimationEvent.DISPOSE)

    override fun seek(progress: Float) {
        check(state == AnimationState.SCHEDULED ||
              state == AnimationState.RUNNING ||
              state == AnimationState.PAUSED) {
            "seek is not legal in state $state: seeking positions a live execution, and a " +
                "finished one has no position to move. Use restart() first."
        }
        require(progress in 0f..1f) { "progress must be 0..1, was $progress" }

        val spec = animation.spec
        if (spec !is TimedSpec) {
            throw UnsupportedOperationException(
                "seeking is defined only for a TimedSpec; ${spec.javaClass.simpleName} has no " +
                    "elapsed time to jump to"
            )
        }

        val targetNanos = spec.elapsedForProgress(progress)
        execution.seekTo(targetNanos)
        strategy.seekTo(progress)
        strategy.advance(targetNanos, 0L)
        publishUpdate()
    }

    override fun addListener(listener: AnimationListener): Disposable {
        check(state != AnimationState.DISPOSED) { "cannot observe a disposed handle" }
        listeners += listener
        return Subscription(listener)
    }

    // --- being driven --------------------------------------------------------

    override fun tick(frameTime: FrameTime) {
        if (!state.isActive) return
        // RULE-013. Also covers a restart from inside the frame currently being processed.
        if (frameTime.frameIndex <= scheduledOnFrame) return

        if (state == AnimationState.SCHEDULED) dispatch(AnimationEvent.TICK)

        val elapsed = execution.advanceTo(frameTime.frameTimeNanos)
        strategy.advance(elapsed, frameTime.deltaNanos)
        publishUpdate()

        if (strategy.isFinished) dispatch(AnimationEvent.FINISH)
    }

    // --- the one place state changes -----------------------------------------

    private fun dispatch(event: AnimationEvent) {
        val from = state
        if (from == AnimationState.DISPOSED && event != AnimationEvent.DISPOSE) {
            throw IllegalStateException(
                "$event is not legal on a disposed handle (${animation.name})"
            )
        }

        // Throws when the event is illegal, before anything has been mutated.
        val to = AnimationStateMachine.next(from, event, pausedFrom)

        when (event) {
            AnimationEvent.PLAY -> enterRegistry()

            AnimationEvent.RESTART -> {
                executionId++
                strategy.reset()
                execution.reset()
                pausedFrom = AnimationState.RUNNING
                progress = 0f
                value = animation.valueAt(strategy.easedProgress)
                enterRegistry()
            }

            AnimationEvent.PAUSE -> if (from != AnimationState.PAUSED) {
                pausedFrom = from
                execution.pause()
                // A paused animation is not advanced, so it leaves the registry: an engine
                // whose animations are all paused should stop asking for frames.
                registry.remove(this)
            }

            AnimationEvent.RESUME -> {
                execution.resume()
                enterRegistry()
            }

            AnimationEvent.CANCEL -> if (!from.isResting) registry.remove(this)

            AnimationEvent.FINISH -> registry.remove(this)

            AnimationEvent.DISPOSE -> registry.remove(this)

            AnimationEvent.TICK -> Unit
        }

        if (to != from) {
            state = to
            publishStateChange(from, to)
        }

        // After the notification, so a listener still hears about the disposal it caused.
        if (event == AnimationEvent.DISPOSE) listeners = emptyArray()
    }

    private fun enterRegistry() {
        scheduledOnFrame = registry.tickingFrameIndex
        registry.add(this)
    }

    // --- notification --------------------------------------------------------

    private fun publishUpdate() {
        // Everything a listener is handed is captured before the loop, not read from the fields
        // inside it. A listener may restart or seek this very handle from its callback, which
        // rewrites progress, value and executionId - and a later listener in the same dispatch
        // would then be handed one execution's id with another execution's numbers. That triple
        // would describe no real moment of any real run, which is precisely the ambiguity
        // executionId exists to prevent (RULE-012).
        val p = strategy.progress
        val v = animation.valueAt(strategy.easedProgress)
        progress = p
        value = v
        val snapshot = listeners
        val id = executionId
        var i = 0
        while (i < snapshot.size) {
            snapshot[i].onUpdate(this, id, p, v)
            i++
        }
    }

    private fun publishStateChange(from: AnimationState, to: AnimationState) {
        // from and to are already parameters rather than fields, so the same discipline
        // publishUpdate needs applies here too, and must keep applying if this ever changes.
        val snapshot = listeners
        val id = executionId
        var i = 0
        while (i < snapshot.size) {
            snapshot[i].onStateChanged(this, id, from, to)
            i++
        }
    }

    private inner class Subscription(private val listener: AnimationListener) : Disposable {

        @Volatile
        private var removed = false

        override val isDisposed: Boolean
            get() = removed

        override fun dispose() {
            if (removed) return
            removed = true
            val current = listeners
            val at = current.indexOfFirst { it === listener }
            if (at < 0) return
            // Removes one occurrence, not every equal one: the same listener may legitimately
            // be registered twice and unsubscribed once.
            listeners = Array(current.size - 1) { i -> if (i < at) current[i] else current[i + 1] }
        }
    }

    private companion object {

        fun strategyFor(spec: AnimationSpec): AnimationStrategy = when (spec) {
            is TimedSpec -> TimedStrategy(spec)
            is PhysicsSpec -> throw UnsupportedOperationException(
                "physics animations arrive in Sprint 06B; ${spec.javaClass.simpleName} has no " +
                    "solver yet"
            )
        }
    }
}
```

- [ ] **Step 3b: Write `DefaultAnimator.kt`**

```kotlin
/*
 * Copyright (C) 2026 The AuroraOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package aurora.runtime.animation

import aurora.sdk.animation.Animation
import aurora.sdk.animation.AnimationHandle
import aurora.sdk.animation.Animator

/**
 * Makes handles, and nothing else.
 *
 * Every question about a running animation is answered by the handle, so this stays a
 * factory. It holds no list of its own: the registry already knows what is running, and a
 * second list would be a second truth to keep in step.
 */
class DefaultAnimator(private val registry: AnimationRegistry) : Animator {

    override fun create(animation: Animation): AnimationHandle =
        AnimationHandleImpl(animation, registry)

    override fun play(animation: Animation): AnimationHandle =
        create(animation).also { it.play() }

    /**
     * Cancels every animation being advanced.
     *
     * Over a snapshot, because cancelling removes from the registry. Cancels rather than
     * disposes: a caller holding one of these handles can still restart it. The snapshot holds
     * only committed entries, so an animation played by an earlier listener in the same frame is
     * still queued and survives this cancel.
     */
    override fun cancelAll() {
        registry.snapshot().forEach { if (it is AnimationHandle) it.cancel() }
    }

    override val activeCount: Int
        get() = registry.size
}
```

- [ ] **Step 4: Run the architecture gate**

Run: `bash frameworks/base/aurora/tools/arch-test.sh`
Expected: `ARCH TEST PASS`.

- [ ] **Step 5: Commit**

```bash
git add frameworks/base/aurora/runtime/java/aurora/runtime/animation/AnimationHandleImpl.kt \
        frameworks/base/aurora/runtime/java/aurora/runtime/animation/DefaultAnimator.kt \
        frameworks/base/aurora/tests/java/aurora/runtime/animation/AnimationLifecycleTest.kt
git commit -m "Sprint 06A: AnimationHandleImpl and DefaultAnimator

The handle coordinates four things and decides none of them: the pure state
machine says what is legal, ExecutionTimeline says how long, the strategy
says how far, and the listener array says who hears about it. Each of those
is testable without a handle, which is the point.

One dispatch() is the only place state changes, and it asks the machine
before mutating anything - so an illegal call leaves the handle exactly as
it was rather than half-transitioned.

Listeners are copy-on-write: a frame walks the array for every running
animation, so copying on subscribe instead keeps the per-frame path free of
garbage, and a listener added during a dispatch takes effect next time
rather than corrupting the current one.

Pausing removes the handle from the registry, so an engine whose animations
are all paused stops asking for frames.

Public, not internal: the host tests are a separate Soong module and Kotlin
internal is module-scoped."
```

---

## Task 13: `DefaultAnimationController`

**Files:**
- Create: `frameworks/base/aurora/runtime/java/aurora/runtime/animation/DefaultAnimationController.kt`
- Modify: `frameworks/base/aurora/tests/java/aurora/runtime/animation/AnimationRegistryTest.kt`

- [ ] **Step 1: Write the failing test**

Add these imports to `AnimationRegistryTest.kt`:

```kotlin
import aurora.sdk.animation.Animation
import aurora.sdk.animation.AnimationState
import aurora.sdk.animation.TimedSpec
import aurora.sdk.time.Timeline
import org.junit.Assert.fail
```

Append to `AnimationRegistryTest`:

```kotlin
    // --- DefaultAnimationController ------------------------------------------

    private fun controller() = DefaultAnimationController().also { it.start() }

    @Test
    fun aControllerRefusesFramesBeforeItIsStarted() {
        val c = DefaultAnimationController()
        assertFalse(c.isRunning)
        try {
            c.tick(frame(0))
            fail("ticking a stopped engine must be loud, not silently ignored")
        } catch (expected: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun aFrameIndexThatDoesNotIncreaseIsRejected() {
        // RULE-006 monotonicity, applied to frames. A repeated or reordered frame would make
        // an animation advance twice for one instant, which no amount of care above could fix.
        val c = controller()
        c.tick(frame(0))
        c.tick(frame(1))
        listOf(1L, 0L).forEach { index ->
            try {
                c.tick(frame(index))
                fail("frame index $index after 1 must be rejected")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("frame"))
            }
        }
    }

    @Test
    fun aFrameIndexMayJumpForwardBecauseFramesGetDropped() {
        val c = controller()
        c.tick(frame(0))
        c.tick(frame(9))
    }

    @Test
    fun stoppingLeavesRunningAnimationsWhereTheyAre() {
        // A display turning off must not visibly reset the interface when it comes back.
        val c = controller()
        val h = c.animator.play(Animation("a", TimedSpec(Timeline.ofMillis(1000))))
        c.tick(frame(0))
        c.tick(frame(1))
        val whereItWas = h.progress

        c.stop()
        assertFalse(c.isRunning)
        assertEquals(AnimationState.RUNNING, h.state)
        assertEquals(whereItWas, h.progress, 0f)
    }

    @Test
    fun restartingTheEngineAcceptsFrameNumberingFromZeroAgain() {
        // A frame source restarted after a stop legitimately begins counting again.
        val c = controller()
        c.tick(frame(0))
        c.tick(frame(1))
        c.stop()
        c.start()
        c.tick(frame(0))
    }

    @Test
    fun theAnimatorAndTheEngineShareOneRegistry() {
        val c = controller()
        c.animator.play(Animation("a", TimedSpec(Timeline.ofMillis(1000))))
        assertEquals(1, c.animator.activeCount)
    }

    // --- RULE-011: coherence --------------------------------------------------

    @Test
    fun everyAnimationInAFrameAdvancesByTheSameElapsedTime() {
        // The point of one driver rather than one per animation. Three animations started on
        // three different frames must still agree about how much time a frame is worth. With
        // per-animation drivers each would build its own FrameTime and they would disagree.
        val c = controller()
        val a = c.animator.play(Animation("a", TimedSpec(Timeline.ofMillis(1000))))
        c.tick(frame(0))
        val b = c.animator.play(Animation("b", TimedSpec(Timeline.ofMillis(1000))))
        c.tick(frame(1))
        val d = c.animator.play(Animation("d", TimedSpec(Timeline.ofMillis(1000))))
        c.tick(frame(2))
        c.tick(frame(3))

        val beforeA = a.progress
        val beforeB = b.progress
        val beforeD = d.progress
        c.tick(frame(4))

        assertEquals(a.progress - beforeA, b.progress - beforeB, 1e-6f)
        assertEquals(a.progress - beforeA, d.progress - beforeD, 1e-6f)
    }
```

- [ ] **Step 2: Verify the type does not exist yet**

Run: `ls frameworks/base/aurora/runtime/java/aurora/runtime/animation/DefaultAnimationController.kt`
Expected: `No such file or directory`.

- [ ] **Step 3: Write the implementation**

```kotlin
/*
 * Copyright (C) 2026 The AuroraOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package aurora.runtime.animation

import aurora.sdk.animation.AnimationController
import aurora.sdk.animation.Animator
import aurora.sdk.time.FrameTime

/**
 * The engine, seen from the frame source.
 *
 * Thin on purpose. It owns the registry, hands out an [Animator] backed by it, and guards the
 * one door time comes through.
 *
 * ## No clock, by construction
 *
 * There is no `AuroraClock` parameter here or anywhere below it. RULE-008 is therefore not a
 * discipline the engine has to keep — there is simply nothing to read. That is a stronger
 * guarantee than any test could give, and it is why the animation packages take a `FrameTime`
 * and never a clock.
 */
class DefaultAnimationController(
    val registry: AnimationRegistry = AnimationRegistry(),
) : AnimationController {

    override val animator: Animator = DefaultAnimator(registry)

    @Volatile
    private var running: Boolean = false

    private var lastFrameIndex: Long = NO_FRAME_YET

    override val isRunning: Boolean
        get() = running

    /**
     * Begins accepting frames.
     *
     * Frame numbering is reset, because a frame source restarted after a [stop] legitimately
     * begins counting again and would otherwise be rejected forever.
     */
    override fun start() {
        running = true
        lastFrameIndex = NO_FRAME_YET
    }

    /**
     * Stops accepting frames.
     *
     * Running animations are left exactly where they are rather than cancelled: a display
     * turning off must not visibly reset the interface when it comes back.
     */
    override fun stop() {
        running = false
    }

    override fun tick(frameTime: FrameTime) {
        check(running) {
            "tick() before start(); a stopped engine ignoring frames silently would look " +
                "exactly like an engine that had stopped animating for some other reason"
        }
        require(frameTime.frameIndex > lastFrameIndex) {
            "frame index must increase: got ${frameTime.frameIndex} after $lastFrameIndex. " +
                "A repeated frame would advance every animation twice for one instant."
        }
        lastFrameIndex = frameTime.frameIndex
        registry.tick(frameTime)
    }

    private companion object {
        /** Lower than any real frame index, so the first frame always passes. */
        const val NO_FRAME_YET: Long = -1L
    }
}
```

- [ ] **Step 4: Run the architecture gate**

Run: `bash frameworks/base/aurora/tools/arch-test.sh`
Expected: `ARCH TEST PASS`.

- [ ] **Step 5: Commit**

```bash
git add frameworks/base/aurora/runtime/java/aurora/runtime/animation/DefaultAnimationController.kt \
        frameworks/base/aurora/tests/java/aurora/runtime/animation/AnimationRegistryTest.kt
git commit -m "Sprint 06A: DefaultAnimationController

Thin by design: owns the registry, hands out an Animator backed by it, and
guards the one door time comes through.

Frame index must increase (RULE-006 applied to frames). A repeated frame
would advance every animation twice for one instant, which nothing above
could correct for.

stop() leaves running animations where they are rather than cancelling them:
a display turning off must not visibly reset the interface when it returns.
start() resets frame numbering, because a restarted frame source legitimately
begins counting again.

There is no AuroraClock parameter here or anywhere below it. RULE-008 is not
a discipline the engine keeps - there is nothing to read."
```

---

## Task 14: `AnimationDriver`

**Files:**
- Create: `frameworks/base/aurora/runtime/java/aurora/runtime/animation/AnimationDriver.kt`
- Modify: `frameworks/base/aurora/tests/java/aurora/runtime/animation/AnimationRegistryTest.kt`

The only class that touches `FrameScheduler`. Tested against `QueuedFrameScheduler` from the
time tier, which hands out frames when the test says so.

- [ ] **Step 1: Write the failing test**

Add these imports to `AnimationRegistryTest.kt`:

```kotlin
import aurora.runtime.time.QueuedFrameScheduler
import aurora.runtime.time.TestClock
```

Append to `AnimationRegistryTest`:

```kotlin
    // --- AnimationDriver ------------------------------------------------------

    private class Rig {
        val clock = TestClock()
        val scheduler = QueuedFrameScheduler(clock)
        val controller = DefaultAnimationController()
        val driver = AnimationDriver(scheduler, controller, controller.registry)
        val animator get() = controller.animator
    }

    @Test
    fun anIdleEngineAsksForNoFrames() {
        // The whole reason the driver has to be deliberate about this: with one callback for
        // everything, nothing stops posting on its own the way a per-animation driver did.
        val rig = Rig()
        rig.driver.start()
        assertEquals(0, rig.scheduler.pendingCount)
    }

    @Test
    fun startingAnAnimationWakesTheDriver() {
        val rig = Rig()
        rig.driver.start()
        rig.animator.play(Animation("a", TimedSpec(Timeline.ofMillis(100))))
        assertEquals(1, rig.scheduler.pendingCount)
    }

    @Test
    fun theDriverKeepsPostingWhileSomethingRuns() {
        val rig = Rig()
        rig.driver.start()
        rig.animator.play(Animation("a", TimedSpec(Timeline.ofMillis(1000))))
        repeat(5) {
            rig.scheduler.advanceOneFrame()
            assertEquals("still animating, so still asking for frames", 1, rig.scheduler.pendingCount)
        }
    }

    @Test
    fun theDriverStopsPostingWhenTheLastAnimationEnds() {
        // A running engine that never stops asking would wake a core every refresh forever.
        val rig = Rig()
        rig.driver.start()
        val h = rig.animator.play(Animation("a", TimedSpec(Timeline.ofMillis(32))))
        rig.scheduler.runToIdle(maxFrames = 50)
        assertEquals(AnimationState.COMPLETED, h.state)
        assertEquals(0, rig.scheduler.pendingCount)
    }

    @Test
    fun theDriverWakesAgainAfterGoingIdle() {
        val rig = Rig()
        rig.driver.start()
        val h = rig.animator.play(Animation("a", TimedSpec(Timeline.ofMillis(32))))
        rig.scheduler.runToIdle(maxFrames = 50)
        assertEquals(0, rig.scheduler.pendingCount)

        h.restart()
        assertEquals("a restarted animation must wake the driver", 1, rig.scheduler.pendingCount)
    }

    @Test
    fun theDriverBuildsAMonotonicFrameSequence() {
        // One FrameTime per frame, indices increasing, deltas measured from the timestamps.
        // The controller rejects anything else, so this passing is the proof.
        val rig = Rig()
        rig.driver.start()
        rig.animator.play(Animation("a", TimedSpec(Timeline.ofMillis(1000))))
        repeat(20) { rig.scheduler.advanceOneFrame() }
    }

    @Test
    fun stoppingTheDriverCancelsThePendingCallback() {
        val rig = Rig()
        rig.driver.start()
        rig.animator.play(Animation("a", TimedSpec(Timeline.ofMillis(1000))))
        assertEquals(1, rig.scheduler.pendingCount)
        rig.driver.stop()
        assertEquals(0, rig.scheduler.pendingCount)
        assertFalse(rig.controller.isRunning)
    }

    @Test
    fun oneFrameAdvancesEveryAnimationExactlyOnce() {
        // RULE-013 end to end, through the real driver.
        val rig = Rig()
        rig.driver.start()
        val counts = IntArray(3)
        listOf(0, 1, 2).forEach { i ->
            val h = rig.animator.play(Animation("a$i", TimedSpec(Timeline.ofMillis(1000))))
            h.addListener(object : aurora.sdk.animation.AnimationListener {
                override fun onUpdate(
                    handle: aurora.sdk.animation.AnimationHandle,
                    executionId: Long,
                    progress: Float,
                    value: Float,
                ) {
                    counts[i]++
                }
            })
        }
        rig.scheduler.advanceOneFrame()
        assertTrue(counts.all { it == 1 })
        rig.scheduler.advanceOneFrame()
        assertTrue(counts.all { it == 2 })
    }
```

- [ ] **Step 2: Verify the type does not exist yet**

Run: `ls frameworks/base/aurora/runtime/java/aurora/runtime/animation/AnimationDriver.kt`
Expected: `No such file or directory`.

- [ ] **Step 3: Write the implementation**

```kotlin
/*
 * Copyright (C) 2026 The AuroraOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package aurora.runtime.animation

import aurora.sdk.animation.AnimationController
import aurora.sdk.event.Disposable
import aurora.sdk.time.FrameCallback
import aurora.sdk.time.FrameScheduler
import aurora.sdk.time.FrameTime

/**
 * The single frame callback for the whole engine.
 *
 * ## RULE-011
 *
 * One [FrameCallback] is posted per frame. One [FrameTime] is built from it. That same
 * instance reaches every animation, so twenty animations in a Dynamic Island transition
 * cannot drift apart — not because they are careful, but because there is only one timestamp
 * in existence for the frame. See ADR-004.
 *
 * ## Waking and sleeping
 *
 * The driver stops posting when the registry empties, and the registry wakes it when
 * something is added. A per-animation driver got this for free, since each run simply stopped
 * re-posting for itself; a batched driver has to do it deliberately or an idle engine wakes a
 * core at every display refresh forever.
 *
 * ## What this class is not allowed to know
 *
 * Nothing about `Choreographer`, threads or loopers. It talks to a [FrameScheduler], which on
 * a host is driven by hand and on device will be implemented in `aurora.platform` in Sprint
 * 08. That seam is why the entire engine is testable with no device.
 */
class AnimationDriver(
    private val scheduler: FrameScheduler,
    private val controller: AnimationController,
    private val registry: AnimationRegistry,
) : FrameCallback {

    private var pending: Disposable? = null
    private var lastFrameNanos: Long = UNSET
    private var nextFrameIndex: Long = 0L
    private var started: Boolean = false

    /** Starts the engine and begins asking for frames when there is anything to advance. */
    fun start() {
        if (started) return
        started = true
        registry.onWake = { postIfNeeded() }
        controller.start()
        postIfNeeded()
    }

    /** Stops the engine and cancels any pending frame request. */
    fun stop() {
        if (!started) return
        started = false
        registry.onWake = null
        pending?.dispose()
        pending = null
        controller.stop()
    }

    override fun doFrame(frameTimeNanos: Long) {
        pending = null

        val frameTime =
            if (lastFrameNanos == UNSET) {
                FrameTime.first(frameTimeNanos)
            } else {
                FrameTime(
                    frameTimeNanos = frameTimeNanos,
                    deltaNanos = frameTimeNanos - lastFrameNanos,
                    frameIndex = nextFrameIndex,
                )
            }
        lastFrameNanos = frameTimeNanos
        nextFrameIndex = frameTime.frameIndex + 1

        controller.tick(frameTime)

        // After the frame, so animations started from a listener are already committed and
        // an engine that just emptied stops here rather than posting one wasted frame.
        postIfNeeded()
    }

    private fun postIfNeeded() {
        if (!started) return
        if (pending != null) return
        if (registry.size == 0) return
        pending = scheduler.postFrame(this)
    }

    private companion object {
        /** No frame delivered yet. Not zero, which is a legitimate frame timestamp. */
        const val UNSET: Long = Long.MIN_VALUE
    }
}
```

- [ ] **Step 4: Run the architecture gate**

Run: `bash frameworks/base/aurora/tools/arch-test.sh`
Expected: `ARCH TEST PASS`. Confirm `runtime: no call to Choreographer` is still `ok` — the
driver names the concept in a comment only, and the checker ignores comment lines.

- [ ] **Step 5: Commit**

```bash
git add frameworks/base/aurora/runtime/java/aurora/runtime/animation/AnimationDriver.kt \
        frameworks/base/aurora/tests/java/aurora/runtime/animation/AnimationRegistryTest.kt
git commit -m "Sprint 06A: AnimationDriver, one callback for the whole engine

One FrameCallback per frame, one FrameTime built from it, that same instance
to every animation. Twenty animations in a Dynamic Island transition cannot
drift apart - not because they are careful, but because only one timestamp
for the frame exists (RULE-011, ADR-004).

The driver stops posting when the registry empties and the registry wakes it
when something is added. A per-animation driver got that free, since each run
stopped re-posting for itself; a batched one has to do it deliberately or an
idle engine wakes a core at every refresh forever.

It knows nothing of Choreographer, threads or loopers - only FrameScheduler,
which a host test drives by hand and Sprint 08 will implement for real."
```

---

## Task 15: `AnimationDeterminismTest`

**Files:**
- Test: `frameworks/base/aurora/tests/java/aurora/runtime/animation/AnimationDeterminismTest.kt`

No production code in this task. RULE-009 is the sprint headline exit criterion and it
deserves a file that does nothing else.

- [ ] **Step 1: Write the test**

```kotlin
/*
 * Copyright (C) 2026 The AuroraOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package aurora.runtime.animation

import aurora.sdk.animation.Animation
import aurora.sdk.animation.AnimationHandle
import aurora.sdk.animation.AnimationListener
import aurora.sdk.animation.Interpolator
import aurora.sdk.animation.TimedSpec
import aurora.sdk.time.AuroraClock
import aurora.sdk.time.FrameTime
import aurora.sdk.time.Timeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RULE-009: the same sequence of frames always produces the same result.
 *
 * Independent of wall clock, thread and frame rate. Everything here compares floats with a
 * tolerance of exactly zero, because "nearly the same" is not what determinism means — a
 * replay that is nearly the same cannot be used to chase a bug.
 */
class AnimationDeterminismTest {

    private val ms = AuroraClock.NANOS_PER_MILLI

    /** A curve with some shape to it, so a bug cannot hide behind a straight line. */
    private val easeOut = Interpolator { p -> 1f - (1f - p) * (1f - p) }

    private fun animation(name: String, durationMs: Long = 300) =
        Animation(name, TimedSpec(Timeline.ofMillis(durationMs), easeOut), from = 10f, to = 90f)

    /** Records every value an animation reports, in order. */
    private class Trace : AnimationListener {
        val values = mutableListOf<Float>()
        override fun onUpdate(
            handle: AnimationHandle,
            executionId: Long,
            progress: Float,
            value: Float,
        ) {
            values += value
        }
    }

    /** A fresh engine, so no test can inherit state from another. */
    private fun engine(): DefaultAnimationController =
        DefaultAnimationController().also { it.start() }

    private fun frameAt(index: Long, nanos: Long, deltaNanos: Long) =
        FrameTime(frameTimeNanos = nanos, deltaNanos = deltaNanos, frameIndex = index)

    /** Drives [frames] evenly spaced by [stepNanos], returning the values reported. */
    private fun run(stepNanos: Long, frames: Int, durationMs: Long = 300): List<Float> {
        val c = engine()
        val trace = Trace()
        val h = c.animator.create(animation("a", durationMs))
        h.addListener(trace)
        h.play()
        var t = 0L
        for (i in 0 until frames) {
            c.tick(frameAt(i.toLong(), t, if (i == 0) 0L else stepNanos))
            t += stepNanos
        }
        return trace.values
    }

    // --- 1. Replay ------------------------------------------------------------

    @Test
    fun twoEnginesFedTheSameFramesProduceIdenticalValues() {
        val first = run(stepNanos = 16 * ms, frames = 25)
        val second = run(stepNanos = 16 * ms, frames = 25)
        assertEquals(first.size, second.size)
        first.indices.forEach {
            assertEquals("frame $it", first[it], second[it], 0f)
        }
    }

    @Test
    fun replayingIsStableAcrossManyRuns() {
        val reference = run(stepNanos = 16 * ms, frames = 25)
        repeat(20) { attempt ->
            assertEquals("run $attempt differs", reference, run(stepNanos = 16 * ms, frames = 25))
        }
    }

    // --- 2. Frame rate independence -------------------------------------------

    @Test
    fun theValueAtAGivenElapsedTimeDoesNotDependOnTheFrameRate() {
        // 60Hz, 120Hz and 250Hz all land on 96ms exactly. The value there must be one number,
        // not three. This is what stops an animation looking different on a 120Hz panel.
        fun valueAt96ms(stepNanos: Long): Float {
            val c = engine()
            val h = c.animator.play(animation("a"))
            var t = 0L
            var i = 0L
            while (t <= 96 * ms) {
                c.tick(frameAt(i, t, if (i == 0L) 0L else stepNanos))
                if (t == 96 * ms) return h.value
                t += stepNanos
                i++
            }
            throw AssertionError("never landed on 96ms with step $stepNanos")
        }

        val at60 = valueAt96ms(16 * ms)     // 96 = 16 * 6
        val at120 = valueAt96ms(8 * ms)     // 96 = 8 * 12
        val at250 = valueAt96ms(4 * ms)     // 96 = 4 * 24

        assertEquals(at60, at120, 0f)
        assertEquals(at60, at250, 0f)
    }

    @Test
    fun irregularFrameSpacingReachesTheSamePlaceAsRegularSpacing() {
        // A busy device delivers frames at uneven intervals. Two animations that arrive at
        // 200ms by different routes must agree, or motion visibly changes under load.
        val c = engine()
        val even = c.animator.play(animation("even"))
        val uneven = c.animator.play(animation("uneven"))

        // Both are in the same engine, so they see identical frames; drive an uneven sequence
        // and check that arriving at 200ms is all that matters.
        val stamps = listOf(0L, 3 * ms, 40 * ms, 41 * ms, 137 * ms, 200 * ms)
        stamps.forEachIndexed { i, t ->
            c.tick(frameAt(i.toLong(), t, if (i == 0) 0L else t - stamps[i - 1]))
        }
        val unevenValue = even.value

        val c2 = engine()
        val steady = c2.animator.play(animation("steady"))
        listOf(0L, 50 * ms, 100 * ms, 150 * ms, 200 * ms).forEachIndexed { i, t ->
            c2.tick(frameAt(i.toLong(), t, if (i == 0) 0L else 50 * ms))
        }

        assertEquals(unevenValue, steady.value, 0f)
        assertEquals(unevenValue, uneven.value, 0f)
    }

    // --- 3. Dropped frames ----------------------------------------------------

    @Test
    fun aDroppedFrameCausesNoDrift() {
        // A 100ms hitch. Progress comes from elapsed time, so the animation is exactly where
        // it should be on the next frame rather than 100ms behind forever.
        val smooth = engine()
        val hitched = engine()
        val a = smooth.animator.play(animation("a"))
        val b = hitched.animator.play(animation("b"))

        var t = 0L
        var i = 0L
        while (t <= 160 * ms) {
            smooth.tick(frameAt(i, t, if (i == 0L) 0L else 16 * ms))
            t += 16 * ms
            i++
        }

        // The same animation, but frames 1..9 never arrived.
        hitched.tick(frameAt(0, 0L, 0L))
        hitched.tick(frameAt(1, 160 * ms, 160 * ms))

        assertEquals(a.value, b.value, 0f)
    }

    // --- 4. Coherence ---------------------------------------------------------

    @Test
    fun animationsStartedOnDifferentFramesShareOneTimestamp() {
        // RULE-011. If each animation built its own FrameTime, animations started a frame apart
        // would disagree about how much time one frame is worth and would slowly separate.
        //
        // The assertion is on progress, not on value, and that distinction is the whole point.
        // Coherence is a statement about TIME: every animation in a frame is handed the same
        // frameTimeNanos, so each gains the same elapsed time and therefore the same linear
        // progress. It is not a statement about value. These animations run through an ease-out
        // curve, so three of them sitting at three different progress points have three
        // different slopes, and equal elapsed time produces unequal value change. That is the
        // curve doing its job, not the engine drifting - asserting on value here would fail for
        // a correct engine, which an earlier draft of this test did.
        // Compared as recorded values, never as differences. Two deltas equal in arithmetic are
        // not equal in float when they come from different pairs: 0.048f - 0.032f is 0.015999999
        // while 0.016f - 0f is 0.016. That cancellation is the test's own subtraction, not the
        // engine drifting, and at a tolerance of zero it would fail a perfectly coherent engine -
        // as an earlier draft of this test did.
        //
        // So the claim is made without subtracting. The three have identical specs and start
        // exactly one frame apart, so the second's progress on frame k must be bit-equal to the
        // first's on frame k-1, and the third's to the first's on frame k-2. Each side is
        // progressAt() of the same elapsed value, so equality is exact rather than approximate.
        // If any animation built its own FrameTime, those elapsed values would differ and the two
        // sides would part on the first comparison.
        val c = engine()
        val byFrame = mutableListOf<Float>()

        val first = c.animator.play(animation("first", durationMs = 1000))
        c.tick(frameAt(0, 0L, 0L))
        byFrame += first.progress                       // index 0

        val second = c.animator.play(animation("second", durationMs = 1000))
        c.tick(frameAt(1, 16 * ms, 16 * ms))
        byFrame += first.progress                       // index 1

        val third = c.animator.play(animation("third", durationMs = 1000))
        c.tick(frameAt(2, 32 * ms, 16 * ms))
        byFrame += first.progress                       // index 2

        repeat(10) {
            val k = 3 + it
            c.tick(frameAt(k.toLong(), (48 + it * 16) * ms, 16 * ms))
            byFrame += first.progress                   // index k

            assertEquals("frame $k: second lags first by one frame", byFrame[k - 1], second.progress, 0f)
            assertEquals("frame $k: third lags first by two frames", byFrame[k - 2], third.progress, 0f)
            assertTrue("a frame must actually advance them", byFrame[k] > byFrame[k - 1])
        }
    }

    // --- 5. Seeking is repeatable ---------------------------------------------

    @Test
    fun seekingToTheSameProgressAlwaysGivesTheSameValue() {
        val c = engine()
        val h = c.animator.play(animation("a"))
        c.tick(frameAt(0, 0L, 0L))

        h.seek(0.31f)
        val reference = h.value
        repeat(50) {
            h.seek(it / 50f)
            h.seek(0.31f)
            assertEquals(reference, h.value, 0f)
        }
    }

    // --- 6. Listener order and reentrancy -------------------------------------

    @Test
    fun animationsStartedFromCallbacksAreOrderedIdenticallyOnReplay() {
        // The direct test of RULE-013. A callback starting B and another starting C must
        // queue them in the same order every time, and neither may advance until the next
        // frame - otherwise where they started would depend on listener order.
        fun once(): List<String> {
            val c = engine()
            val log = mutableListOf<String>()

            fun spawner(name: String, spawn: String) {
                val h = c.animator.play(animation(name, durationMs = 1000))
                var spawned = false
                h.addListener(object : AnimationListener {
                    override fun onUpdate(
                        handle: AnimationHandle,
                        executionId: Long,
                        progress: Float,
                        value: Float,
                    ) {
                        log += name
                        if (!spawned) {
                            spawned = true
                            val child = c.animator.play(animation(spawn, durationMs = 1000))
                            child.addListener(object : AnimationListener {
                                override fun onUpdate(
                                    handle: AnimationHandle,
                                    executionId: Long,
                                    progress: Float,
                                    value: Float,
                                ) {
                                    log += spawn
                                }
                            })
                        }
                    }
                })
            }

            spawner("a1", "b")
            spawner("a2", "c")

            c.tick(frameAt(0, 0L, 0L))
            c.tick(frameAt(1, 16 * ms, 16 * ms))
            return log
        }

        val reference = once()
        // b and c are started during frame 0 and must first advance in frame 1, in the order
        // they were queued.
        assertEquals(listOf("a1", "a2", "a1", "a2", "b", "c"), reference)
        repeat(20) { assertEquals(reference, once()) }
    }

    @Test
    fun restartingFromACallbackDoesNotAdvanceTwiceInOneFrame() {
        // The one-running-execution invariant: no frame ever produces two updates for one
        // handle, even across a mid-frame restart.
        val c = engine()
        var updates = 0
        var restarted = false
        val h = c.animator.create(animation("a", durationMs = 1000))
        h.addListener(object : AnimationListener {
            override fun onUpdate(
                handle: AnimationHandle,
                executionId: Long,
                progress: Float,
                value: Float,
            ) {
                updates++
                if (!restarted) {
                    restarted = true
                    handle.restart()
                }
            }
        })
        h.play()

        c.tick(frameAt(0, 0L, 0L))
        assertEquals("one frame, one update", 1, updates)

        c.tick(frameAt(1, 16 * ms, 16 * ms))
        assertEquals(2, updates)
    }

    // --- 7. The engine holds no clock -----------------------------------------

    @Test
    fun noAnimationClassTakesAClock() {
        // RULE-008 by construction, checked so it stays that way. If a constructor ever
        // accepts an AuroraClock, the engine can read time behind the frame contract and
        // every guarantee above becomes a matter of discipline instead of structure.
        val engineClasses = listOf(
            AnimationDriver::class.java,
            DefaultAnimationController::class.java,
            DefaultAnimator::class.java,
            AnimationHandleImpl::class.java,
            AnimationRegistry::class.java,
            ExecutionTimeline::class.java,
            TimedStrategy::class.java,
            AnimationStateMachine::class.java,
        )
        engineClasses.forEach { type ->
            type.declaredConstructors.forEach { constructor ->
                constructor.parameterTypes.forEach { parameter ->
                    assertTrue(
                        "${type.simpleName} takes ${parameter.simpleName}; the animation " +
                            "engine must not be able to read a clock (RULE-008)",
                        !AuroraClock::class.java.isAssignableFrom(parameter)
                    )
                }
            }
            type.declaredFields.forEach { field ->
                assertTrue(
                    "${type.simpleName}.${field.name} is a clock (RULE-008)",
                    !AuroraClock::class.java.isAssignableFrom(field.type)
                )
            }
        }
    }
}
```

- [ ] **Step 2: Run the architecture gate**

Run: `bash frameworks/base/aurora/tools/arch-test.sh`
Expected: `ARCH TEST PASS`.

- [ ] **Step 3: Commit**

```bash
git add frameworks/base/aurora/tests/java/aurora/runtime/animation/AnimationDeterminismTest.kt
git commit -m "Sprint 06A: AnimationDeterminismTest

RULE-009, in a file that does nothing else. Every float comparison has a
tolerance of exactly zero, because nearly-the-same is not what determinism
means: a replay that is nearly the same cannot be used to chase a bug.

Seven checks. Two engines fed one frame sequence produce identical values.
60Hz, 120Hz and 250Hz all land on 96ms with one value, not three. A 100ms
hitch causes no drift. Animations started on different frames advance by
identical amounts. Seeking twice to one progress gives one value. Animations
started from callbacks are ordered identically on replay and first advance
on the next frame. And no engine class takes or holds an AuroraClock, so
RULE-008 stays structural rather than a matter of discipline."
```

---

## Task 16: `forbid-call-under` in `arch-test.sh`

**Files:**
- Modify: `frameworks/base/aurora/tools/arch-test.sh`
- Modify: `frameworks/base/aurora/contracts/runtime.contract`

Today's `forbid-call` applies to a whole layer. RULE-009's hazards are package-specific, so
the tool needs one new key.

- [ ] **Step 1: Add the contract entries**

Append to `frameworks/base/aurora/contracts/runtime.contract`:

```
# RULE-009: determinism hazards, scoped to the animation packages.
#
# forbid-call-under: <call>@<path prefix relative to frameworks/base/aurora>
#
# Unlike forbid-call, which covers the whole layer, this bans a call only beneath a path.
# Randomness has no place in an animation engine anywhere.
forbid-call-under: Math.random@runtime/java/aurora/runtime/animation
forbid-call-under: java.util.Random@runtime/java/aurora/runtime/animation

# Hash containers are banned in AnimationRegistry.kt ONLY.
#
# The hazard is iteration order, not the container: tick order is observable behaviour, so a
# hash there would let two runs of the same program tick in different orders. A HashMap used
# for lookup elsewhere - handleById, for instance - is entirely fine, which is why this is
# scoped to the one file where order becomes behaviour rather than to the package.
forbid-call-under: HashMap@runtime/java/aurora/runtime/animation/AnimationRegistry.kt
forbid-call-under: HashSet@runtime/java/aurora/runtime/animation/AnimationRegistry.kt
```

- [ ] **Step 2: Implement the check**

In `frameworks/base/aurora/tools/arch-test.sh`, add this function immediately after
`check_forbidden_calls` (which ends with its closing `}` before the `# ---` banner for
negative compiles):

```bash
# RULE-009: forbidden calls, scoped to a path rather than to a whole layer.
#
# Declared in a contract as
#   forbid-call-under: <call>@<path relative to frameworks/base/aurora>
#
# forbid-call covers an entire layer, which is right for RULE-007 - nothing in sdk or runtime
# may read the system clock. RULE-009 is narrower: the hazard is specific to the animation
# packages, and one of the two entries is specific to a single file. Banning a hash container
# across the whole runtime would reject a perfectly good lookup map.
check_forbidden_calls_under() {
  local contract="$1"
  local layer entry call rel target hits
  layer="$(value_of layer "$contract")"

  while IFS= read -r entry; do
    [ -z "$entry" ] && continue
    call="${entry%%@*}"
    rel="${entry#*@}"
    target="$AURORA_DIR/$rel"

    if [ ! -e "$target" ]; then
      skip "$layer: '$call' under $rel (path does not exist yet)"
      continue
    fi

    # Same comment-stripping as check_forbidden_calls: documentation must be able to name
    # what it forbids, and this rule's own explanation names every one of these.
    #
    # -H forces the filename prefix even when $target is a single file rather than a
    # directory: plain grep -r omits it for a lone file, which would leave the comment
    # regex below unable to find the leading "path:line:" it expects.
    hits="$(grep -rnHF "$call" "$target" --include='*.java' --include='*.kt' 2>/dev/null \
      | grep -vE '^[^:]*:[0-9]+:[[:space:]]*(\*|//|/\*)' || true)"

    if [ -n "$hits" ]; then
      fail "$layer must not call '$call' under $rel:"
      printf '%s\n' "$hits" | sed 's/^/          /'
    else
      ok "$layer: nowhere under $rel calls $call"
    fi
  done < <(values_of forbid-call-under "$contract")
}
```

Then call it from `main`, immediately after the existing `check_forbidden_calls` line:

```bash
    check_forbidden_calls "$contract"
    check_forbidden_calls_under "$contract"
    check_soong_deps "$contract"
```

Note the `-H`. `grep -r` prints a `path:line:` prefix when walking a directory but omits the path
when handed a single file, and the comment-stripping regex on the next line needs that prefix to
recognise a comment. Without it, the `AnimationRegistry.kt` entries match their own KDoc — which
legitimately names `HashMap` and `HashSet` while explaining why they are banned — and the checker
fails permanently on a clean tree.

This was found by running Step 3 rather than assuming it would pass, which is the entire reason
Step 3 exists.

- [ ] **Step 3: Verify the check passes on the current tree**

Run: `bash frameworks/base/aurora/tools/arch-test.sh`
Expected: four new `ok` lines, and `ARCH TEST PASS`:
```
  ok    runtime: nowhere under runtime/java/aurora/runtime/animation calls Math.random
  ok    runtime: nowhere under runtime/java/aurora/runtime/animation calls java.util.Random
  ok    runtime: nowhere under runtime/java/aurora/runtime/animation/AnimationRegistry.kt calls HashMap
  ok    runtime: nowhere under runtime/java/aurora/runtime/animation/AnimationRegistry.kt calls HashSet
```

- [ ] **Step 4: Verify the check actually fails on bad input**

A checker that has never failed is not known to work. Break it deliberately:

```bash
sed -i 's|private val active = ArrayList<Tickable>()|private val active = ArrayList<Tickable>()\n    private val bad = HashSet<Tickable>()|' \
    frameworks/base/aurora/runtime/java/aurora/runtime/animation/AnimationRegistry.kt
bash frameworks/base/aurora/tools/arch-test.sh
```
Expected: `FAIL  runtime must not call 'HashSet' under …/AnimationRegistry.kt:` followed by the
offending line, and `ARCH TEST FAIL`.

Then revert:
```bash
git checkout frameworks/base/aurora/runtime/java/aurora/runtime/animation/AnimationRegistry.kt
bash frameworks/base/aurora/tools/arch-test.sh
```
Expected: `ARCH TEST PASS`.

- [ ] **Step 5: Commit**

```bash
git add frameworks/base/aurora/tools/arch-test.sh \
        frameworks/base/aurora/contracts/runtime.contract
git commit -m "Sprint 06A: forbid-call-under, so RULE-009 is machine-checked

forbid-call covers a whole layer, which is right for RULE-007: nothing in
sdk or runtime may read the system clock. RULE-009 is narrower - the hazards
belong to the animation packages, and two of them to a single file.

Math.random and java.util.Random are banned across runtime/animation.
HashMap and HashSet are banned in AnimationRegistry.kt only, because the
hazard is iteration order rather than the container: tick order is
observable behaviour there, while a HashMap used for lookup elsewhere is
entirely fine. Banning it package-wide would reject good code and teach
people to ignore the checker.

Verified by breaking it on purpose and watching it fail, then reverting."
```

---

## Task 17: Collateral — `AnimationService` loses its own `AnimationHandle`

**Files:**
- Modify: `frameworks/base/aurora/sdk/java/aurora/sdk/service/AnimationService.kt`

Sprint 04 declared a narrow `AnimationHandle` in this file. Two types with one name, one of
them missing pause, resume, restart, seek and state, would be a trap: both imports compile and
the mistake shows up late. Nothing implements `AnimationService` yet, so this costs nothing
today and will never be this cheap again.

- [ ] **Step 1: Replace the local interface with an import**

In `AnimationService.kt`, delete the entire `interface AnimationHandle { … }` block — from the
`/**\n * A running animation, from the caller's point of view.` comment through the closing
brace of `fun onFinished(...)` — and add to the imports:

```kotlin
import aurora.sdk.animation.AnimationHandle
```

The import block becomes:

```kotlin
import aurora.sdk.animation.AnimationHandle
import aurora.sdk.design.Easing
import aurora.sdk.design.Spring
```

- [ ] **Step 2: Update the class documentation to point at the new handle**

Replace the `## Interruption is the point` section of the `AnimationService` KDoc with:

```kotlin
/**
 * Drives animations.
 *
 * A convenience facade over [aurora.sdk.animation.Animator] for the two shapes callers reach
 * for most. Anything needing pause, resume, restart, seek or lifecycle observation should use
 * the animator directly; this exists so that the common case is one call.
 *
 * ## Interruption is the point
 *
 * Every method here takes a *current value* rather than assuming a start of zero. Gesture-
 * driven motion is interrupted constantly — a swipe reverses, a second touch lands mid-flight
 * — and an animator that restarts from a fixed origin makes the interface visibly snap. The
 * [springTo] overload that takes an initial velocity exists for exactly this reason: it lets a
 * release continue the motion the finger was already making.
 *
 * ## Availability
 *
 * [springTo] cannot be satisfied until Sprint 06B adds the spring solver. An implementation
 * arriving before then must reject it rather than silently substituting a timed curve, since
 * a spring quietly replaced by an ease is exactly the kind of difference nobody notices in
 * review and everybody feels on device.
 */
```

- [ ] **Step 3: Run the architecture gate**

Run: `bash frameworks/base/aurora/tools/arch-test.sh`
Expected: `ARCH TEST PASS`. `aurora.sdk.service` importing `aurora.sdk.animation` is within
the layer, so no contract changes.

- [ ] **Step 4: Confirm only one `AnimationHandle` is declared in the tree**

Run:
```bash
grep -rn "^interface AnimationHandle" frameworks/base/aurora/
```
Expected: exactly one line, in `sdk/java/aurora/sdk/animation/AnimationHandle.kt`.

- [ ] **Step 5: Commit**

```bash
git add frameworks/base/aurora/sdk/java/aurora/sdk/service/AnimationService.kt
git commit -m "Sprint 06A: one AnimationHandle, not two

Sprint 04 declared a narrow AnimationHandle inside AnimationService.kt. Two
types with one name, one of them missing pause, resume, restart, seek and
state, is a trap: both imports compile and the mistake surfaces late.

Nothing implements AnimationService yet, so unifying costs nothing today and
will never be this cheap again."
```

---

## Task 18: Document the rules

**Files:**
- Modify: `frameworks/base/aurora/README.md`

The README is where the rules are cited from in review and in commit messages. Six new ones.

- [ ] **Step 1: Add the rules**

In `frameworks/base/aurora/README.md`, immediately after the RULE-008 block and before the
`### Time, in three tiers` heading, insert:

```markdown
**RULE-009 — Animation MUST be deterministic.** The same sequence of `FrameTime` values always
produces the same result, independent of wall clock, thread and frame rate. Host tests and a
device must agree frame for frame.

All mutable animation state lives in an `AnimationStrategy`. An `Interpolator` is a pure
function, and a design token is data; state hiding in either would make `seek()` and
`restart()` silently stop being repeatable, because `transform(0.5f)` twice would return two
different numbers.

Enforced by `arch-test.sh` through `forbid-call-under`, which bans `Math.random` and
`java.util.Random` beneath `runtime/java/aurora/runtime/animation`, and by
`AnimationDeterminismTest`, whose float comparisons all use a tolerance of exactly zero.

**RULE-010 — SDK defines the language, Runtime speaks it, Platform connects it to Android.**

```
aurora.sdk.animation        interface Animator          the language
aurora.runtime.animation    class DefaultAnimator       speaking it
aurora.platform.animation   class AndroidAnimatorBridge connecting it   (Sprint 08)
```

Three layers, never mixed. The practical test for a new file: if it *executes* anything beyond
arithmetic on its own fields, it does not belong in `aurora.sdk`.

**RULE-011 — One `FrameTime` per frame, shared by reference.** Exactly one is built and handed
to every animation, never cloned and never mutated. Twenty animations in one transition cannot
drift apart, because only one timestamp for the frame exists. No animation may post its own
frame callback.

**RULE-012 — Execution identity is not handle identity.** The handle is stable; executions are
ephemeral. `COMPLETED` and `CANCELLED` end an execution, `DISPOSED` ends the handle, and every
callback carries the `executionId` it belongs to so a listener from run 3 can tell it is being
handed an event from run 4.

**RULE-013 — An execution advances at most once per frame.** Anything started, restarted or
disposed from inside a listener takes effect at the end of the frame. Otherwise where an
animation started would depend on listener order, which no caller controls.

A handle therefore never has more than one execution in `RUNNING` at the same time — not for a
frame, not for an instant.

**RULE-014 — An animation callback must never mutate `FrameTime`.** Every animation in a frame
reads the same instance, so one callback dirtying it would corrupt the whole frame. `FrameTime`
is a data class of `val`s; `AnimationApiTest` asserts by reflection that every field is `final`,
so the rule fails the day someone adds a `var`.
```

- [ ] **Step 2: Add the animation tier table**

Immediately after the `### Time, in three tiers` table, insert:

```markdown
### Animation, in the same three tiers

| Layer | Holds | Examples |
|---|---|---|
| `aurora.sdk.animation` | concepts and contracts | `Animation`, `AnimationSpec`, `AnimationState`, `AnimationHandle`, `AnimationStrategy`, `Animator`, `AnimationController`, `Interpolator` |
| `aurora.runtime.animation` | the engine | `AnimationStateMachine`, `ExecutionTimeline`, `TimedStrategy`, `AnimationRegistry`, `AnimationHandleImpl`, `DefaultAnimator`, `DefaultAnimationController`, `AnimationDriver` |
| `aurora.platform.animation` | the Android bridge | `ChoreographerAnimationDriver` — Sprint 08 |

Sprint 06A builds the lifecycle and leaves the motion. There is no solver: `TimedStrategy` is
the only `AnimationStrategy`, and it delegates to `Timeline`. Sprint 06B adds `SpringStrategy`,
`BezierInterpolator`, `DecayStrategy`, `SnapStrategy` and `FlingStrategy` as new files,
touching none of the classes above.

Timing bugs and physics bugs look identical from the outside — something moved wrong — so the
half that can be proven exactly is built first. When a pixel is wrong in 06B, it is the
solver.
```

- [ ] **Step 3: Update the module table**

In the `## Module` table, add a row after the design tokens row:

```markdown
| (part of `aurora-sdk`) | `aurora.sdk.animation` | `sdk/java/aurora/sdk/animation/` | Animation contracts |
| (part of `aurora-runtime`) | `aurora.runtime.animation` | `runtime/java/aurora/runtime/animation/` | The animation engine |
```

- [ ] **Step 4: Update the future-extension list**

Replace the `**Later — Gesture work.**` paragraph with:

```markdown
**Sprint 06B — Physics.** `SpringStrategy`, `BezierInterpolator`, `DecayStrategy`,
`SnapStrategy` and `FlingStrategy`, each benchmarked. All are new files in
`aurora.runtime.animation`; the engine does not change, which is the claim Sprint 06A exists
to make true.

**Sprint 08 — Android platform bridge.** `ChoreographerFrameScheduler` and
`ChoreographerAnimationDriver` in `aurora.platform`. `AnimationController.tick(FrameTime)` is
already the entry point, so this is an adapter rather than a rework.

**Later — Gesture work.** iOS-style gesture customisation belongs in `aurora.platform`, acting
on `SystemUI` and Launcher3 QuickStep. Because gesture code lives in the framework layer and
touches no device hardware, whatever is developed on the emulator will behave identically on
real hardware.
```

- [ ] **Step 5: Commit**

```bash
git add frameworks/base/aurora/README.md
git commit -m "Sprint 06A: document RULE-009 through RULE-014

Six rules, each saying honestly what enforces it. RULE-009 and RULE-014 are
machine-checked; RULE-010 and RULE-012 rest on tests and review, and the
README says so rather than overstating its reach - a checker that claims
more than it does teaches people to ignore its output.

Also adds the animation tier table beside the time one, since the two now
have identical shape."
```

---

## Task 19: VM checkpoint 2 — every exit criterion

- [ ] **Step 1: Run the local gate one last time**

Run: `bash frameworks/base/aurora/tools/arch-test.sh`
Expected: `ARCH TEST PASS`.

- [ ] **Step 2: Sync**

Run: `.\sync-to-vm.ps1`
Expected: `=== APPLY_DONE_OK ===`.

- [ ] **Step 3: Run every gate**

On the VM:
```bash
bash /mnt/build/lineage/frameworks/base/aurora/tools/verify-sprint06a.sh
```
Expected final line: `SPRINT06A_ALL_GREEN`.

If it prints `SPRINT06A_PROBLEM`, the trailing numbers say which gate failed:
`build=0 unit=0 arch=0 android=0 coverage=0` — anything non-zero is the one to read the log
for. Logs are at `/mnt/build/s06a-build.log`, `/mnt/build/s06a-ut.log`, `/mnt/build/s06a-arch.log`.

- [ ] **Step 4: Check each exit criterion by name**

| Criterion | Evidence |
|---|---|
| Compile PASS | gate 1, `rc=0` |
| Host Test PASS | gate 2, `OK (N tests)` |
| Animation Lifecycle PASS | `AnimationLifecycleTest` in the JUnit output |
| State Machine PASS | `AnimationStateMachineTest`, and `theTableCoversEveryCell` proves all 56 were checked |
| Timeline PASS | the `ExecutionTimeline` tests in `AnimationLifecycleTest` |
| Deterministic PASS | `AnimationDeterminismTest` |
| Architecture PASS | gate 3, `ARCH TEST PASS` including the four `forbid-call-under` lines |
| No Android Dependency | gate 4, `android.* imports under animation packages: 0` |
| API coverage | gate 5, `all N public declarations are named by a test` |

- [ ] **Step 5: Commit any fixes, then report**

Any fix goes in its own commit naming the gate it repairs. When all five gates are green,
report the JUnit test count and the arch-test check count — those two numbers are what the
review will ask for.

- [ ] **Step 6: Offer to finish the branch**

Do not merge unprompted. Ask whether to fast-forward `main` onto
`sprint-06a-animation-architecture` (the branching pattern of Sprints 01–05.5b) or to keep the
branch for review.

---

## Self-review

Run against the frozen spec after the plan is written, before execution begins.

**Spec coverage.** Every section of
`docs/specs/2026-08-02-sprint-06a-animation-architecture-design.md` maps to a task:

| Spec section | Task |
|---|---|
| Layer split, file inventory | 1–6, 8–14 |
| What is deliberately not created | stated in File Structure; `TimelineDriver` untouched is verified by it never appearing in a task |
| State machine, legality table, three decisions | 8 |
| One-running-execution invariant | 15 (`restartingFromACallbackDoesNotAdvanceTwiceInOneFrame`) |
| Public API (all nine SDK files) | 1, 2, 3, 4, 5, 6 |
| Runtime pipeline (all eight files) | 8, 9, 10, 11, 12, 13, 14 |
| `ExecutionTimeline` pause/seek rules | 9 |
| Registry deferred mutation, dispose contract | 11 |
| RULE-009 … RULE-014 | 16 (enforcement), 18 (documentation), 15 (tests) |
| `arch-test.sh` `forbid-call-under` | 16 |
| Test plan, all five classes | 1–15 |
| Coverage criterion | 0 (gate 5) |
| Collateral `AnimationService` change | 17 |
| Exit criteria | 0, 19 |

**Placeholder scan.** No `TBD`, no `implement later`, no "similar to Task N", no "add error
handling". Every code step carries the code. The one instruction that describes rather than
shows is Task 17 Step 1, which deletes an existing block; the block is identified by its exact
opening comment and closing method, and Step 4 verifies the outcome by `grep`.

**Type consistency.** Checked across tasks:

- `AnimationStrategy` — `progress` / `easedProgress` / `isFinished` / `advance` / `reset` /
  `seekTo` are identical in Tasks 5, 10, 12 and 15.
- `AnimationRegistry.Tickable` — `isTickable` / `tick` identical in Tasks 11 and 12.
- `AnimationRegistry.NOT_TICKING` — used in Tasks 11 (declaration, test) and 12 (handle).
- `TimedSpec.elapsedForProgress` — declared in Task 3, used in Task 12.
- `AnimationEvent` — the eight constants are identical in Tasks 8 and 12.
- `ExecutionTimeline` — `advanceTo` / `pause` / `resume` / `seekTo` / `reset` / `elapsedNanos`
  / `hasStarted` identical in Tasks 9 and 12.
- `DefaultAnimationController.registry` is a `val` in Task 13 because Task 14 constructs
  `AnimationDriver(scheduler, controller, controller.registry)`.

**The elapsed-progress invariant.** Both directions of the mapping live on `TimedSpec`:
`elapsedForProgress` there, and `progressAt` reached through `spec.timeline` from
`TimedStrategy`. Nothing else converts between the two — `ExecutionTimeline` deals only in
elapsed nanoseconds, `AnimationStrategy.seekTo` takes a progress it does not convert, and
`AnimationHandleImpl.seek` orchestrates without computing. `AnimationApiTest` asserts the round
trip across five timeline shapes, so the pair cannot drift apart silently.

**Three fixes applied during this review:**

1. `spec::class.simpleName` was used in the Task 3 test and the Task 12 implementation.
   `KClass.simpleName` pulls in Kotlin reflection, which is not on the `core_current`
   classpath. Both now use `javaClass.simpleName`.
2. The Task 13 coherence test carried unused `listener` / `seen` scaffolding from an earlier
   draft, with a note telling the implementer to delete it. A plan that ships code it tells
   you to remove is a plan failure; the test now asserts the three deltas directly and the
   scaffolding is gone.
3. `elapsedForProgress` spanned the whole repeated sequence, which reads naturally and is
   wrong: `Timeline.progressAt` counts per iteration and resets to 0 each time round, so the
   two were not inverses and `seek(0.25f)` on a three-times timeline produced a progress of
   0.75. The original test happened to sample only 0.5 and 1.0, the two values that agree by
   coincidence — exactly what a round-trip assertion catches and test-by-example does not. The
   mapping is now per iteration and the round trip is asserted over 100 points on five
   timeline shapes.

---

## Execution

**Plan complete and saved to `docs/plans/2026-08-02-sprint-06a-animation-architecture.md`.**

Nineteen tasks. Two require the VM (7 and 19); the other seventeen run entirely on the
workstation with the local architecture gate.

Two execution options:

**1. Subagent-Driven (recommended)** — a fresh subagent per task, review between tasks, fast
iteration. Suits this plan because each task is self-contained and ends in a commit.

**2. Inline Execution** — tasks executed in this session with checkpoints for review.

Which approach?
