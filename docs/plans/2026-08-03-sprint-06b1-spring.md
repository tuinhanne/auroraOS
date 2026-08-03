# Sprint 06B.1 — Spring: implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development.
> Steps use checkbox (`- [ ]`) syntax.

**Goal:** the first solver, judged by a contract that already exists.

**Spec:** `docs/specs/2026-08-03-sprint-06b1-spring-design.md` (frozen).

**This plan contains no design decisions.** Every formula, constant and boundary below is quoted
from the spec. If a decision appears while implementing — a tolerance, a branch, a fallback — that
is a signal to stop and amend the spec, not to settle it here. That happened twice while the spec
was being written and both times the spec was the right place for it.

---

## Task order

| task | stops when |
|---|---|
| 1 | `SpringSampler` exists, passes the sampler tier, and passes the stability test |
| 2 | every physics property has been seen **red** on a wrong spring, with the declared red set |
| 3 | the real spring meets the physics tier — or §7.1's rule has been applied to a red |
| 4 | §7 is a matrix, gate 5 is updated with its reason, docs match |

**Task 2 before Task 3 is the whole structure of this sprint.** A green physics tier on the real
spring means nothing until the property has been shown able to reject one. Running Task 3 first
and finding green leaves no way to tell "the contract holds" from "the property is vacuous", and
the §7 row would be closed on evidence that does not exist.

---

## File structure

**Created — production**
- `runtime/java/aurora/runtime/animation/SpringSampler.kt`

**Modified — production**
- `runtime/java/aurora/runtime/animation/AnimationHandleImpl.kt` — `samplerFor` stops refusing
  `SpringSpec`

**Created — tests**
- `tests/java/aurora/testing/animation/WrongSprings.kt` — the four fixtures
- `tests/java/aurora/runtime/animation/SpringSamplerTest.kt` — sampler tier, tokens, stability
- `tests/java/aurora/testing/animation/SpringContractTest.kt` — physics tier, and Task 2's proofs

**Modified**
- `tests/java/aurora/testing/animation/ContractSelfTest.kt` — RULE-015 pairing block
- `tools/verify-sprint06b0.sh` — gate 5
- `docs/contracts/motion-sampler-contract.md` — §7 matrix
- `frameworks/base/aurora/README.md` — RULE-016 extension, if accepted

---

## Task 1: `SpringSampler`, the sampler tier, and stability

- [ ] **Step 1: Write the sampler**

Create `SpringSampler.kt`. Every line below is the spec's §2 transcribed; the comments explain
why, so nobody simplifies it back.

```kotlin
class SpringSampler(spec: SpringSpec) : MotionSampler {

    private val omega = kotlin.math.sqrt(spec.spring.stiffness)
    private val zeta = spec.spring.dampingRatio

    /**
     * `1 - ζ²`, factored so it is not a difference of nearly equal quantities.
     *
     * At ζ = 0.9999 float32 computes ζ² as about 0.9998, and `1f - 0.9998f` throws away roughly
     * four significant digits — so ω_d would be wrong by 1e-3 relative before any trigonometry
     * happened. Neither factor here is such a difference.
     *
     * **Nothing in this file may recompute this as `1f - zeta * zeta`.** Note in particular that
     * the derivative's middle term is `ω²(ζ²-1)`, which written literally is exactly the
     * cancellation this line exists to avoid; it appears below as `-omega * omega * discriminant`.
     */
    private val discriminant = (1f - zeta) * (1f + zeta)

    private val underdamped = discriminant > 0f

    /** ω_d when underdamped, ω_h when overdamped. Zero at ζ = 1, which `sinc` handles. */
    private val omegaScaled = omega * kotlin.math.sqrt(kotlin.math.abs(discriminant))

    /** `ζω - v₀`, from the initial conditions y(0) = 1, y'(0) = -v₀. */
    private val k = zeta * omega - spec.initialVelocity

    override fun sampleAt(elapsedNanos: Long): MotionSample {
        val t = elapsedNanos / 1_000_000_000f
        val z = omegaScaled * t
        val c = if (underdamped) kotlin.math.cos(z) else kotlin.math.cosh(z)
        // S = sin(ω_d t)/ω_d, written as t·sinc(z) so ζ = 1 needs no branch: sinc(0) = 1 gives
        // S = t, which is exactly the critically damped solution. See spec §2.
        val s = t * (if (underdamped) sinc(z) else sinhc(z))
        val e = kotlin.math.exp(-zeta * omega * t)

        val y = e * (c + k * s)
        val yPrime = e * (-zeta * omega * (c + k * s) - omega * omega * discriminant * s + k * c)

        // value counts up from `from` toward the target; y counts down the distance left.
        return MotionSample(value = 1f - y, velocity = -yPrime)
    }

    private companion object {
        /** `sin(z)/z`, exact in the limit. Direct division has no cancellation for z ≠ 0. */
        fun sinc(z: Float): Float = if (z == 0f) 1f else kotlin.math.sin(z) / z

        fun sinhc(z: Float): Float = if (z == 0f) 1f else kotlin.math.sinh(z) / z
    }
}
```

