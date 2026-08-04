# Sprint 06B.0 — Physics semantics: implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: use superpowers:subagent-driven-development.
> Steps use checkbox (`- [ ]`) syntax.

**Goal:** Deliver a runnable Physics Contract — the domain boundary, the completion
decomposition and decay's derived target — with a property harness proven able to fail, before
any solver exists.

**Spec:** `docs/specs/2026-08-03-sprint-06b0-physics-semantics-design.md` (frozen except §4.6,
which is recorded as open analysis and blocks nothing here).

**Architecture:** No solver. One SDK type changes shape (`PhysicsSpec`); everything else is test
infrastructure and documentation. `Animation`, `MotionSample`, `MotionSampler`, the state machine,
the registry and the driver are untouched.

**Tech stack:** Kotlin, `sdk_version: "core_current"`, JUnit 4 in the single
`aurora-platform-tests` Soong module.

---

## Task ordering is by risk, not by file

Each task leaves the repository compiling, with every test passing and no half-stated contract.

One deliberate departure from the obvious order: **the sampler-tier harness is built before
`PhysicsSpec` is reshaped**, not after. Reshaping first would mean writing three
`completionMetric` implementations with nothing able to judge them — the same position 06A was in
when it shipped a normalisation argument no code could check. The sampler-tier properties need no
API change at all, so they can be built, proven red against broken fixtures and proven green
against `TimedSampler` first. The reshape then lands into a harness that can already judge part
of it.

The physics-tier properties cannot come first — they call `completionMetric`, which does not exist
until Task 2. That is why the harness is split across Tasks 1 and 4 rather than built in one go.

| task | risk retired |
|---|---|
| 1 | Do the sampler properties have discriminating power, or are they tautologies? |
| 2 | Does the reshaped API compile and leave the existing suite green? |
| 3 | Is a derived target implementable without touching `Animation`? |
| 4 | Can the physics properties fail, and do they fail for the stated reason? |
| 5 | Is every clause of the contract mapped to a named assertion, or only to prose? |

The dependency is epistemic rather than a compile order - each task builds the means of knowing
the next one is right:

```
Task 1  Sampler contract        knows nothing of PhysicsSpec
   |
Task 2  PhysicsSpec reshape     lands in a world that can already judge a sampler
   |
Task 3  Decay's derived target
   |
Task 4  Physics contract        the properties that judge a metric
```

The principle underneath it is the one Sprint 06A had to learn from being wrong three times:
**do not write an abstraction before an independent way of showing it correct exists.** Building
the API first invites tests written afterwards to ratify whatever the API happened to do.

### Two rules this sprint adds

> **RULE-015.** A contract property must have a fixture that violates it. A property never shown
> to go red has not been shown to check anything, and reads in a green run exactly like one that
> everything satisfies.

> **RULE-016.** A property must not reproduce the implementation it verifies. Checking a central
> difference with a central difference at the same step compares a computation against itself and
> passes for every input.

They are two halves of one idea - a verifier must be **independent** of what it checks (016) and
must be able to **discriminate** (015) - and the zero-diff gate belongs to the same family.

### What Task 2 may and may not do

Task 2 changes shape, not behaviour, and the line is drawn where it can be checked: **it adds no
new test.** Every test it touches is a rename forced by the API, and no expected value changes -
the 06A.5 rule, *translate names and shapes, never translate expected values*. If Task 2 finds
itself wanting a new test to justify a formula, that formula belongs to a later task.

The stronger form of that constraint - *Task 2 writes no physics* - cannot hold literally, and it
is better to say so now than to have it break mid-task. `SpringSpec.completionMetric` must carry
the envelope formula the moment the interface declares it, and there is no behaviour-preserving
intermediate to park there first: the rule being replaced is a conjunction of two thresholds,
which has no single-scalar equivalent. That is precisely why it is being replaced.

What can be said, and was checked by hand rather than assumed, is that the four `isFinished`
assertions `AnimationApiTest` already makes about `SpringSpec` all survive the change unaltered.
With `SPRING_GENTLE` at `omega = sqrt(400) = 20` and a threshold of 0.001:

| sample | `x = 1 - value` | `v/omega` | metric | old verdict | new verdict |
|---|---|---|---|---|---|
| `(1.0, 0)` | 0 | 0 | 0 | finished | finished |
| `(1.0, 1)` | 0 | 0.05 | 0.05 | still moving | still moving |
| `(0.5, 0)` | 0.5 | 0 | 0.5 | still far away | still far away |
| `(1.2, 0)` | -0.2 | 0 | 0.2 | overshooting | overshooting |

