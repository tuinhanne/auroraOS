# Sprint 06A.5 — Sampler API Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Change the shape of the animation engine's API without changing one bit of its behaviour, so that Sprint 06B can add solvers to a vocabulary that fits them.

**Architecture:** `AnimationStrategy` becomes `MotionSampler` — one method, `sampleAt(elapsedNanos): MotionSample`, returning a position and a velocity and no policy. Whether a motion has ended moves to `AnimationSpec.isFinished(elapsed, sample)`, because a timed animation cannot derive it from a value and a spring's rest thresholds live on its spec. Elapsed time becomes the engine's canonical quantity; `progress` leaves `AnimationHandle` and `normalizedPosition` replaces it as a caller convenience.

**Tech Stack:** Kotlin (Soong `java_library`, `sdk_version: "core_current"`), JUnit 4 host tests, bash (`arch-test.sh`), GCP build VM.

**Spec:** [`docs/specs/2026-08-03-sprint-06a5-sampler-api-design.md`](../specs/2026-08-03-sprint-06a5-sampler-api-design.md) — frozen.
**Decisions:** [ADR-007](../adr/ADR-007-animation-model.md); ADR-002 and ADR-006 are amended by Task 9.
**Branch:** create `sprint-06a5-sampler-api` off `main` (`ddd2931` plus the four doc commits).

---

## Read this before Task 1

### The one rule that governs every task

> **Translate names and shapes. Never translate expected values.**

If a test needs its *field names* changed, that is migration working. If a test needs its
*expected numbers* changed, the migration has broken something — stop, and understand it before
touching the number. There is exactly one licensed exception, stated in Task 8.

267 tests pass today. 267 must pass at the end, and the count must not change: this sprint adds
no test and removes none, because it adds no behaviour.

### Nothing compiles on this workstation

Same as Sprint 06A: no Gradle, no `out/`, no toolchain. `arch-test.sh` runs locally; compilation
and JUnit run on the GCP build VM (`instance-20260731-135250`, zone `asia-southeast1-b`), driven
by `sync-to-vm.ps1` and `48-quickcheck.sh`. The VM is currently **TERMINATED** and must be started
before Task 10.

Never claim code compiles or tests pass. Run the command, read the output, quote it.

### The zero-diff gate, and why it ignores comments

Five runtime files must not change in behaviour: `AnimationStateMachine.kt`,
`AnimationRegistry.kt`, `AnimationDriver.kt`, `DefaultAnimationController.kt`,
`ExecutionTimeline.kt`. None of them mentions progress, a sampler or a strategy in *code* — they
deal in states and events, tick order, frames, start/stop, and elapsed nanoseconds.

Two of them mention `AnimationStrategy` in a **comment**:

- `AnimationStateMachine.kt:55` — `/** The strategy reported that the motion ended. */`
- `ExecutionTimeline.kt:23` — `...to an \`AnimationStrategy\`. That split is ADR-006...`

Those comments must be updated, or they point at a type that no longer exists. So the gate
compares **non-comment lines only**, the same way `arch-test.sh` already strips comments before
checking for forbidden calls. Task 0 builds it.

---

## File Structure

```
frameworks/base/aurora/
├── sdk/java/aurora/sdk/animation/
│   ├── MotionSample.kt              CREATE  value + velocity, nothing else
│   ├── MotionSampler.kt             CREATE  one method
│   ├── AnimationStrategy.kt         DELETE  replaced by the two above
│   ├── AnimationSpec.kt             MODIFY  gains isFinished(elapsed, sample)
│   ├── AnimationHandle.kt           MODIFY  elapsedNanos, velocity, normalizedPosition, seekToElapsed
│   └── AnimationListener.kt         MODIFY  onUpdate carries elapsedNanos, not progress
├── runtime/java/aurora/runtime/animation/
│   ├── TimedSampler.kt              CREATE  from TimedStrategy.kt
│   ├── TimedStrategy.kt             DELETE
│   ├── AnimationHandleImpl.kt       MODIFY  samplerFor, fresh sampler on restart, seekToElapsed
│   ├── AnimationStateMachine.kt     comment only  ← zero-diff
│   ├── ExecutionTimeline.kt         comment only  ← zero-diff
│   ├── AnimationRegistry.kt         untouched     ← zero-diff
│   ├── AnimationDriver.kt           untouched     ← zero-diff
│   └── DefaultAnimationController.kt untouched    ← zero-diff
├── tests/java/aurora/
│   ├── sdk/animation/AnimationApiTest.kt              MODIFY  ~25 sites
│   └── runtime/animation/
│       ├── AnimationLifecycleTest.kt                  MODIFY  ~71 sites
│       ├── AnimationDeterminismTest.kt                MODIFY  ~10 sites
│       ├── AnimationRegistryTest.kt                   MODIFY  ~8 sites
│       └── AnimationStateMachineTest.kt               untouched
├── tools/
│   ├── zero-diff-gate.sh            CREATE  Task 0
│   └── verify-sprint06a.sh          MODIFY  Task 10 renames it and adds the gate
└── README.md                        MODIFY  Task 9
```

---

## Task 0: The zero-diff gate

**Files:** Create `frameworks/base/aurora/tools/zero-diff-gate.sh`

It exists before any change so every later task can run it.

- [ ] **Step 1: Write the script**

```bash
#!/usr/bin/env bash
# Sprint 06A.5: prove the migration changed no runtime behaviour.
#
# Five files carry the engine's behaviour and none of them mentions progress, a sampler or a
# strategy in code. If any of them needs a real change to make the tests pass, the model is
# wrong somewhere and the sprint stops.
#
# Comments are excluded. Two of these files name AnimationStrategy in prose, and that prose has
# to be corrected or it points at a type that no longer exists. arch-test.sh already strips
# comments before checking forbidden calls; this uses the same idea.
#
#   bash frameworks/base/aurora/tools/zero-diff-gate.sh <base-ref>
set -uo pipefail

BASE="${1:-main}"
AURORA="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$AURORA/../../.." || exit 1

FILES=(
  frameworks/base/aurora/runtime/java/aurora/runtime/animation/AnimationStateMachine.kt
  frameworks/base/aurora/runtime/java/aurora/runtime/animation/AnimationRegistry.kt
  frameworks/base/aurora/runtime/java/aurora/runtime/animation/AnimationDriver.kt
  frameworks/base/aurora/runtime/java/aurora/runtime/animation/DefaultAnimationController.kt
  frameworks/base/aurora/runtime/java/aurora/runtime/animation/ExecutionTimeline.kt
)

# Reduce a file to the lines that carry behaviour, so prose may change and code may not.
#
# Two passes. The first drops comment-only lines and blanks. The second drops a trailing //
# comment from a line that also has code, because this sprint edits comments and a gate that
# flagged `foo() // old wording` -> `foo() // new wording` as a behaviour change would produce
# false failures - and a checker people learn to ignore is worse than none.
#
# The trailing strip skips any line containing a double quote, so a string literal holding //
# is never truncated. That leaves one blind spot: a comment-only edit to a line that ALSO holds a
# quoted string would be reported as a code change. No guarded line needs both today, and the
# check below proves it rather than asserting it. Refusing to guess is cheaper
# than a regex that tries to parse Kotlin.
# Reads stdin so both sides of the diff go through exactly one implementation. Duplicating it -
# a function for the working tree and an inline grep for the base - would let the two drift and
# report a difference that is only in the stripping.
stripStdin() {
  grep -vE '^[[:space:]]*(\*|//|/\*|$)' \
    | sed -E '/"/! s@[[:space:]]*//.*$@@' \
    | grep -vE '^[[:space:]]*$'
}

