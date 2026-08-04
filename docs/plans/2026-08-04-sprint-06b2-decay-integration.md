# Sprint 06B.2 — Decay integration: implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development.

**Spec:** `docs/specs/2026-08-04-sprint-06b2-decay-integration-design.md` (frozen).

**No design decisions here.** Every formula, name and boundary is the spec's. If one appears
while implementing — a tolerance, a friction value, a change to the assertion so it can tell the
witness apart — **stop and amend the spec**. 06B.1 needed that four times; 06B.2 has absorbed
those lessons, so needing it again is itself information.

---

## Task order

| task | stops when |
|---|---|
| 1 | the integration assertion exists, and its witness is red there while green on both existing layers |
| 2 | `AnimationService.fling` turns that witness green |
| 3 | `DecaySampler` ships; `samplerFor` refuses only `SnapSpec` |
| 4 | §7, gate 5, verify, squash, push |

Task 1 builds no production code at all. RULE-018: the integration layer has never existed, so
its assertion and its subject would otherwise arrive together and a pass would carry nothing.

---

## Task 1: open the integration layer

**Files:** `tests/java/aurora/testing/animation/IntegrationContract.kt`,
`WrongFlings.kt`, `DecayIntegrationTest.kt` — all new, all tests.

- [ ] **Step 1: The assertion**

```kotlin
package aurora.testing.animation

/**
 * Properties of a whole pipeline, including its caller and the point where units change.
 *
 * The **integration layer**, opened in Sprint 06B.2 and empty before it. Both other layers take a
 * `MotionSampler` and work in normalised progress, so neither can see across a unit boundary —
 * which is why a claim that `assertConvergesToOne` guarded this was retracted before this layer
 * was built.
 */
object IntegrationContract {

    /**
     * The travel a caller inferred, scaled by friction, returns exactly the velocity it was given.
     *
     * ```
     * (to - from) · friction = v₀
     * ```
     *
     * A product rather than a quotient: no division, so `to == from` needs no special case, and a
     * caller that forgets to divide lands off by exactly a factor of `friction` — a signature
     * rather than a discrepancy.
     *
     * ## What this does and does not prove
     *
     * If `fling` derives `to` through `DecaySpec.restingDisplacement`, this is an identity for
     * every friction. It therefore proves that the caller **routed through the single
     * implementation** of the friction model, not that the model is right. That
     * `v₀/friction` is correct was established in Sprint 06B.0 by
     * `aDecaysNormalisedInitialVelocityIsAlwaysItsFriction`, and is not re-proved here.
     */
    fun assertTravelPreservesTheGestureVelocity(
        name: String,
        animation: Animation,
        spec: DecaySpec,
        gestureVelocity: Float,
    ) {
        val returned = (animation.to - animation.from) * spec.friction
        val scale = maxOf(abs(gestureVelocity), abs(returned), 1f)
        if (abs(returned - gestureVelocity) / scale > TOLERANCE) {
            fail(
                "$name inferred a travel of ${animation.to - animation.from}, which at friction " +
                    "${spec.friction} returns $returned rather than the $gestureVelocity it was given"
            )
        }
    }

    /** Relative, and loose only enough to absorb float32 rounding on two multiplications. */
    private const val TOLERANCE = 1e-4f
}
```

- [ ] **Step 2: The witness**

```kotlin
/**
 * A `fling` that forgets to divide by friction — the first witness of the integration layer.
 *
 * Declared red set: **integration only**. The sampler is untouched and correct, and normalised
 * progress still reaches 1 for any friction, so both existing layers must accept it. That
 * signature is the whole reason the layer exists.
 *
 * ## It must not be run at friction 1
 *
 * The discrepancy is `v₀(f - 1)`, which is exactly zero at `f = 1`: there the correct pipeline and
 * this one produce **identical observations** and the witness proves nothing. Sprint 06B.1 met the
 * same failure in a different coordinate, where a witness sat inside the oracle's resolution blind
 * spot; this one would sit inside a parameter blind spot. `DecayIntegrationTest` runs it at the
 * default 4.6 and states the exclusion.
 */
fun flingForgettingFriction(from: Float, gestureVelocity: Float, spec: DecaySpec): Animation =
    Animation("fling/forgot-friction", spec, from = from, to = from + gestureVelocity)
```