So at the level of observable behaviour, for everything currently under test, Task 2 really is a
reshape. Where it is not - the flip at a turning point that section 3.1 of the spec describes - no
test exercised it, which is how the defect survived 06A in the first place.

---

## File structure

**Modified**
- `frameworks/base/aurora/sdk/java/aurora/sdk/animation/AnimationSpec.kt` — `PhysicsSpec` reshape
- `frameworks/base/aurora/tests/java/aurora/sdk/animation/AnimationApiTest.kt` — threshold rename

**Created — test infrastructure** (`frameworks/base/aurora/tests/java/aurora/testing/animation/`)
- `SamplerContract.kt` — properties every `MotionSampler` must satisfy
- `PhysicsContract.kt` — properties every `PhysicsSpec` and its sampler must satisfy
- `BrokenSamplers.kt` — deliberately wrong samplers, one per property
- `ContractSelfTest.kt` — proves each property goes red against its fixture
- `TimedSamplerContractTest.kt` — proves the sampler tier goes green against real code

**Created — documentation**
- `docs/contracts/motion-sampler-contract.md`
- `docs/adr/ADR-008-physics-contract-domain.md`
- `docs/adr/ADR-002-sealed-animation-spec.md` — amended, not created

**Created — tooling**
- `frameworks/base/aurora/tools/verify-motion-evidence.sh`

No file is added under `runtime/` or `platform/`. The sprint ships no solver, and `arch-test.sh`
plus the gate in Task 5 both enforce it.

---

## Task 1: The sampler-tier harness, and proof that it discriminates

**Files:**
- Create: `frameworks/base/aurora/tests/java/aurora/testing/animation/SamplerContract.kt`
- Create: `frameworks/base/aurora/tests/java/aurora/testing/animation/BrokenSamplers.kt`
- Create: `frameworks/base/aurora/tests/java/aurora/testing/animation/ContractSelfTest.kt`
- Create: `frameworks/base/aurora/tests/java/aurora/testing/animation/TimedSamplerContractTest.kt`

Nothing in production changes. This task exists to answer one question before anything depends on
the answer: do these assertions have the power to reject a wrong implementation?

- [ ] **Step 1: Write the contract assertions**

Create `SamplerContract.kt`:

```kotlin
package aurora.testing.animation

import aurora.sdk.animation.MotionSample
import aurora.sdk.animation.MotionSampler
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import kotlin.math.abs

/**
 * Properties every [MotionSampler] must satisfy, whatever it models.
 *
 * These are requirements, not consequences of the interface: an implementation can fail any of
 * them while still compiling and still looking plausible on screen. `ContractSelfTest` proves
 * each one rejects a sampler that breaks it — an assertion never shown to go red has not been
 * shown to check anything.
 *
 * Every assertion reports which sampler it ran against, so a sampler nobody checked cannot be
 * invisible in a green run.
 */
object SamplerContract {

    /** Sample points spread across the first two seconds, dense enough to catch local defects. */
    private val PROBES_NANOS: List<Long> =
        (0..200).map { it * 10_000_000L }

    /**
     * No sample is NaN or infinite, at any probe.
     *
     * The cheapest property and the one most worth having: a NaN reaches the screen as a view
     * that silently stops drawing, with no exception anywhere to point at the cause.
     */
    fun assertFinite(name: String, sampler: MotionSampler) {
        for (t in PROBES_NANOS) {
            val s = sampler.sampleAt(t)
            if (!s.value.isFinite() || !s.velocity.isFinite()) {
                fail("$name produced a non-finite sample at ${t}ns: $s")
            }
        }
    }

    /**
     * The same elapsed time yields the same sample, however many times it is asked and in
     * whatever order.
     *
     * Probed out of order on purpose. A sampler that secretly advances internal state on each
     * call passes a forward-only scan and fails this.
     */
    fun assertDeterministic(name: String, sampler: MotionSampler) {
        val forward = PROBES_NANOS.map { it to sampler.sampleAt(it) }
        for ((t, expected) in forward.reversed()) {
            val actual = sampler.sampleAt(t)
            assertEquals("$name value drifted at ${t}ns", expected.value, actual.value, 0f)
            assertEquals("$name velocity drifted at ${t}ns", expected.velocity, actual.velocity, 0f)
        }
    }

    /**
     * Reported velocity is the derivative of reported value.
     *
     * ## Why this does not reuse the sampler's own step
     *
     * `TimedSampler` computes velocity as a central difference of its value with
     * `EPSILON_NANOS = 500_000L`. Checking it with the same method at the same step would compare
     * a computation against itself and pass for every input — a tautology wearing a test's name,
     * and exactly the failure this sprint exists to catch.
     *
     * So the step here is deliberately different (5ms against the sampler's 0.5ms). A sampler
     * whose velocity is genuinely the derivative agrees at both scales. One that returns
     * something else does not.
     *
     * The tolerance is relative, because velocity spans orders of magnitude across a motion, and
     * a fixed epsilon would be either useless early or unmeetable late.
     */
    fun assertVelocityMatchesDerivative(name: String, sampler: MotionSampler) {
        val h = 5_000_000L // 5ms, ten times the sampler's own epsilon
        for (t in PROBES_NANOS) {
            if (t < h) continue
            val before = sampler.sampleAt(t - h).value
            val after = sampler.sampleAt(t + h).value
            val numeric = (after - before) / (2f * h / 1_000_000_000f)
            val reported = sampler.sampleAt(t).velocity
            val scale = maxOf(abs(numeric), abs(reported), 1f)
            if (abs(numeric - reported) / scale > 0.05f) {
                fail(
                    "$name reported velocity $reported at ${t}ns but its value changes at " +
                        "$numeric per second"
                )
            }
        }
    }
}
```