- [ ] **Step 2: Assert the initial conditions before anything else**

```kotlin
    @Test
    fun aSpringStartsWhereItWasToldTo() {
        // t = 0 collapses the whole expression: E = 1, C = 1, S = 0, so y = 1 and
        // y' = -ζω + k = -v₀. If this is wrong, nothing downstream is worth reading.
        val spec = SpringSpec(spring = MotionTokens.SPRING_GENTLE, initialVelocity = 3f)
        val s = SpringSampler(spec).sampleAt(0L)
        assertEquals(0f, s.value, 1e-6f)
        assertEquals(3f, s.velocity, 1e-4f)
    }
```

- [ ] **Step 3: Run the sampler tier against all three shipped tokens**

```kotlin
    @Test
    fun everyShippedSpringSatisfiesTheSamplerContract() {
        // SPRING_SNAPPY is ζ = 1 exactly, so it lands on the removable singularity and takes the
        // sinc(0) = 1 path. It is not an edge case; it is the most-used token in the system.
        for (token in listOf(SPRING_BOUNCY, SPRING_GENTLE, SPRING_SNAPPY)) {
            val name = "SpringSampler/$token"
            val spec = SpringSpec(spring = token, initialVelocity = 2f)
            SamplerContract.assertFinite(name, SpringSampler(spec))
            SamplerContract.assertDeterministic(name) { SpringSampler(spec) }
            SamplerContract.assertVelocityMatchesDerivative(name, SpringSampler(spec))
            ClosedFormSamplerContract.assertOrderIndependent(name, SpringSampler(spec))
        }
    }

    @Test
    fun anOverdampedSpringSatisfiesTheSamplerContract() {
        // No shipped token is overdamped, so this branch has no production subject. It is
        // exercised here or not at all.
        val spec = SpringSpec(spring = Spring(stiffness = 400f, dampingRatio = 1.6f),
                              initialVelocity = 2f)
        SamplerContract.assertFinite("SpringSampler/overdamped", SpringSampler(spec))
        SamplerContract.assertVelocityMatchesDerivative("SpringSampler/overdamped",
                                                        SpringSampler(spec))
    }
```

> **If the overdamped case fails `assertFinite` at large t, stop and return to the spec.**
> `cosh(z)` overflows float32 above z ≈ 88 while `e^(-ζωt)` underflows, and the product is finite
> only if they are combined before either is evaluated. Whether to restructure the expression is a
> design decision and belongs in the spec, not here. It affects no shipped token, so it must not be
> patched quietly to keep this task green.

- [ ] **Step 4: The stability test**

```kotlin
    @Test
    fun theFloatImplementationTracksADoubleEvaluationNearCriticalDamping() {
        // The only guard on the (1-ζ)(1+ζ) factoring: a spring that computes it as 1 - ζ*ζ stays
        // internally consistent, so both contract tiers pass it. This is the third kind of
        // evidence — its oracle is higher precision, not the contract. See spec §2.
        val zeta = 0.9999f
        val spec = SpringSpec(spring = Spring(stiffness = 400f, dampingRatio = zeta),
                              initialVelocity = 2f)
        val sampler = SpringSampler(spec)
        for (i in 0..200) {
            val nanos = i * 10_000_000L
            val expected = referenceValueInDouble(spec, nanos)
            assertEquals("at ${nanos}ns", expected, sampler.sampleAt(nanos).value.toDouble(), 1e-4)
        }
    }
```

`referenceValueInDouble` is the same closed form in `Double` — **the same expression, in the same
order, with only the type changed.** Not an independent algebraic rearrangement: if the oracle
reorganised the algebra, a disagreement could mean the implementation is wrong, the oracle is
wrong, or two different rearrangements lose precision in two different ways, and the test would
not distinguish them. What is being measured is the effect of float32, not the correctness of the
solution.

That raises an obvious objection — if the oracle is the same expression, does it not reproduce the
same bug? It does not, and the reason bounds what this test is good for. The defect is precision
loss: the cancellation in `1 - ζ²` costs about four significant digits, which leaves three of
float32's seven and twelve of `Double`'s sixteen. **The same poor expression is still accurate in
`Double`**, so the comparison stands.

The corollary is the boundary. This test is valid only against *precision* defects. Against a
*structural* one — a wrong formula — an identical-expression oracle would reproduce the error and
pass, which is exactly why the other three fixtures are caught by the tiers instead and only the
fourth is caught here.

The bound `1e-4`
is stated rather than tuned: float32 carries about seven significant digits, the trajectory is
order 1, and a correct implementation should stay within a few hundred ulps across two seconds.
**If it needs widening to pass, that is the finding** — record it, do not widen it.

- [ ] **Step 5: Wire `samplerFor`, run the whole suite, commit**

`AnimationHandleImpl.samplerFor` returns `SpringSampler(spec)` for a `SpringSpec` and keeps
refusing the other two. Gate 5 of `verify-sprint06b0.sh` will now fail — leave it failing until
Task 4, which is where the invariant change is recorded with its reason.