- [ ] **Step 3: Assert the complete red set**

The sampler side is played by `DecayTrajectory`, the analytic subject from 06B.0.
`DecaySampler` does not exist until Task 3 and is not needed: this witness's defect is entirely
on the caller's side of the boundary, so the sampler is a constant in the experiment.

```kotlin
    @Test
    fun aFlingThatForgetsFrictionIsCaughtByTheIntegrationLayerAlone() {
        val spec = DecaySpec(friction = 4.6f, initialVelocity = 4.6f)
        val trajectory = DecayTrajectory(friction = 4.6f)

        // Must stay green: nothing below the boundary changed.
        SamplerContract.assertFinite(NAME, trajectory)
        SamplerContract.assertVelocityMatchesDerivative(NAME, trajectory)
        PhysicsContract.assertMetricNeverIncreases(NAME, spec, trajectory)
        PhysicsContract.assertConvergesToOne(NAME, spec, trajectory)

        // Must go red.
        assertRejects(NAME) {
            IntegrationContract.assertTravelPreservesTheGestureVelocity(
                NAME, flingForgettingFriction(0f, 800f, spec), spec, 800f
            )
        }
    }

    @Test
    fun theWitnessIsSilentAtFrictionOne() {
        // Not a bug: at f = 1 the correct pipeline and the broken one are identical, so the
        // witness cannot distinguish them. Asserted so the exclusion is enforced rather than
        // remembered, and so nobody "fixes" the default friction to 1 without meeting this.
        val spec = DecaySpec(friction = 1f, initialVelocity = 1f)
        IntegrationContract.assertTravelPreservesTheGestureVelocity(
            "degenerate", flingForgettingFriction(0f, 800f, spec), spec, 800f
        )
    }
```

- [ ] **Step 4: Run, then commit**

Expected: both pass. If the first does **not** go red, stop — the assertion cannot distinguish
the witness, and that is a spec-level finding, not a harness to adjust.

---

## Task 2: `AnimationService.fling`

- [ ] Implement it so it derives `to` through `DecaySpec.restingDisplacement`, and normalises the
      gesture velocity by the travel it just computed.
- [ ] Add the mirror of Task 1's test: the real `fling` passes
      `assertTravelPreservesTheGestureVelocity`.
- [ ] `require(gestureVelocity != 0f)` — a fling released at rest has zero travel, which
      `DecaySpec` already rejects; the message should say so at this level too.
- [ ] Do not touch `DecaySampler` or `samplerFor`.

---

## Task 3: `DecaySampler`

Three lines, moved rather than invented — `value = 1 - e^(-ft)`, `velocity = f·e^(-ft)`.

- [ ] Ship it in `runtime/`, and point `samplerFor` at it for a `DecaySpec`.
- [ ] `DecayTrajectory` **stays** in the test tree. It is the analytic subject the contract was
      verified against, and deleting it would remove the evidence rather than the duplication.
- [ ] A test asserts the two agree, since they are now two copies of one expression.

---

## Task 4: close

- [ ] §7: decay's `domain` becomes end-to-end; the `integration` layer row names
      `IntegrationContract`.
- [ ] Gate 5 narrows to `SnapSpec` alone, with a comment naming 06B.2 — **not deleted**; it still
      guards the one family without a solver.
- [ ] Both verify scripts and the full suite green on the VM.
- [ ] Squash, verify tree hash against the VM-verified commit, push, stop the VM.

---

## Exit criteria

The spec's §5, plus:

- [ ] Task 1 committed before any production code exists
- [ ] The degeneracy at `f = 1` asserted, not just documented
- [ ] `DecayTrajectory` still present and still used