- [ ] **Step 2: Write the samplers that break them**

Create `BrokenSamplers.kt`:

```kotlin
package aurora.testing.animation

import aurora.sdk.animation.MotionSample
import aurora.sdk.animation.MotionSampler

/**
 * Samplers that are wrong on purpose, one per contract property.
 *
 * They exist so every property can be shown to go red for its stated reason. A suite that only
 * ever runs against correct implementations cannot tell a property that holds from a property
 * that checks nothing — both are green.
 *
 * None of these is a solver. They live in tests, never in `runtime/`, and the verify script
 * fails if one appears outside this directory.
 */

/** Correct, and the baseline the broken ones are deviations from: constant velocity. */
class LinearSampler(private val perSecond: Float = 1f) : MotionSampler {
    override fun sampleAt(elapsedNanos: Long): MotionSample =
        MotionSample(perSecond * elapsedNanos / 1_000_000_000f, perSecond)
}

/** Breaks `assertFinite`: fine until it converges, then returns NaN. */
class NaNAfterConvergenceSampler : MotionSampler {
    override fun sampleAt(elapsedNanos: Long): MotionSample =
        if (elapsedNanos > 1_000_000_000L) MotionSample(Float.NaN, Float.NaN)
        else MotionSample(elapsedNanos / 1_000_000_000f, 1f)
}

/**
 * Breaks `assertDeterministic`: advances a counter on every call.
 *
 * Named for the shape of the mistake rather than for a clock, because this is what accidentally
 * stateful sampling looks like — no `System.nanoTime` in sight, just a field that moves.
 */
class CallCountingSampler : MotionSampler {
    private var calls = 0
    override fun sampleAt(elapsedNanos: Long): MotionSample {
        calls++
        return MotionSample(elapsedNanos / 1_000_000_000f + calls * 1e-4f, 1f)
    }
}

/** Breaks `assertVelocityMatchesDerivative`: plausible value, velocity off by a constant factor. */
class WrongDerivativeSampler : MotionSampler {
    override fun sampleAt(elapsedNanos: Long): MotionSample =
        MotionSample(elapsedNanos / 1_000_000_000f, 2f)
}
```

- [ ] **Step 3: Write the self-test — every property must go red**

Create `ContractSelfTest.kt`:

```kotlin
package aurora.testing.animation

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The harness checking itself.
 *
 * Each test asserts that a property **fails** against the sampler built to break it, and that the
 * correct baseline passes. Without this, a property that silently checks nothing looks exactly
 * like a property that everything satisfies.
 */
class ContractSelfTest {

    private fun assertRejects(what: String, body: () -> Unit) {
        try {
            body()
        } catch (expected: AssertionError) {
            return
        }
        assertTrue("$what should have been rejected by the contract but passed", false)
    }

    @Test
    fun theBaselineSamplerSatisfiesEveryProperty() {
        val s = LinearSampler()
        SamplerContract.assertFinite("linear", s)
        SamplerContract.assertDeterministic("linear", s)
        SamplerContract.assertVelocityMatchesDerivative("linear", s)
    }

    @Test
    fun aNaNSamplerIsRejected() =
        assertRejects("NaNAfterConvergenceSampler") {
            SamplerContract.assertFinite("nan", NaNAfterConvergenceSampler())
        }

    @Test
    fun aStatefulSamplerIsRejected() =
        assertRejects("CallCountingSampler") {
            SamplerContract.assertDeterministic("counting", CallCountingSampler())
        }

    @Test
    fun aWrongVelocityIsRejected() =
        assertRejects("WrongDerivativeSampler") {
            SamplerContract.assertVelocityMatchesDerivative("wrong", WrongDerivativeSampler())
        }
}
```

- [ ] **Step 4: Run it. Expect green — every property both accepts and rejects**

```bash
# On the VM: tools/verify-motion-evidence.sh does not exist yet, so run the module directly
atest aurora-platform-tests:aurora.testing.animation.ContractSelfTest
```

Expected: 4 passing. If `aWrongVelocityIsRejected` fails, the tolerance or the step in
`assertVelocityMatchesDerivative` is too loose to notice a factor of two — fix the assertion, not
the fixture.

- [ ] **Step 5: Point the harness at real code**

Create `TimedSamplerContractTest.kt`:

```kotlin
package aurora.testing.animation

import aurora.runtime.animation.TimedSampler
import aurora.sdk.animation.Interpolator
import aurora.sdk.animation.TimedSpec
import aurora.sdk.time.Timeline
import org.junit.Test

/**
 * `TimedSampler` is the only `MotionSampler` that exists in Sprint 06B.0, and it is the harness's
 * only real subject. The physics tier has none until 06B.1, which is why the broken fixtures
 * carry that half.
 */
class TimedSamplerContractTest {

    private fun sampler(interpolator: Interpolator) = TimedSampler(
        TimedSpec(
            timeline = Timeline(durationNanos = 1_000_000_000L),
            interpolator = interpolator,
        )
    )

    @Test
    fun aLinearTimedSamplerSatisfiesTheSamplerContract() {
        val s = sampler(Interpolator.LINEAR)
        SamplerContract.assertFinite("TimedSampler/linear", s)
        SamplerContract.assertDeterministic("TimedSampler/linear", s)
        SamplerContract.assertVelocityMatchesDerivative("TimedSampler/linear", s)
    }
}
```

- [ ] **Step 6: Run it, and read the result carefully**

```bash
atest aurora-platform-tests:aurora.testing.animation.TimedSamplerContractTest
```

Expected: PASS. If `assertVelocityMatchesDerivative` fails at the timeline's boundaries, that is
information, not noise — a central difference straddling the end of a timeline compares a moving
value against a stopped one. Restrict the probe range to the interior and say so in a comment;
do **not** widen the tolerance until it passes.

- [ ] **Step 7: Commit**

```bash
git add frameworks/base/aurora/tests/java/aurora/testing/
git commit -m "Sprint 06B.0: sampler contract, proven able to reject"
```

---

## Task 2: Reshape `PhysicsSpec`

**Files:**
- Modify: `frameworks/base/aurora/sdk/java/aurora/sdk/animation/AnimationSpec.kt`
- Modify: `frameworks/base/aurora/tests/java/aurora/sdk/animation/AnimationApiTest.kt`

- [ ] **Step 1: Replace the `PhysicsSpec` interface body**

Replace the three threshold properties and the `isFinished` override with:

```kotlin
sealed interface PhysicsSpec : AnimationSpec {

    /**
     * Progress per second at the moment the animation starts.
     *
     * Normalised: 1.0 means the motion was crossing its whole range every second. Divide a
     * measured gesture velocity by the distance the animation spans to get this.
     */
    val initialVelocity: Float

    /**
     * How small [completionMetric] must get before the motion counts as over.
     *
     * In progress units, and comparable across families: 0.001 means the residual motion is
     * under 0.1% of the animation's travel, whether that motion is a spring settling or a fling
     * coasting. The two numbers it replaces were not comparable — nobody could say what
     * `restVelocity = 0.01` implied without first being told the friction.
     */
    val completionThreshold: Float

    /**
     * How far this motion still is from rest, in progress units.
     *
     * **Must never increase while the sampler evolves.** The contract states observable
     * behaviour, not a formula: an implementation may compute this from energy, from a decay
     * envelope or from anything else, provided a later sample never reports more than an earlier
     * one. `PhysicsContract.assertMetricNeverIncreases` checks exactly that.
     *
     * The rule this replaces read the instantaneous displacement and velocity separately, and
     * an underdamped spring has zero velocity at every turning point. Once its envelope fell
     * below the old `restDelta` the rule reported finished at a turning point and not-finished a
     * moment later — so the instant a spring settled depended on which frame happened to land
     * near one, and the same spring finished at different times at 60Hz and at 120Hz. See ADR-008.
     */
    fun completionMetric(sample: MotionSample): Float

    /** A comparison, and nothing else. No spec overrides this. */
    override fun isFinished(elapsedNanos: Long, sample: MotionSample): Boolean =
        completionMetric(sample) < completionThreshold
}
```

- [ ] **Step 2: Give `SpringSpec` the envelope metric**

```kotlin
data class SpringSpec(
    val spring: Spring = MotionTokens.SPRING_GENTLE,
    override val initialVelocity: Float = 0f,
    override val completionThreshold: Float = 0.001f,
) : PhysicsSpec {

    init {
        require(completionThreshold > 0f) {
            "completionThreshold must be positive; $completionThreshold would never be reached"
        }
    }

    /**
     * The amplitude this oscillation would settle at if damping stopped now.
     *
     * With `x` the displacement still to cover and `v` the current speed, `√(x² + (v/ω)²)` is
     * the conserved amplitude of the equivalent undamped oscillator, and damping only removes
     * energy: for `E = ½(v² + ω²x²)`, `dE/dt = -2ζωv² ≤ 0` always. So it never increases.
     *
     * `v/ω` has units of `(progress/second) / (1/second)`, so the whole expression is in
     * progress and shares a threshold with every other family.
     */
    override fun completionMetric(sample: MotionSample): Float {
        val omega = kotlin.math.sqrt(spring.stiffness)
        val x = 1f - sample.value
        val scaledVelocity = sample.velocity / omega
        return kotlin.math.sqrt(x * x + scaledVelocity * scaledVelocity)
    }
}
```

- [ ] **Step 3: Give `SnapSpec` the same metric and `DecaySpec` its own**

`SnapSpec` takes the identical body — after a target is chosen it is a spring, and §8 of the spec
defers the question of whether it needs a solver of its own at all.

`DecaySpec` loses its `isFinished` override entirely and gains:

```kotlin
    /**
     * The fraction of its travel a decay has still to cover.
     *
     * Normalised against its own total travel, a decay's position is `1 - e^(-f·t)`, so this is
     * `e^(-f·t)` — monotone by inspection, with no oscillation to account for. The override that
     * used to read velocity alone is gone: a decay has a target after all, derived rather than
     * supplied.
     */
    override fun completionMetric(sample: MotionSample): Float =
        kotlin.math.abs(1f - sample.value)
```

- [ ] **Step 4: Update `AnimationApiTest` - renames only, no new test**

Rename `restDelta = x, restVelocity = y` to `completionThreshold = x` at every construction site.
Per the 06A.5 migration rule - *translate names and shapes, never translate expected values* - no
assertion's expected number changes in this step. If one seems to need to, stop: the quantity has
changed type and that is a finding, not a rename.

Three tests need more than a rename, and each is a deletion rather than a rewrite:

- the loop asserting `restVelocity > 0f` and `restDelta > 0f` across all three specs becomes one
  assertion on `completionThreshold`
- the constructor-rejection table loses its `restVelocity` and `restDelta` rows and keeps one
  `completionThreshold = 0f` row per spec
- the test reading `spec.restDelta < 0.01f` and `spec.restVelocity < 0.1f` becomes a single bound
  on `completionThreshold`, and its comment - which explains the threshold in terms of a
  full-screen slide - carries over unchanged, because the unit did not change

The four `isFinished` assertions about `SpringSpec` are **not** touched. They pass under the new
metric; the table near the top of this plan shows the arithmetic. If any of them goes red, the
envelope implementation is wrong - do not adjust the test.

- [ ] **Step 5: Compile and run the full suite on the VM**