# The blind spot named above, checked instead of assumed. A guarded line holding both a quoted
# string and a trailing comment cannot have its comment edited without this gate calling it a
# code change. None exists today; if one ever does, say so loudly here rather than let a future
# task rediscover it as a mystery FAIL.
BLIND=0
for f in "${FILES[@]}"; do
  [ -f "$f" ] || continue
  HITS=$(grep -nE '"[^"]*".*//' "$f" 2>/dev/null | grep -vE '^[0-9]+:[[:space:]]*(\*|//|/\*)')
  if [ -n "$HITS" ]; then
    echo "  warn  $(basename "$f") has a line with both a string and a trailing comment;"
    echo "        editing that comment alone would read as a code change:"
    printf '%s\n' "$HITS" | sed 's/^/          /'
    BLIND=1
  fi
done
[ "$BLIND" -eq 0 ] && echo "  ok    no guarded line mixes a quoted string with a trailing comment"
echo

FAILURES=0
for f in "${FILES[@]}"; do
  if [ ! -f "$f" ]; then
    echo "  FAIL  $f no longer exists"
    FAILURES=$((FAILURES + 1))
    continue
  fi
  # Process substitution keeps both sides as files for diff. Both go through stripStdin.
  if diff -q <(git show "$BASE:$f" 2>/dev/null | stripStdin) \
            <(stripStdin < "$f") >/dev/null 2>&1; then
    echo "  ok    $(basename "$f") unchanged in code"
  else
    echo "  FAIL  $(basename "$f") changed in code, not just comments:"
    diff <(git show "$BASE:$f" 2>/dev/null | stripStdin) \
         <(stripStdin < "$f") | head -20 | sed 's/^/          /'
    FAILURES=$((FAILURES + 1))
  fi
done

echo
if [ "$FAILURES" -eq 0 ]; then
  echo "ZERO_DIFF_GATE PASS"
  exit 0
fi
echo "ZERO_DIFF_GATE FAIL ($FAILURES file(s))"
exit 1
```

- [ ] **Step 2: Check it parses and passes on an unchanged tree**

```
bash -n frameworks/base/aurora/tools/zero-diff-gate.sh
bash frameworks/base/aurora/tools/zero-diff-gate.sh main
```
Expected: `bash -n` silent; the gate prints five `ok` lines and `ZERO_DIFF_GATE PASS`.

- [ ] **Step 3: Prove it fails on a real change**

A gate that has never failed is not known to work.

```bash
F=frameworks/base/aurora/runtime/java/aurora/runtime/animation/AnimationRegistry.kt
printf '\nval deliberateViolation = 1\n' >> "$F"
bash frameworks/base/aurora/tools/zero-diff-gate.sh main
git checkout "$F"
bash frameworks/base/aurora/tools/zero-diff-gate.sh main
```
Expected: `ZERO_DIFF_GATE FAIL (1 file(s))` naming `AnimationRegistry.kt`, then `PASS` after the
revert. Quote both outputs in your report.

- [ ] **Step 4: Prove it tolerates a comment-only change**

```bash
F=frameworks/base/aurora/runtime/java/aurora/runtime/animation/AnimationRegistry.kt
printf '\n// a comment, which the gate must ignore\n' >> "$F"
bash frameworks/base/aurora/tools/zero-diff-gate.sh main
git checkout "$F"
```
Expected: `ZERO_DIFF_GATE PASS`. If it fails here the strip regex is wrong and must be fixed
before proceeding — every later task depends on this distinction.

- [ ] **Step 5: Commit**

```bash
git add frameworks/base/aurora/tools/zero-diff-gate.sh
git commit -m "Sprint 06A.5: the zero-diff gate, before anything it guards

Five runtime files carry the engine's behaviour and none of them mentions
progress, a sampler or a strategy in code. If any needs a real change to make
the tests pass, the model is wrong and the sprint stops.

Comments are excluded, because two of those files name AnimationStrategy in
prose that has to be corrected or it points at a type that will not exist.
arch-test.sh already strips comments before checking forbidden calls; this
borrows the idea.

Verified by breaking it with a real line, watching it fail, reverting, then
adding a comment and watching it still pass."
```

---

## Task 1: `MotionSample` and `MotionSampler`

**Files:**
- Create `frameworks/base/aurora/sdk/java/aurora/sdk/animation/MotionSample.kt`
- Create `frameworks/base/aurora/sdk/java/aurora/sdk/animation/MotionSampler.kt`
- Delete `frameworks/base/aurora/sdk/java/aurora/sdk/animation/AnimationStrategy.kt`

The two new types come together and the old one goes with them, because nothing can compile in
between.

- [ ] **Step 1: Create `MotionSample.kt`**

Apache header exactly as every other file in the tree has it, then:

```kotlin
package aurora.sdk.animation

/**
 * Where a motion is and how fast it is going, at one instant.
 *
 * ## A value, and it stays one
 *
 * Immutable, never cached, never reused, never pooled. One is created per sample and forgotten.
 *
 * The rule exists because of a specific temptation: anyone worried about allocating per
 * animation per frame will reach for an object pool, and pooling breaks exactly the property
 * that makes a sample useful. A pooled sample handed to a listener can be overwritten underneath
 * it later in the same frame, so the number the listener read is no longer the number it acts
 * on — a failure that is invisible in review and intermittent at runtime.
 *
 * It also keeps determinism easy to reason about: if a sample can never change after it is made,
 * "what was this animation doing at 96ms" has exactly one answer.
 *
 * ## No `finished`
 *
 * Whether a motion has ended is a policy, not a measurement, and it is made of the spec's own
 * numbers. See [AnimationSpec.isFinished].
 *
 * @param value normalised position. May leave 0..1 — an overshooting spring is supposed to — and
 *     may decrease, which is why it is not called progress.
 * @param velocity rate of change of [value] with respect to time, in normalised units per
 *     second. Each sampler supplies this by whatever method suits its model.
 */