---

## Task 2: prove the physics properties can reject a spring

Its subject is the **property**, not the spring.

- [ ] **Step 1: Write the four wrong springs**

Create `WrongSprings.kt`. Each names the one assumption it breaks and its declared red set, taken
from spec §3. Each is otherwise a copy of `SpringSampler` — **internally consistent in every
dimension except the one under test**, which is what makes the first and fourth attributable.

| class | breaks | declared red set |
|---|---|---|
| `EnvelopeAtDampedFrequencySpring` | decays at `ω_d`, reports the true derivative of that | physics tier only |
| `UndampedVelocitySpring` | velocity omits `ζ` | both tiers |
| `WrongBranchSpring` | takes `cosh`/`sinhc` for `ζ < 1` | both tiers |
| `CancellingDiscriminantSpring` | `1f - zeta * zeta` | **neither tier** |

- [ ] **Step 2: Assert each red set exactly**

Not "it fails". **Which** properties fail, and which pass:

```kotlin
    @Test
    fun aSpringWhoseEnvelopeDecaysAtTheDampedFrequencyIsCaughtByThePhysicsTierAlone() {
        val spec = SpringSpec(spring = MotionTokens.SPRING_BOUNCY, initialVelocity = 2f)
        val wrong = EnvelopeAtDampedFrequencySpring(spec)
        // Passes below: its velocity really is the derivative of its (wrong) position.
        SamplerContract.assertVelocityMatchesDerivative("wrongEnvelope", wrong)
        SamplerContract.assertFinite("wrongEnvelope", wrong)
        // Fails above.
        assertRejects("EnvelopeAtDampedFrequencySpring") {
            PhysicsContract.assertMetricNeverIncreases("wrongEnvelope", spec, wrong)
        }
    }
```

A red set **larger** than declared means the fixture is defective — it broke more than one thing.
A red set **smaller** means the *property* is defective, and that is the failure this task exists
to find. Both are stop conditions.

- [ ] **Step 3: `CancellingDiscriminantSpring` must pass both tiers**

The awkward one, and the point of including it:

```kotlin
    @Test
    fun aSpringWithACancellingDiscriminantPassesBothTiersAndIsStillWrong() {
        // Declared red set: neither tier. Its value and velocity come from the same bad ω_d, so
        // they agree; its envelope uses ω_n, so the metric still falls. Everything the contract
        // can express is satisfied while the motion is visibly wrong.
        //
        // This is a fixture of the contract's BOUNDARY, not of the contract. If a future change
        // makes a tier reject it, that tier has grown a capability and §7 should say so.
        val spec = SpringSpec(spring = Spring(stiffness = 400f, dampingRatio = 0.9999f),
                              initialVelocity = 2f)
        val wrong = CancellingDiscriminantSpring(spec)
        SamplerContract.assertVelocityMatchesDerivative("cancelling", wrong)
        PhysicsContract.assertMetricNeverIncreases("cancelling", spec, wrong)
        // And the stability test is the only thing that catches it:
        assertRejects("CancellingDiscriminantSpring") {
            assertTracksDoubleReference(spec, wrong)
        }
    }
```

- [ ] **Step 4: Declare all four in the RULE-015 pairing block, run, commit**

---

## Task 3: the real spring against the physics tier

- [ ] **Step 1: Run both physics properties on `SpringSampler`, all three tokens**

- [ ] **Step 2: If red, apply §7.1 before touching the spring**

Quoted from `docs/contracts/motion-sampler-contract.md`, written before any spring existed:

> If an implementation satisfies the sampler tier's independent invariants but fails a
> physics-tier property, investigate the contract and its metric before changing the
> implementation. Reverse the presumption only on independent evidence against the implementation.

Task 2's results are what make this usable: the red set's *shape* is the discriminator. Both tiers
red points at the spring; physics tier alone points at the metric or the contract.

Write the investigation down before changing anything. If the contract is amended, the amendment
is a commit of its own with its evidence, and §7 records the row as **revised** rather than
verified.

---

## Task 4: close the loop

- [ ] **Step 1: §7 becomes a matrix**, spring column filled from Task 3's actual results

- [ ] **Step 2: Gate 5 of `verify-sprint06b0.sh`**

It currently asserts exactly one `UnsupportedOperationException`, and Task 1 broke that on
purpose. The gate did its job: it made an intended change to an invariant impossible to make
silently. Update it to two refusals — decay and snap — with a comment naming the sprint that
removed the third. **Do not delete the gate**; it still guards the two families that have no
solver.

- [ ] **Step 3: RULE-016 extension into the README, if accepted**

Spec §3 proposes it; it is not applied by the spec because RULE-016 shipped in 06B.0.

- [ ] **Step 4: Full suite and both verify scripts green on the VM, then commit**

---

## Exit criteria

Spec §7's list, plus:

- [ ] Every wrong spring's red set asserted, not observed
- [ ] Gate 5 updated with a reason rather than deleted
- [ ] Any §7 row marked *revised* carries the evidence that revised it