```bash
./tools/sync-to-vm.ps1        # from the workstation
# on the VM:
m aurora-sdk aurora-runtime && atest aurora-platform-tests
```

Expected: green. The engine never reads these fields — `AnimationHandleImpl` asks
`animation.spec.isFinished(...)` and nothing else — so a red here means a test asserted the old
shape rather than the behaviour.

- [ ] **Step 6: Commit**

```bash
git commit -am "Sprint 06B.0: completion is a metric, a threshold and a comparison"
```

---

## Task 3: Decay's derived target

**Files:**
- Modify: `frameworks/base/aurora/sdk/java/aurora/sdk/animation/AnimationSpec.kt`

- [ ] **Step 1: Add `restingDisplacement` and the zero-velocity precondition**

```kotlin
data class DecaySpec(
    val friction: Float = 4.6f,
    override val initialVelocity: Float,
    override val completionThreshold: Float = 0.001f,
) : PhysicsSpec {

    init {
        require(friction > 0f) { "friction must be positive; $friction would never settle" }
        require(initialVelocity != 0f) {
            "a decay released at rest travels nowhere; there is nothing to animate"
        }
        require(completionThreshold > 0f) {
            "completionThreshold must be positive; $completionThreshold would never be reached"
        }
    }

    /**
     * How far a decay released at [velocity] will travel before stopping, in the same units as
     * [velocity].
     *
     * Under exponential friction the total travel is `v₀/f`, in closed form at t = 0 — which is
     * why a decay has a target after all: `to = from + restingDisplacement(v₀)`. ADR-002 called
     * that circular on the assumption that finding the resting position means simulating to it.
     * It does not.
     *
     * This lives here so the formula has one implementation. A caller computing `v₀/friction`
     * itself and a sampler computing `1 - e^(-ft)` would be two copies of one model, and if they
     * drifted the animation would still run, still look smooth, and stop in the wrong place with
     * nothing to report it. `PhysicsContract.assertConvergesToOne` is what turns "the two agree"
     * into a check.
     */
    fun restingDisplacement(velocity: Float): Float = velocity / friction
```

`initialVelocity` loses its default: there is no sensible zero, and a required argument is a
better place to learn that than a `require` at runtime.

- [ ] **Step 2: Justify the friction default in its KDoc**

```kotlin
    /**
     * How quickly the motion sheds speed, in inverse seconds.
     *
     * The default is derived rather than chosen: a decay is over when `e^(-f·t)` drops below
     * `completionThreshold`, so `t = -ln(0.001)/f`, and `f = 4.6` puts a fling's settle time at
     * about 1.5 seconds. The previous default of 0.5 worked out to **13.8 seconds**, which is the
     * first thing measured about it — it had been documented as plausible rather than measured,
     * and it was not plausible.
     */
```

- [ ] **Step 3: Add a test for the arithmetic that connects the two halves**

In `AnimationApiTest`:

```kotlin
    @Test
    fun aDecayNormalisedVelocityIsItsFriction() {
        // to = from + v0/f, so v0/(to - from) = f. The caller's conversion and the sampler's
        // shape have to agree at t = 0 or the animation stops somewhere other than `to`.
        val spec = DecaySpec(friction = 4.6f, initialVelocity = 800f)
        val travel = spec.restingDisplacement(800f)
        assertEquals(4.6f, 800f / travel, 1e-4f)
    }

    @Test
    fun aDecayReleasedAtRestIsRejected() {
        try {
            DecaySpec(initialVelocity = 0f)
            fail("a decay with no initial velocity should not construct")
        } catch (expected: IllegalArgumentException) {
        }
    }
```

- [ ] **Step 4: Run, then commit**

```bash
atest aurora-platform-tests:aurora.sdk.animation.AnimationApiTest
git commit -am "Sprint 06B.0: a decay's target is derived, not absent"
```

---

## Task 4: The physics-tier harness

**Files:**
- Create: `frameworks/base/aurora/tests/java/aurora/testing/animation/PhysicsContract.kt`
- Modify: `frameworks/base/aurora/tests/java/aurora/testing/animation/BrokenSamplers.kt`
- Modify: `frameworks/base/aurora/tests/java/aurora/testing/animation/ContractSelfTest.kt`

- [ ] **Step 1: Write the two physics properties**

