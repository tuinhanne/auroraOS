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

package aurora.testing.animation

import aurora.sdk.animation.MotionSample
import aurora.sdk.animation.MotionSampler
import aurora.sdk.animation.SpringSpec
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt

/**
 * Springs that are wrong on purpose, each a **minimal witness** for one statement about the
 * contract.
 *
 * Not a negative test suite: the subject under test is the *property*, not the spring. Each of
 * these exists so `SpringContractTest` can assert a complete **red set** — what must fail and,
 * just as importantly, what must stay green. Asserting only the failures would let a property
 * that begins failing unexpectedly hide behind "the fixture was supposed to fail anyway", which
 * is exactly the information the declared red set is there to preserve.
 *
 * Each is otherwise a copy of `SpringSampler`, **internally consistent in every dimension except
 * the one under test**. That is what makes the first and fourth attributable: changing a position
 * without correcting the velocity fires both tiers and destroys the attribution.
 *
 * None of these is a solver. They live in the test tree, and `verify-motion-evidence.sh` fails if one
 * appears outside it.
 *
 * ## That sentence was false until Sprint 06B.3
 *
 * Worth recording where it was written rather than only in the sprint that found it. The gate it
 * refers to checked a hardcoded list of five class names in `BrokenSamplers.kt`. None of the four
 * below was on it, and nobody extended the list when this file was added in Sprint 06B.1 — so the
 * guarantee was claimed here and enforced nowhere, for two sprints.
 *
 * A normative claim nothing enforces is the exact failure 06B.1 caught in the contract prose, and
 * it reappeared in a comment about the gate built in response. The check now derives its witness
 * set from this file and the manifest instead of naming classes, so adding a fifth wrong spring
 * extends it without anyone remembering to.
 */

/**
 * Breaks the completion metric: the envelope does not decay at all.
 *
 * Declared red set: **physics tier only** — both of its properties. Its velocity is the exact
 * derivative of its own wrong position, so the sampler tier has nothing to object to. This is a
 * trajectory that is perfectly self-consistent and simply is not a damped spring.
 *
 * ## Why the damping is dropped entirely rather than merely wrong
 *
 * A first draft decayed at `ω_d` instead of `ζω` — a subtler and more realistic mistake, and too
 * weak to be a witness. Writing `metric² = y² + (y'/ω)²` and differentiating gives
 * `2y'(y(1 - ω'²/ω²) - 2λy'/ω²)`, where `ω'` is the trajectory's own natural frequency. Decaying
 * too *fast* puts `ω'` above `ω`, which leaves a rise only in a narrow second-order window around
 * each turning point — while the envelope itself was falling 16% between probes, swamping it.
 *
 * With no damping at all, `ω' = ω_d < ω`, the sign of `(1 - ω'²/ω²)` flips positive, and the rise
 * becomes first order: `metric²` swings between `A²` and `(1-ζ²)A²` every half cycle. For
 * `SPRING_BOUNCY` that is a 25% rise, visible at any sampling rate the harness might use.
 *
 * The general lesson is worth more than the fixture: a witness has to fail by a margin that does
 * not depend on how finely the property happens to sample.
 */
class UndampedEnvelopeSpring(spec: SpringSpec) : MotionSampler {

    private val omega = sqrt(spec.spring.stiffness)
    private val zeta = spec.spring.dampingRatio
    private val discriminant = (1f - zeta) * (1f + zeta)
    private val omegaD = omega * sqrt(abs(discriminant))
    private val k = zeta * omega - spec.initialVelocity

    override fun sampleAt(elapsedNanos: Long): MotionSample {
        val t = elapsedNanos / 1_000_000_000f
        val z = omegaD * t
        val c = cos(z)
        val s = t * (if (z == 0f) 1f else sin(z) / z)
        // The one thing wrong: no e^(-ζωt) factor anywhere.
        val y = c + k * s
        // ...and the exact derivative of that, so the sampler tier stays green.
        val yPrime = -omega * omega * discriminant * s + k * c
        return MotionSample(value = 1f - y, velocity = -yPrime)
    }
}

/**
 * Breaks the derivative: the velocity drops the `-ζω(C + kS)` term.
 *
 * Declared red set: **sampler tier only**, which is not what the spec predicted.
 *
 * The spec argued that any wrong velocity must also disturb the metric, since `completionMetric`
 * reads velocity. Running it showed the argument is false, and the reason is specific: the term
 * dropped here is exactly the damping part of the derivative, and `y' + ζωy` is the *undamped*
 * part. Substituting the true solution gives
 *
 * ```
 * metric² = A² e^(-2ζωt) · [ cos² + (1-ζ²) sin² ]
 * ```
 *
 * whose bracket oscillates between 1 and `1-ζ²` while the exponential falls by `e^(-2.36)` over
 * each quarter cycle for `SPRING_BOUNCY`. The product still decreases, so the metric is monotone
 * and the physics tier is right to accept it.
 *
 * That makes this witness **orthogonal** after all — one property, one failure — which is a
 * better outcome than the coupling the spec expected, and it leaves only
 * [WrongBranchSpring] genuinely coupled.
 */