data class MotionSample(
    val value: Float,
    val velocity: Float,
)
```

- [ ] **Step 2: Create `MotionSampler.kt`**

Apache header, then:

```kotlin
package aurora.sdk.animation

/**
 * Turns an elapsed time into a position and a velocity.
 *
 * ## One method, and nothing else
 *
 * No `advance`, no `reset`, no properties, no policy. A sampler answers one question and holds
 * no opinion about anything else — not about when the motion ends, not about which execution it
 * belongs to, not about what a frame is.
 *
 * ## Created per execution
 *
 * A sampler is built when an execution starts and discarded when it ends, so it never has to be
 * reset and never carries anything from a previous run. That makes a stepped sampler's internal
 * state — position, velocity, step count — entirely its own business, with nothing for the
 * engine to remember to clear.
 *
 * ## Elapsed, never delta
 *
 * A closed-form sampler evaluates a function of elapsed time. A stepped one derives its step
 * count from elapsed rather than accumulating frame deltas, which is what keeps 60Hz and 120Hz
 * bit-identical at the same instant. Neither needs a delta, and a parameter nobody uses is an
 * invitation to accumulate something — and accumulation drifts.
 */
interface MotionSampler {

    /**
     * Where the motion is at [elapsedNanos] since this execution began.
     *
     * Callers sample in non-decreasing order of elapsed time. A closed-form sampler ignores that
     * and could be called in any order; a stepped one integrates forward and cannot go back.
     *
     * This constrains the *caller*, not the implementation, and that is deliberate: it is what
     * makes a stepped sampler legal to write at all. Without it a caller would be entitled to
     * sample in any order, and a stepped sampler defending itself would be breaking the contract
     * rather than keeping it.
     */
    fun sampleAt(elapsedNanos: Long): MotionSample
}
```

- [ ] **Step 3: Delete the old interface**

```bash
git rm frameworks/base/aurora/sdk/java/aurora/sdk/animation/AnimationStrategy.kt
```

- [ ] **Step 4: Confirm nothing else declares the old name**

```bash
grep -rn "interface AnimationStrategy" frameworks/base/aurora/
```
Expected: no output. References from other files still exist and are fixed in later tasks; only
the declaration must be gone.

- [ ] **Step 5: Run the architecture gate**

Run: `bash frameworks/base/aurora/tools/arch-test.sh`
Expected: `ARCH TEST PASS`.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Sprint 06A.5: MotionSample and MotionSampler replace AnimationStrategy

One method, two numbers out, no policy. progress and easedProgress are gone
because a spring reports 1.18, then 0.95, then 1.03 - a position that
oscillates, not a progress that advances.

deltaNanos is gone because no sampler ever used it, and a parameter nobody
touches invites accumulation, which drifts. reset() is gone because a sampler
is now built per execution.

The non-decreasing-elapsed clause constrains the caller rather than the
implementation, which is what will make a stepped sampler legal to write in
06D without the contract changing."
```

---

## Task 2: `AnimationSpec.isFinished`

**Files:** Modify `frameworks/base/aurora/sdk/java/aurora/sdk/animation/AnimationSpec.kt`

- [ ] **Step 1: Add the method to the sealed interface**

Replace `sealed interface AnimationSpec` (currently an empty marker) with:

```kotlin
sealed interface AnimationSpec {

    /**
     * Whether a motion described by this spec has ended.
     *
     * The rule belongs here and not on the sampler for two reasons. It is made of this spec's
     * own numbers — a spring rests inside its `restDelta` and below its `restVelocity` — so a
     * sampler reporting it would be applying a rule it does not own, and every alternative
     * spring solver would have to re-implement it identically. And a timed animation cannot
     * derive it from a value at all: a timeline ends because time ran out, not because the value
     * arrived anywhere, which is why this takes [elapsedNanos] as well as [sample].
     *
     * Putting it here also keeps a `when` over spec kinds out of the engine. A spec added in a
     * later sprint brings its own rule with it and `AnimationHandleImpl` never learns it exists.
     */
    fun isFinished(elapsedNanos: Long, sample: MotionSample): Boolean
}
```

- [ ] **Step 2: Implement it on `TimedSpec`**

Inside `TimedSpec`, after `elapsedForProgress`:

```kotlin
    /** A timeline ends when it runs out. An infinite one never does. */
    override fun isFinished(elapsedNanos: Long, sample: MotionSample): Boolean =
        timeline.isFinishedAt(elapsedNanos)
```

- [ ] **Step 3: Implement it on the three physics specs**

`SpringSpec` and `SnapSpec` share a rule, so put it on `PhysicsSpec` as a default and let
`DecaySpec` override. Inside `sealed interface PhysicsSpec`, after `restDelta`:

```kotlin
    /**
     * At rest when it is close enough to its target and slow enough.
     *
     * The target is 1 because everything on a `PhysicsSpec` is normalised progress, not value
     * units — see ADR-002. `SpringSpec` and `SnapSpec` both use this; `DecaySpec` overrides it,
     * because a decay has no target to be near.
     */
    override fun isFinished(elapsedNanos: Long, sample: MotionSample): Boolean =
        kotlin.math.abs(1f - sample.value) < restDelta &&
            kotlin.math.abs(sample.velocity) < restVelocity
```

Inside `DecaySpec`, after its `init` block:

```kotlin
    /** A decay has nowhere to arrive. It ends when it stops moving. */
    override fun isFinished(elapsedNanos: Long, sample: MotionSample): Boolean =
        kotlin.math.abs(sample.velocity) < restVelocity
```

- [ ] **Step 4: Add tests to `AnimationApiTest`**

After `physicsThresholdsAreNormalisedSoTheDefaultsAreSaneAtAnyScale`:

```kotlin
    @Test
    fun aTimedSpecFinishesWhenItsTimelineRunsOut() {
        // And not because of the value: a timeline ends because time ran out, which is why the
        // sample it is handed here is deliberately nowhere near an end state.
        val spec = TimedSpec(Timeline.ofMillis(200))
        val midFlight = MotionSample(value = 0.5f, velocity = 5f)
        assertFalse(spec.isFinished(199 * ms, midFlight))
        assertTrue(spec.isFinished(200 * ms, midFlight))
    }

    @Test
    fun anInfiniteTimedSpecNeverFinishes() {
        val spec = TimedSpec(
            Timeline(durationNanos = 100 * ms, repeatCount = Timeline.REPEAT_INFINITE)
        )
        assertFalse(spec.isFinished(10_000 * ms, MotionSample(1f, 0f)))
    }

    @Test
    fun aSpringFinishesWhenItIsNearOneAndSlow() {
        // Near one, not near zero: everything on a PhysicsSpec is normalised progress, so the
        // target is always 1 whatever the animation's from and to happen to be.
        val spec = SpringSpec()
        assertTrue(spec.isFinished(0L, MotionSample(value = 1f, velocity = 0f)))
        assertFalse("still moving", spec.isFinished(0L, MotionSample(1f, velocity = 1f)))
        assertFalse("still far away", spec.isFinished(0L, MotionSample(value = 0.5f, velocity = 0f)))
        assertFalse("overshooting", spec.isFinished(0L, MotionSample(value = 1.2f, velocity = 0f)))
    }

    @Test
    fun aDecayFinishesOnVelocityAloneBecauseItHasNoTarget() {
        // The distinguishing case: a value nowhere near 1, which would keep a spring running,
        // finishes a decay as long as it has stopped moving.
        val spec = DecaySpec()
        assertTrue(spec.isFinished(0L, MotionSample(value = 0.3f, velocity = 0f)))
        assertFalse(spec.isFinished(0L, MotionSample(value = 0.3f, velocity = 1f)))
    }

    @Test
    fun aSnapUsesTheSameRestRuleAsASpring() {
        val spec = SnapSpec(targets = listOf(0f, 1f))
        assertTrue(spec.isFinished(0L, MotionSample(1f, 0f)))
        assertFalse(spec.isFinished(0L, MotionSample(0.5f, 0f)))
    }
```

- [ ] **Step 5: Run the architecture gate and the zero-diff gate**

```
bash frameworks/base/aurora/tools/arch-test.sh
bash frameworks/base/aurora/tools/zero-diff-gate.sh main
```
Expected: `ARCH TEST PASS` and `ZERO_DIFF_GATE PASS`.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Sprint 06A.5: the rest policy moves onto AnimationSpec

Whether a motion has ended is made of the spec's own numbers, so the spec
owns the rule. A sampler reporting it would apply a rule it does not own, and
every alternative spring solver would re-implement it identically.

It takes elapsed as well as the sample because a timed animation cannot
derive the answer from a value at all - a timeline ends because time ran out,
not because the value arrived somewhere.

SpringSpec and SnapSpec share the rest rule, so it is a default on PhysicsSpec
and DecaySpec overrides it: a decay has nowhere to arrive and ends when it
stops moving. The target is 1 because a PhysicsSpec is normalised progress
(ADR-002), whatever the animation's from and to are."
```

---

## Task 3: `TimedSampler`

**Files:**
- Create `frameworks/base/aurora/runtime/java/aurora/runtime/animation/TimedSampler.kt`
- Delete `frameworks/base/aurora/runtime/java/aurora/runtime/animation/TimedStrategy.kt`

- [ ] **Step 1: Create `TimedSampler.kt`**

Apache header, then:

```kotlin
package aurora.runtime.animation

import aurora.sdk.animation.MotionSample
import aurora.sdk.animation.MotionSampler
import aurora.sdk.animation.TimedSpec

/**
 * The only sampler Sprint 06A.5 ships: time decides position.
 *
 * Holds nothing. [aurora.sdk.time.Timeline] is already stateless and the interpolator is a pure
 * function, so a sample is derived from elapsed time on every call and never accumulated. Two
 * calls that reach the same elapsed time by different routes agree exactly, which is what stops
 * a dropped frame from drifting.
 *
 * ## Velocity
 *
 * A central finite difference around the sampled instant. It is a pure function of elapsed, so
 * determinism is unaffected, and unlike an analytic derivative it works for any [Interpolator] —
 * including an arbitrary Bézier that has no closed form to differentiate.
 *
 * The contract says only that velocity is the rate of change of value with respect to time. This
 * is one way to produce it, not the required way.
 */
class TimedSampler(private val spec: TimedSpec) : MotionSampler {

    override fun sampleAt(elapsedNanos: Long): MotionSample {
        val value = valueAt(elapsedNanos)
        // Central difference, clamped at zero so the first sample looks forward rather than
        // before the execution began.
        val before = valueAt(if (elapsedNanos < EPSILON_NANOS) 0L else elapsedNanos - EPSILON_NANOS)
        val after = valueAt(elapsedNanos + EPSILON_NANOS)
        val spanSeconds =
            (if (elapsedNanos < EPSILON_NANOS) elapsedNanos + EPSILON_NANOS else 2 * EPSILON_NANOS)
                .toDouble() / NANOS_PER_SECOND
        val velocity = if (spanSeconds == 0.0) 0f else ((after - before) / spanSeconds).toFloat()
        return MotionSample(value = value, velocity = velocity)
    }

    private fun valueAt(elapsedNanos: Long): Float =
        spec.interpolator.transform(spec.timeline.progressAt(elapsedNanos))