```kotlin
package aurora.testing.animation

import aurora.sdk.animation.MotionSampler
import aurora.sdk.animation.PhysicsSpec
import org.junit.Assert.fail
import kotlin.math.abs

/**
 * Properties every [PhysicsSpec] and its sampler must satisfy together.
 *
 * These are the **contract tier**: a failure on every sampler means the semantics are wrong, not
 * the solver. A property that only one family can satisfy belongs beside that family instead —
 * and when 06B.2 shows a decay cannot satisfy something a spring can, moving it is the expected
 * outcome, not a defect in 06B.0.
 */
object PhysicsContract {

    private val PROBES_NANOS: List<Long> = (0..300).map { it * 10_000_000L }

    /**
     * The completion metric never increases while the animation is still running.
     *
     * Bounded to the running region on purpose. Asserting it everywhere would require the
     * inequality to hold where the metric has fallen to 1e-9, and there float32 rounds in both
     * directions and the assertion would flake — the same arithmetic that made `0.048f - 0.032f`
     * come out as `0.015999999` in Sprint 06A. Above the threshold, one ULP is around 1e-10 and
     * no tolerance constant is needed.
     */
    fun assertMetricNeverIncreases(name: String, spec: PhysicsSpec, sampler: MotionSampler) {
        var previous = Float.MAX_VALUE
        for (t in PROBES_NANOS) {
            val metric = spec.completionMetric(sampler.sampleAt(t))
            if (metric < spec.completionThreshold) return // finished; the sampler stops evolving
            if (metric > previous) {
                fail("$name metric rose from $previous to $metric at ${t}ns")
            }
            previous = metric
        }
    }

    /**
     * The motion reaches its target.
     *
     * This is the check that keeps a decay's two halves honest. The travel `v₀/f` is computed by
     * whoever builds the `Animation`, while the shape `1 - e^(-ft)` is computed by the sampler;
     * if those disagree the animation still runs and still looks smooth, and only this notices.
     */
    fun assertConvergesToOne(name: String, spec: PhysicsSpec, sampler: MotionSampler) {
        val settled = sampler.sampleAt(PROBES_NANOS.last()).value
        if (abs(1f - settled) > spec.completionThreshold * 10f) {
            fail("$name settled at $settled rather than 1")
        }
    }
}
```

- [ ] **Step 2: Add the two fixtures that break them**

```kotlin
/** Breaks `assertMetricNeverIncreases`: converges, then drifts back out. */
class IncreasingEnvelopeSampler : MotionSampler {
    override fun sampleAt(elapsedNanos: Long): MotionSample {
        val t = elapsedNanos / 1_000_000_000f
        return MotionSample(if (t < 1f) t else 2f - t, if (t < 1f) 1f else -1f)
    }
}

/** Breaks `assertConvergesToOne`: stops short and stays there. */
class NonConvergingSampler : MotionSampler {
    override fun sampleAt(elapsedNanos: Long): MotionSample =
        MotionSample(0.5f * (1f - kotlin.math.exp(-elapsedNanos / 1e9f)), 0f)
}
```

- [ ] **Step 3: Extend the self-test**

Add two tests asserting each fixture is rejected, paired with an **analytic reference
trajectory** — `MotionSample(1f - exp(-f·t), f·exp(-f·t))` — that must pass both.

The naming matters more than it looks. Calling it a *correct decay implementation* would read as
the harness proving one implementation against another, which is exactly what §6 says it must
never do: the harness follows the contract, never a solver. It is a closed-form solution written
directly into the test to give the properties something that satisfies them — three lines, no
dispatch, no spec, and 06B.1 does not replace it with anything.

- [ ] **Step 4: Run, expect four more passing tests, commit**

---

## Task 5: The contract document, the ADRs, and the gate

**Files:**
- Create: `docs/contracts/motion-sampler-contract.md`
- Create: `docs/adr/ADR-008-physics-contract-domain.md`
- Modify: `docs/adr/ADR-002-sealed-animation-spec.md`
- Create: `frameworks/base/aurora/tools/verify-motion-evidence.sh`
- Modify: `frameworks/base/aurora/README.md` — add RULE-015

- [ ] **Step 1: Write the contract document**

Normative prose, spanning sprints. It states, with no formula for any specific family:

1. **Domain** — the contract binds every solver whose entire dynamical state is
   `(value, velocity)`. A system carrying hidden state falls outside it, and the extension it
   needs is a wider `MotionSample`, not another threshold.