class UndampedVelocitySpring(spec: SpringSpec) : MotionSampler {

    private val omega = sqrt(spec.spring.stiffness)
    private val zeta = spec.spring.dampingRatio
    private val discriminant = (1f - zeta) * (1f + zeta)
    private val omegaScaled = omega * sqrt(abs(discriminant))
    private val k = zeta * omega - spec.initialVelocity

    override fun sampleAt(elapsedNanos: Long): MotionSample {
        val t = elapsedNanos / 1_000_000_000f
        val z = omegaScaled * t
        val c = cos(z)
        val s = t * (if (z == 0f) 1f else sin(z) / z)
        val decay = exp(-zeta * omega * t)
        val y = decay * (c + k * s)
        // The damping term is missing from the derivative and from nowhere else.
        val yPrime = decay * (-omega * omega * discriminant * s + k * c)
        return MotionSample(value = 1f - y, velocity = -yPrime)
    }
}

/**
 * Breaks branch selection: takes the hyperbolic branch while `ζ < 1`.
 *
 * Declared red set: **both tiers**. This is what the mistake looks like in practice — someone
 * inverts the comparison and `cosh`/`sinhc` are used where `cos`/`sinc` belong, while the
 * derivative's middle term keeps the sign the *correct* branch needs. Position and derivative go
 * wrong together, so the coupling here is stronger than in [UndampedVelocitySpring] rather than
 * merely unavoidable.
 */
class WrongBranchSpring(spec: SpringSpec) : MotionSampler {

    private val omega = sqrt(spec.spring.stiffness)
    private val zeta = spec.spring.dampingRatio
    private val discriminant = (1f - zeta) * (1f + zeta)
    private val omegaScaled = omega * sqrt(abs(discriminant))
    private val k = zeta * omega - spec.initialVelocity

    override fun sampleAt(elapsedNanos: Long): MotionSample {
        val t = elapsedNanos / 1_000_000_000f
        val z = omegaScaled * t
        val c = cosh(z)
        val s = t * (if (z == 0f) 1f else sinh(z) / z)
        val decay = exp(-zeta * omega * t)
        val y = decay * (c + k * s)
        val yPrime = decay * (-zeta * omega * (c + k * s) - omega * omega * discriminant * s + k * c)
        return MotionSample(value = 1f - y, velocity = -yPrime)
    }
}

/**
 * Breaks nothing the contract can express, and is still wrong.
 *
 * Declared red set: **neither tier**. It computes `1 - ζ²` as a direct subtraction, which at
 * `ζ = 0.9999` discards about four of float32's seven significant digits, so `ω_d` is wrong by
 * roughly `1e-3` relative before any trigonometry happens.
 *
 * Both tiers pass it, by construction rather than by accident. Its value and its velocity are
 * derived from the *same* bad `ω_d`, so they agree and the sampler tier is satisfied; the
 * completion metric is built on `ω` rather than `ω_d`, so its envelope still falls and the physics
 * tier is satisfied too. Everything the contract can express holds while the motion is wrong.
 *
 * **This is a witness for the contract's boundary rather than for the contract.** Its test asserts
 * that both tiers accept it — a statement about what Aurora's physics contract does *not* promise.
 * Only the numerical stability test in `SpringSamplerTest`, whose oracle is a `Double` evaluation
 * rather than the contract, rejects it.
 *
 * If this fixture ever starts failing a tier, that is a design event and not a regression. Stop
 * and ask whether the contract grew stronger, whether a property changed scope, or whether this
 * witness has stopped isolating the class of error it was built for.
 */
class CancellingDiscriminantSpring(spec: SpringSpec) : MotionSampler {

    private val omega = sqrt(spec.spring.stiffness)
    private val zeta = spec.spring.dampingRatio
    // The one thing wrong, and it is a subtraction of nearly equal quantities.
    private val discriminant = 1f - zeta * zeta
    private val underdamped = discriminant > 0f
    private val omegaScaled = omega * sqrt(abs(discriminant))
    private val k = zeta * omega - spec.initialVelocity

    override fun sampleAt(elapsedNanos: Long): MotionSample {
        val t = elapsedNanos / 1_000_000_000f
        val z = omegaScaled * t
        val c = if (underdamped) cos(z) else cosh(z)
        val s = t * (if (z == 0f) 1f else if (underdamped) sin(z) / z else sinh(z) / z)
        val decay = exp(-zeta * omega * t)
        val y = decay * (c + k * s)
        val yPrime = decay * (-zeta * omega * (c + k * s) - omega * omega * discriminant * s + k * c)
        return MotionSample(value = 1f - y, velocity = -yPrime)
    }
}