    private companion object {
        /** Half a millisecond: far below a frame, far above float noise at animation scale. */
        const val EPSILON_NANOS = 500_000L
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
```

- [ ] **Step 2: Delete the old class**

```bash
git rm frameworks/base/aurora/runtime/java/aurora/runtime/animation/TimedStrategy.kt
```

- [ ] **Step 3: Migrate the `TimedStrategy` tests in `AnimationLifecycleTest`**

Every test in the `// --- TimedStrategy ---` section changes shape, not expectation. Rename the
section heading to `// --- TimedSampler ---` and translate each test. The pattern is:

```kotlin
// before
val s = TimedStrategy(TimedSpec(Timeline.ofMillis(200)))
s.advance(100 * ms, 16 * ms)
assertEquals(0.5f, s.progress, 1e-6f)

// after
val s = TimedSampler(TimedSpec(Timeline.ofMillis(200)))
assertEquals(0.5f, s.sampleAt(100 * ms).value, 1e-6f)
```

Two tests need more than a rename because they exercised removed members, and both are replaced
rather than deleted so the coverage survives:

`resetClearsProgressAndTheFinishedFlag` becomes:

```kotlin
    @Test
    fun aFreshSamplerStartsAtTheCurveAtZero() {
        // reset() is gone: a sampler is built per execution, so "fresh" is the only state a new
        // one can be in. This is what that test was really asserting.
        val offset = Interpolator { p -> 0.25f + 0.5f * p }
        val s = TimedSampler(TimedSpec(Timeline.ofMillis(200), offset))
        assertEquals(0.25f, s.sampleAt(0L).value, 1e-6f)
    }
```

`seekToOnATimedStrategyDoesNotThrow` is deleted outright — `seekTo` no longer exists on the
contract, and seeking is now `ExecutionTimeline`'s business, already covered by its own tests.

Then add one test for the new capability:

```kotlin
    @Test
    fun velocityIsTheRateOfChangeOfValue() {
        // A linear 200ms animation covers the unit interval in 0.2s, so it moves at 5 per second
        // throughout.
        //
        // The tolerance is loose on purpose. The contract says velocity is the rate of change of
        // value; central finite difference is how this sprint produces it, and the epsilon it
        // uses is an implementation detail. A test tight enough to notice the epsilon changing
        // would be testing the method rather than the quantity, and would fail a sampler that
        // was still perfectly correct.
        val s = TimedSampler(TimedSpec(Timeline.ofMillis(200)))
        assertEquals(5f, s.sampleAt(100 * ms).velocity, 0.25f)
    }

    @Test
    fun velocityIsNearZeroWhereTheCurveIsFlat() {
        // Past the end of the timeline the value holds, so nothing is moving. Same reasoning
        // about tolerance: the claim is "not moving", not a particular numerical zero.
        val s = TimedSampler(TimedSpec(Timeline.ofMillis(200)))
        assertEquals(0f, s.sampleAt(400 * ms).velocity, 0.25f)
    }

    @Test
    fun velocityIsPositiveWhileAdvancingAndTracksTheCurve() {
        // The property, stated without depending on any particular number: an ease-out is fast
        // early and slow late, so its velocity must decrease across the animation. This holds
        // for any correct derivative by any method.
        val easeOut = Interpolator { p -> 1f - (1f - p) * (1f - p) }
        val s = TimedSampler(TimedSpec(Timeline.ofMillis(200), easeOut))
        val early = s.sampleAt(20 * ms).velocity
        val late = s.sampleAt(180 * ms).velocity
        assertTrue("an ease-out starts fast, got $early", early > 0f)
        assertTrue("and slows down, got $early then $late", late < early)
    }
```

- [ ] **Step 4: Run both gates**

```
bash frameworks/base/aurora/tools/arch-test.sh
bash frameworks/base/aurora/tools/zero-diff-gate.sh main
```
Expected: both PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Sprint 06A.5: TimedSampler replaces TimedStrategy

Same arithmetic, new shape: sampleAt(elapsed) returns a MotionSample instead
of advance() writing into properties. Timeline is already stateless and the
interpolator is pure, so the sampler holds nothing at all.

Velocity is a central finite difference. The contract says only that velocity
is the rate of change of value; this is one way to produce it, chosen because
it works for any Interpolator including an arbitrary Bezier with no closed
form to differentiate, and because it stays a pure function of elapsed.

resetClearsProgressAndTheFinishedFlag becomes aFreshSamplerStartsAtTheCurveAtZero,
which is what it was really asserting now that a sampler is built per
execution. seekToOnATimedStrategyDoesNotThrow is deleted: seekTo is off the
contract and seeking is ExecutionTimeline's business, already covered there."
```

---

## Task 4: `AnimationHandle`

**Files:** Modify `frameworks/base/aurora/sdk/java/aurora/sdk/animation/AnimationHandle.kt`

- [ ] **Step 1: Replace the property block**

Replace `val progress: Float` and `val value: Float` with:

```kotlin
    /**
     * Time since this execution began.
     *
     * The engine's canonical quantity. Every calculation inside the engine — in the handle, in a
     * sampler, in `ExecutionTimeline` — is expressed in elapsed nanoseconds.
     */
    val elapsedNanos: Long

    /** The animated value: `animation.valueAt(sample.value)`. */
    val value: Float

    /** Rate of change of [value], in value units per second. */
    val velocity: Float

    /**
     * Whether [normalizedPosition] means anything for this animation.
     *
     * True for a timed animation, false for one whose position oscillates.
     */
    val hasNormalizedPosition: Boolean

    /**
     * How far through the animation is, 0..1, or `NaN` when [hasNormalizedPosition] is false.
     *
     * **A convenience for callers. Nothing inside the engine may read it.** A scrollbar, a slider
     * or a scrubber wants a number to draw with; without this, such a caller would compute
     * `elapsedNanos / duration`, which is right for a `TimedSpec` and wrong for a `PhysicsSpec`,
     * so the caller would have to know which it holds and the abstraction would have leaked.
     *
     * `NaN` rather than an exception, because queries never throw — see the rules above. And
     * `NaN` rather than zero, because it propagates visibly instead of impersonating a real
     * position.
     */
    val normalizedPosition: Float
```

- [ ] **Step 2: Replace `seek`**

```kotlin
    /**
     * Moves the current execution to [nanos] since it began.
     *
     * Legal from [AnimationState.SCHEDULED], [AnimationState.RUNNING] and
     * [AnimationState.PAUSED] only: seeking positions a live execution, and a finished one has no
     * position to move.
     *
     * In elapsed time rather than in progress, because progress has no inverse for an animation
     * that overshoots — a spring reaches 0.9 at three different times, so `seek(0.9f)` has no
     * single answer. Elapsed always does. A caller thinking in fractions converts with
     * `TimedSpec.elapsedForProgress`.
     */
    fun seekToElapsed(nanos: Long)
```

- [ ] **Step 3: Update rule 1 in the class KDoc**

The rule currently lists `progress`. Replace that sentence with:

```
 * 1. **Queries never throw.** [state], [isRunning], [elapsedNanos], [value], [velocity],
 *    [normalizedPosition], [hasNormalizedPosition], [executionId] and [animation] are readable
 *    in every state including [AnimationState.DISPOSED]. They read volatile fields, take no lock
 *    and trigger no lazy computation.
```

And in rule 3, replace `[seek]` with `[seekToElapsed]`.

- [ ] **Step 4: Run both gates**

Expected: `ARCH TEST PASS`, `ZERO_DIFF_GATE PASS`.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Sprint 06A.5: elapsed becomes the handle's canonical quantity

progress leaves AnimationHandle and normalizedPosition replaces it, guarded
by hasNormalizedPosition. A boolean and not a Float? because a nullable
primitive boxes per frame and reads badly from Java; NaN and not an exception
because queries never throw, and a teardown path reading a value to log it
must not become the thing that crashes teardown.

normalizedPosition is documented as a caller convenience that nothing inside
the engine may read, because the failure mode is easy to reach: it looks like
the natural way to say how far through, and it is meaningless for every
physics animation.

seek(progress) becomes seekToElapsed(nanos), which deletes rather than works
around a problem with no solution - a spring reaches progress 0.9 at three
different times."
```

---

## Task 5: `AnimationListener`

**Files:** Modify `frameworks/base/aurora/sdk/java/aurora/sdk/animation/AnimationListener.kt`

- [ ] **Step 1: Change `onUpdate`**

```kotlin
    /**
     * The animation advanced.
     *
     * @param elapsedNanos time since this execution began
     * @param value the animated value: `handle.animation.valueAt(sample.value)`
     */
    fun onUpdate(
        handle: AnimationHandle,
        executionId: Long,
        elapsedNanos: Long,
        value: Float,
    ) {
    }
```

- [ ] **Step 2: Run both gates**

Expected: both PASS.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "Sprint 06A.5: onUpdate carries elapsed, not progress

Same reason the handle changed: progress is not a quantity every animation
has. A listener that wants a fraction asks the handle for
normalizedPosition and checks hasNormalizedPosition first."
```

---

## Task 6: `AnimationHandleImpl`

**Files:** Modify `frameworks/base/aurora/runtime/java/aurora/runtime/animation/AnimationHandleImpl.kt`

The one runtime file that must change. Everything else on the zero-diff list must not.

- [ ] **Step 1: Swap the sampler field and factory**

Replace the `strategy` field and the `strategyFor` companion function:

```kotlin
    private var sampler: MotionSampler = samplerFor(animation.spec)
```

```kotlin
        fun samplerFor(spec: AnimationSpec): MotionSampler = when (spec) {
            is TimedSpec -> TimedSampler(spec)
            is PhysicsSpec -> throw UnsupportedOperationException(
                "physics animations arrive in Sprint 06B; ${spec.javaClass.simpleName} has no " +
                    "sampler yet"
            )
        }
```

Note `var`, not `val`: `restart()` replaces it.

- [ ] **Step 2: Replace the published state**

```kotlin
    @Volatile
    override var elapsedNanos: Long = 0L
        private set

    @Volatile
    override var value: Float = animation.valueAt(samplerFor(animation.spec).sampleAt(0L).value)
        private set

    @Volatile
    override var velocity: Float = 0f
        private set

    @Volatile
    override var normalizedPosition: Float = Float.NaN
        private set

    override val hasNormalizedPosition: Boolean
        get() = animation.spec is TimedSpec
```

- [ ] **Step 3: Rewrite `publishUpdate`**

```kotlin
    private fun publishUpdate(sample: MotionSample, elapsed: Long) {
        // Everything a listener is handed is captured before the loop, never read from the
        // fields inside it. A listener may restart or seek this very handle from its callback,
        // which rewrites these fields and the executionId - and a later listener in the same
        // dispatch would then be handed one execution's id beside another execution's numbers.
        val v = animation.valueAt(sample.value)
        val range = animation.to - animation.from
        elapsedNanos = elapsed
        value = v
        velocity = sample.velocity * range
        normalizedPosition =
            if (hasNormalizedPosition) sample.value else Float.NaN

        val snapshot = listeners
        val id = executionId
        var i = 0
        while (i < snapshot.size) {
            snapshot[i].onUpdate(this, id, elapsed, v)
            i++
        }
    }
```

- [ ] **Step 4: Rewrite `tick`**

```kotlin
    override fun tick(frameTime: FrameTime) {
        if (!state.isActive) return
        if (frameTime.frameIndex <= scheduledOnFrame) return

        if (state == AnimationState.SCHEDULED) dispatch(AnimationEvent.TICK)

        val elapsed = execution.advanceTo(frameTime.frameTimeNanos)
        val sample = sampler.sampleAt(elapsed)
        publishUpdate(sample, elapsed)

        if (animation.spec.isFinished(elapsed, sample)) dispatch(AnimationEvent.FINISH)
    }
```

- [ ] **Step 5: Rewrite `seek` as `seekToElapsed`**

```kotlin
    override fun seekToElapsed(nanos: Long) {
        check(state == AnimationState.SCHEDULED ||
              state == AnimationState.RUNNING ||
              state == AnimationState.PAUSED) {
            "seekToElapsed is not legal in state $state: seeking positions a live execution, and " +
                "a finished one has no position to move. Use restart() first."
        }
        require(nanos >= 0) { "cannot seek before the execution began: $nanos" }

        execution.seekTo(nanos)
        publishUpdate(sampler.sampleAt(nanos), nanos)
    }
```

It no longer asks what kind of spec it holds. That check existed only to reject seeking a
physics animation by progress, and there is no progress to seek by any more.

- [ ] **Step 6: Rewrite the RESTART branch of `dispatch`**

```kotlin
            AnimationEvent.RESTART -> {
                executionId++
                // A fresh sampler rather than a reset one. A stepped sampler's internal state is
                // its own business, and there is nothing for the engine to remember to clear.
                sampler = samplerFor(animation.spec)
                execution.reset()
                pausedFrom = AnimationState.RUNNING
                elapsedNanos = 0L
                val fresh = sampler.sampleAt(0L)
                value = animation.valueAt(fresh.value)
                velocity = 0f
                normalizedPosition = if (hasNormalizedPosition) fresh.value else Float.NaN
                enterRegistry()
            }
```

- [ ] **Step 7: Run both gates**

Expected: `ARCH TEST PASS` and `ZERO_DIFF_GATE PASS`. The zero-diff gate is the important one
here: if it fails, this task changed a file it must not have.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "Sprint 06A.5: AnimationHandleImpl on the sampler contract

The one runtime file that changes, because it is the one place Animation,
Execution and Sampler meet.

samplerFor replaces strategyFor. restart() builds a fresh sampler instead of
resetting one, so a stepped sampler's state in 06D is entirely its own
business. seek() becomes seekToElapsed() and no longer asks what kind of spec
it holds - that check existed only to reject seeking physics by progress, and
there is no progress to seek by any more.

isFinished is asked of the spec rather than read off the sampler, so the
engine has no when over spec kinds and a spec added later brings its own rule.

publishUpdate takes the sample and the elapsed as parameters and captures the
value it publishes, keeping the fix 06A found the hard way: a listener that
restarts the handle must not leave a later listener holding one execution's
id beside another execution's numbers."
```

---

## Task 7: Migrate the tests

**Files:**
- Modify `frameworks/base/aurora/tests/java/aurora/sdk/animation/AnimationApiTest.kt`
- Modify `frameworks/base/aurora/tests/java/aurora/runtime/animation/AnimationLifecycleTest.kt`
- Modify `frameworks/base/aurora/tests/java/aurora/runtime/animation/AnimationDeterminismTest.kt`
- Modify `frameworks/base/aurora/tests/java/aurora/runtime/animation/AnimationRegistryTest.kt`

The largest task by line count and the simplest by judgement. **Translate names and shapes.
Never translate expected values.**

- [ ] **Step 1: Apply the mechanical translations**

| Before | After |
|---|---|
| `handle.progress` | `handle.normalizedPosition` |
| `handle.seek(p)` | `handle.seekToElapsed(spec.elapsedForProgress(p))` |
| `strategy.progress` | `sampler.sampleAt(e).value` |
| `strategy.easedProgress` | `sampler.sampleAt(e).value` |
| `strategy.advance(e, d)` | `sampler.sampleAt(e)` |
| `TimedStrategy(` | `TimedSampler(` |
| `AnimationStrategy` | `MotionSampler` |
| `onUpdate(h, id, progress, value)` | `onUpdate(h, id, elapsedNanos, value)` |

`AnimationApiTest`'s `HalfWayStrategy` becomes:

```kotlin
    /** A sampler with no physics, proving the interface is implementable as declared. */
    private class HalfWaySampler : MotionSampler {
        override fun sampleAt(elapsedNanos: Long) = MotionSample(value = 0.5f, velocity = 0f)
    }
```

and the three tests that exercised `progress`/`easedProgress`/`reset`/`seekTo` on it collapse
into one, because the interface now has one method:

```kotlin
    @Test
    fun aSamplerAnswersOneQuestion() {
        val s = HalfWaySampler()
        assertEquals(0.5f, s.sampleAt(10L).value, 0f)
        assertEquals(0f, s.sampleAt(10L).velocity, 0f)
        assertEquals("a sampler holds nothing, so the same elapsed always answers the same",
            s.sampleAt(10L), s.sampleAt(10L))
    }
```

- [ ] **Step 2: Fix the three tests that need real thought**

`AnimationLifecycleTest.seekMovesTheExecutionAndPublishesImmediately`:

```kotlin
    @Test
    fun seekingMovesTheExecutionAndPublishesImmediately() {
        val r = registry()
        val spec = TimedSpec(Timeline.ofMillis(100))
        val h = DefaultAnimator(r).create(Animation("test", spec))
        h.play()
        r.tick(frame(0))
        h.seekToElapsed(spec.elapsedForProgress(0.25f))
        assertEquals(0.25f, h.normalizedPosition, 1e-6f)
        assertEquals(0.25f, h.value, 1e-6f)
    }
```

`AnimationLifecycleTest.seekIsIllegalOnceTheExecutionHasEnded` — same body, `seekToElapsed(0L)`
in place of `seek(0.5f)`.

`AnimationLifecycleTest.seekRejectsProgressOutsideTheUnitRange` becomes:

```kotlin
    @Test
    fun seekingBeforeTheExecutionBeganIsRejected() {
        // The unit-range check went with seek(progress). Elapsed has one bound, not two.
        val r = registry()
        val h = handle(r)
        h.play()
        try {
            h.seekToElapsed(-1L)
            fail("an execution cannot be positioned before it began")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }
```

`AnimationDeterminismTest.animationsStartedOnDifferentFramesShareOneTimestamp` gets simpler —
compare `elapsedNanos` directly, which is exact and needs no recorded history:

```kotlin
        repeat(10) {
            val k = 3 + it
            c.tick(frameAt(k.toLong(), (48 + it * 16) * ms, 16 * ms))
            // Coherence is a claim about time. All three were handed the same frameTimeNanos, so
            // each gained the same elapsed, and the ones that started later are behind by exactly
            // the frames they missed. Compared as elapsed, this is exact - no float subtraction.
            assertEquals(second.elapsedNanos + 16 * ms, first.elapsedNanos)
            assertEquals(third.elapsedNanos + 32 * ms, first.elapsedNanos)
        }
```

- [ ] **Step 3: Confirm the test count did not change**

```bash
grep -c "@Test" frameworks/base/aurora/tests/java/aurora/sdk/animation/AnimationApiTest.kt \
  frameworks/base/aurora/tests/java/aurora/runtime/animation/*.kt
```
Expected total: **148** before, plus the 5 added in Task 2 and the **3** added in Task 3, minus
the 3 collapsed in Step 1 and the 1 deleted in Task 3 = **152**.

Task 3 also inherits a gap this plan left: several tests asserted `strategy.isFinished`, a
property that moved to `AnimationSpec.isFinished` in Task 2, and the mechanical table below does
not mention it. The translation is `spec.isFinished(elapsed, sampler.sampleAt(elapsed))`, holding
the spec in a local — **not** `timeline.isFinishedAt(elapsed)`, which would skip the very method
Task 2 added and test the wrong layer.

That translation leaves `aFreshSamplerStartsAtTheCurveAtZero` and
`aFreshStrategyReportsTheCurveAtZeroRatherThanZero` asserting nearly the same thing, because the
old API split across three properties what the new one says with one. Merge them into the first
and drop the second, which takes the total to **151**. State the real observed count either way. State the real numbers in
your report and reconcile them; if they do not add up, say so rather than adjusting.

- [ ] **Step 4: Run both gates**

Expected: both PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Sprint 06A.5: migrate the tests, names and shapes only

No expected value changed. Every assertion that moved from progress to
normalizedPosition is the same number under a new name, and every sampler
call that moved from advance-then-read to sampleAt returns the same
arithmetic.

Three tests needed real thought rather than translation. The two seek tests
now express their position in elapsed, and the unit-range rejection becomes a
negative-elapsed rejection, because elapsed has one bound and progress had
two. The coherence test compares elapsedNanos directly, which is exact and
removes the float-subtraction cancellation that had to be fixed twice in 06A.

HalfWayStrategy's three tests collapse into one, because the interface it
implements now has one method."
```

---

## Task 8: The licensed exception, written down

**Files:** none — this is a check, and it either passes or the sprint stops.

- [ ] **Step 1: Prove no expected value changed**

```bash
git diff main -- frameworks/base/aurora/tests/ | grep -E "^[-+].*assertEquals" | sort | uniq -c | sort -rn | head -40
```

Read the output. Every `-` line must have a matching `+` line with the same numeric literals and
a different field name. List any pair where the number differs, with the test name.

- [ ] **Step 2: Report**

The expected answer is that the only numeric differences are in the three tests Task 7 Step 2
rewrote, each for a reason stated there. If any other test's number moved, **stop and report it**
— the migration has changed behaviour and the sprint's central claim is false.

- [ ] **Step 3: Commit nothing**

This task produces no commit. Its output goes in the report.

---

## Task 9: Amend the documents

**Files:**
- Modify `docs/adr/ADR-002-sealed-animation-spec.md`
- Modify `docs/adr/ADR-006-strategy-owns-progress.md`
- Modify `frameworks/base/aurora/README.md`
- Modify the two zero-diff files' comments

- [ ] **Step 1: Amend ADR-002**

Add to its Consequences:

```markdown
- **Superseded in part by Sprint 06A.5.** This ADR justified physics rejecting `seekTo` by saying
  a spring's position is the result of integrating from its previous state. For a closed-form
  spring that is false — its position is a function of elapsed time. The real obstacle was that
  progress is not injective for an overshooting spring: 0.9 occurs at three different times, so
  `seek(0.9f)` has no single answer. Sprint 06A.5 removes the question by seeking in elapsed
  time, which always has one. The decision this ADR records — a sealed spec with a timed and a
  physics branch — is unaffected.
```

- [ ] **Step 2: Amend ADR-006**

Add at the top, under the status line:

```markdown
> **Amended by Sprint 06A.5.** `AnimationStrategy` is now `MotionSampler` and it returns a
> `MotionSample` rather than exposing `progress` and `easedProgress`. The split this ADR
> describes — `ExecutionTimeline` owns elapsed time, the sampler owns motion — is unchanged and
> is what 06A.5 makes clearer. Read `progress` below as `MotionSample.value`.
```

- [ ] **Step 3: Update the two zero-diff files' comments**

`AnimationStateMachine.kt:55`: `/** The sampler reported that the motion ended. */` becomes
`/** The spec's completion rule reported that the motion ended. */` — which is now accurate,
since `isFinished` lives on the spec.

`ExecutionTimeline.kt:23`: replace `AnimationStrategy` with `MotionSampler`.

Then run `bash frameworks/base/aurora/tools/zero-diff-gate.sh main` and confirm it still passes —
this is the comment-only case the gate was built to tolerate, and Task 0 Step 4 already proved it
does.

- [ ] **Step 4: Update the README**

In the animation tier table, replace `TimedStrategy` with `TimedSampler` and add `MotionSample`,
`MotionSampler` to the `aurora.sdk.animation` row. In RULE-009's paragraph, replace
"`AnimationStrategy`" with "`MotionSampler`". Update the 06B line to name the two sub-sprints
from the spec's roadmap.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Sprint 06A.5: amend ADR-002 and ADR-006, update the README

ADR-002 justified physics rejecting seekTo by saying a spring integrates from
its previous state. For a closed-form spring that is false. The real obstacle
was that progress has no inverse for an overshooting spring, and 06A.5
removes the question by seeking in elapsed. The sealed-spec decision itself
is unaffected.

ADR-006's split - ExecutionTimeline owns elapsed, the sampler owns motion -
is unchanged and is exactly what this sprint makes clearer, so it gets an
amendment note rather than a rewrite.

The two comments in the zero-diff files that named AnimationStrategy are
corrected. This is the comment-only case the gate was built to tolerate."
```

---

## Task 10: VM verification

- [ ] **Step 1: Start the VM**

```powershell
$g = "$env:LOCALAPPDATA\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
& $g compute instances start instance-20260731-135250 --zone asia-southeast1-b
```
Expected: `RUNNING`.

- [ ] **Step 2: Rename and extend the verify script**

Copy `frameworks/base/aurora/tools/verify-sprint06a.sh` to `verify-sprint06a5.sh`, change the log
paths from `s06a-` to `s06a5-`, change the final marker to `SPRINT06A5_ALL_GREEN` /
`SPRINT06A5_PROBLEM`, and add the zero-diff gate as a sixth gate before the summary:

```bash
echo
echo "=== 6. No runtime behaviour change ==="
bash $AURORA/tools/zero-diff-gate.sh main
RC_ZERODIFF=$?
echo "zero-diff exit=$RC_ZERODIFF"
```

Add `RC_ZERODIFF` to the final `if`.

Then add a seventh gate, counting the one place physics is still refused:

```bash
echo
echo "=== 7. Exactly one physics refusal, and it is the factory ==="
REFUSALS=$(grep -rn "UnsupportedOperationException(" \
  $AURORA/runtime/java/aurora/runtime/animation/ 2>/dev/null)
echo "$REFUSALS" | sed 's/^/    /'
COUNT=$(printf '%s\n' "$REFUSALS" | grep -c . )
RC_REFUSAL=0
if [ "$COUNT" -ne 1 ]; then
  echo "    expected exactly 1 (samplerFor's physics branch), found $COUNT"
  RC_REFUSAL=1
elif ! printf '%s' "$REFUSALS" | grep -q "AnimationHandleImpl.kt"; then
  echo "    the one refusal is not in AnimationHandleImpl.samplerFor"
  RC_REFUSAL=1
fi
echo "refusal exit=$RC_REFUSAL"
```

Add `RC_REFUSAL` to the final `if` as well.

This gate exists because the refusal is a promissory note. `samplerFor` throws for every
`PhysicsSpec` with a message naming Sprint 06B, and 06B's job is to make it disappear. Counting
it means a second one cannot quietly appear somewhere else in the runtime, and it gives 06B a
gate that goes green exactly when it is done: the count must reach zero, not one.

State in your report that 06B inherits this gate with the expected count changed from 1 to 0.

- [ ] **Step 3: Sync and run**

```powershell
.\sync-to-vm.ps1
& $g compute ssh instance-20260731-135250 --zone asia-southeast1-b --quiet --command "bash /mnt/build/lineage/frameworks/base/aurora/tools/verify-sprint06a5.sh"
```

Expected final line: `SPRINT06A5_ALL_GREEN`, with `OK (271 tests)` — 267 plus the net new tests
from Tasks 2, 3 and 7. Reconcile the count against Task 7 Step 3 and state both numbers.

If a test fails, read it against the one rule: did a *name* need changing, or a *number*? A
number means stop.

- [ ] **Step 4: Stop the VM**

```powershell
& $g compute instances stop instance-20260731-135250 --zone asia-southeast1-b --quiet
```

- [ ] **Step 5: Report and ask how to finish the branch**

Do not merge unprompted. The project's convention is one squashed commit per sprint on its own
branch, with `main` fast-forwarded onto it.

---

## Self-review

**Spec coverage.** Every section of the frozen spec maps to a task: `MotionSample` and
`MotionSampler` → 1; `AnimationSpec.isFinished` → 2; `TimedSampler` and the velocity note → 3;
`AnimationHandle` → 4; `AnimationListener` → 5; the runtime changes → 6; the migration rule → 7
and 8; the ADR amendments → 9; every exit criterion → 10, with the zero-diff gate built in 0 and
run in every task from 2 onward.

**Placeholder scan.** No `TBD`, no "similar to Task N", no step that describes without showing.
The one task with no code is Task 8, which is a check by construction.

**Type consistency.** `MotionSample(value, velocity)`, `MotionSampler.sampleAt(elapsedNanos)`,
`AnimationSpec.isFinished(elapsedNanos, sample)`, `samplerFor(spec)`, `seekToElapsed(nanos)`,
`hasNormalizedPosition` / `normalizedPosition` — spelled identically in Tasks 1, 2, 3, 4, 6 and 7.

**One thing this plan asserts and cannot prove until Task 10:** that the test count arithmetic in
Task 7 Step 3 comes out at 151 animation tests and 271 overall. The numbers are derived, not
observed. Task 7 Step 3 and Task 10 Step 3 both require them to be reconciled against reality and
reported, rather than assumed.