2. **`value`** — normalised displacement from `from` toward the resting point, 0 → 1, one meaning
   for every family.
3. **`velocity`** — the derivative of `value`, in progress per second.
4. **Completion** — a scalar in progress units that never increases while the sampler evolves.
5. **Determinism** — the same elapsed time yields the same sample, always.

Each clause names the assertion that enforces it. Any clause with no assertion says so in the
same sentence.

- [ ] **Step 2: Write ADR-008 and amend ADR-002**

ADR-008 covers the domain boundary and the completion decomposition, with the turning-point
argument and the token table as evidence.

ADR-002 gains a paragraph retracting the circularity claim: it rested on the unstated assumption
that finding a resting position requires simulating to it, which is false for exponential
friction. Its **decision** — a sealed spec with a timed and a physics branch — is unaffected, and
the amendment says so, the same way the 06A.5 amendment did.

- [ ] **Step 3: Add RULE-015 and RULE-016 to the README**

Both are stated at the top of this plan. RULE-015 gets gate 4 of the verify script; RULE-016
cannot be checked mechanically - no script can ask whether two computations are the same idea - so
the README says plainly that it is enforced by review, the way it already distinguishes
machine-checked rules from the rest.

- [ ] **Step 4: Write the verify script**

Gates, each failing loudly on an empty match rather than passing vacuously — the defect that made
06A's first verify script green against an empty directory:

1. `arch-test.sh` passes
2. No file under `runtime/` or `platform/` mentions `SpringSampler`, `DecaySampler` or
   `SnapSampler` — the sprint ships no solver
3. `restDelta` and `restVelocity` appear nowhere in the tree
4. Every `assert*` function in `SamplerContract` and `PhysicsContract` is **paired with a fixture
   that violates it** — RULE-015, checked rather than trusted
5. `UnsupportedOperationException(` still appears exactly once, in `AnimationHandleImpl.kt`
6. The full test module passes

Gate 4 is the one worth writing carefully, and merely grepping for the assertion's name is too
weak: `assertMetricNeverIncreases` appearing in the baseline test would satisfy it while no
fixture violates the property at all. That is a gate that passes for the wrong reason, which this
project has already shipped once.

So the pairing is **declared**, at the top of `ContractSelfTest.kt`, and the gate checks the
declaration against reality from both ends:

```kotlin
// RULE-015 pairing. Every contract property and the fixture that violates it.
// verify-motion-evidence.sh checks each assertion here exists in the harness and each fixture
// exists in BrokenSamplers.kt, so neither column can name something that is not there.
//
//   assertFinite                  <- NaNAfterConvergenceSampler
//   assertDeterministic           <- CallCountingSampler
//   assertVelocityMatchesDerivative <- WrongDerivativeSampler
//   assertMetricNeverIncreases    <- IncreasingEnvelopeSampler
//   assertConvergesToOne          <- NonConvergingSampler
```

The gate then makes three checks, all with `grep`, no AST needed:

1. every `fun assert*` in the two harness files appears in the left column — a sixth property
   cannot be added without declaring its fixture
2. every name in the left column is a real function in the harness
3. every name in the right column is a real class in `BrokenSamplers.kt`

What this still cannot check is whether the self-test actually *uses* each pair — a declaration
can drift from the test below it. That link is enforced by review, and the gate says so in its
output rather than implying a coverage it does not have. Claiming otherwise would be the same
false confidence RULE-016 was written to avoid.

- [ ] **Step 5: Run the whole gate on the VM, then commit**

```bash
bash frameworks/base/aurora/tools/verify-motion-evidence.sh
```

---

## Exit criteria

- [ ] `PhysicsSpec` exposes `completionMetric` and one `completionThreshold`; `isFinished` is
      derived and overridden nowhere
- [ ] `restDelta` and `restVelocity` appear nowhere in the tree
- [ ] Every contract property has a fixture proving it can fail, and the verify script checks
      that pairing rather than assuming it
- [ ] `TimedSampler` passes the sampler tier, against a step different from its own
- [ ] No file added under `runtime/` or `platform/`
- [ ] `docs/contracts/motion-sampler-contract.md` exists, and every clause names its assertion or
      states that it has none
- [ ] ADR-002's circularity claim is retracted with its reason; ADR-008 records the domain
      boundary
- [ ] The full suite passes on the VM
