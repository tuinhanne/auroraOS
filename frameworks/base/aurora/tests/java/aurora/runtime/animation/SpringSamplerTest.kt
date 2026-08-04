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

import aurora.sdk.animation.SpringSpec
import aurora.sdk.design.MotionTokens
import aurora.sdk.design.Spring
import aurora.testing.animation.ClosedFormSamplerContract
import aurora.testing.animation.PhysicsContract
import aurora.testing.animation.SamplerContract
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Sprint 06B.1 Task 1: the closed form, the sampler tier, and numerical stability.
 *
 * The physics tier is deliberately absent here. It runs in Task 3, and only after Task 2 has
 * shown those properties can reject a spring — a green physics tier is not evidence until then.
 */
class SpringSamplerTest {

    @Test
    fun aSpringStartsWhereItWasToldTo() {
        // t = 0 collapses the whole expression: decay = 1, c = 1, s = 0, so y = 1 and
        // y' = -zw + k = -v0. If this is wrong nothing downstream is worth reading, which is why
        // it is the first assertion in the sprint.
        val sample = SpringSampler(SpringSpec(initialVelocity = 3f)).sampleAt(0L)
        assertEquals(0f, sample.value, 1e-6f)
        assertEquals(3f, sample.velocity, 1e-4f)
    }

    @Test
    fun everyShippedSpringSatisfiesTheSamplerContract() {
        // SPRING_SNAPPY is zeta = 1 exactly, so it lands on the removable singularity and takes
        // the sinc(0) = 1 path. It is not an edge case; it is the most-used token in the system.
        val tokens = listOf(
            "bouncy" to MotionTokens.SPRING_BOUNCY,
            "gentle" to MotionTokens.SPRING_GENTLE,
            "snappy" to MotionTokens.SPRING_SNAPPY,
        )
        for ((label, token) in tokens) {
            val name = "SpringSampler/$label"
            val spec = SpringSpec(spring = token, initialVelocity = 2f)
            SamplerContract.assertFinite(name, SpringSampler(spec))
            SamplerContract.assertDeterministic(name) { SpringSampler(spec) }
            SamplerContract.assertVelocityMatchesDerivative(name, SpringSampler(spec))
            ClosedFormSamplerContract.assertOrderIndependent(name, SpringSampler(spec))
        }
    }

    @Test
    fun anOverdampedSpringSatisfiesTheSamplerContract() {
        // No shipped token is overdamped, so the sinh branch has no production subject. It is
        // exercised here or nowhere.
        val spec = SpringSpec(
            spring = Spring(stiffness = 400f, dampingRatio = 1.6f),
            initialVelocity = 2f,
        )
        SamplerContract.assertFinite("SpringSampler/overdamped", SpringSampler(spec))
        SamplerContract.assertDeterministic("SpringSampler/overdamped") { SpringSampler(spec) }
        SamplerContract.assertVelocityMatchesDerivative(
            "SpringSampler/overdamped", SpringSampler(spec)
        )
    }

    /**
     * The only guard on `(1-ζ)(1+ζ)`, and a third kind of evidence.
     *
     * Both contract tiers pass a spring that computes the discriminant as `1f - zeta * zeta`,
     * because its value and velocity come from the same bad `ω_d` and stay consistent, while the
     * completion metric uses `ω` rather than `ω_d`. Everything the contract can express is
     * satisfied and the motion is still wrong. This test's oracle is not the contract but a
     * higher-precision evaluation of the same mathematics.
     *
     * The reference is **the same expression in the same order with only the type changed**. An
     * independently rearranged oracle would leave a disagreement unattributable between a wrong
     * implementation, a wrong oracle, and two rearrangements losing precision differently — and
     * what is being measured here is the effect of float32, not the correctness of the solution.
     *
     * An identical oracle does not reproduce the defect, because the defect is precision loss:
     * the cancellation costs about four significant digits, leaving three of float32's seven and
     * twelve of `Double`'s sixteen. The corollary bounds what this test can see — against a
     * *structural* error an identical oracle would reproduce it and pass, which is why the other
     * fixtures are caught by the tiers instead.
     */
    @Test
    fun theFloatImplementationTracksADoubleEvaluationNearCriticalDamping() {
        val spec = SpringSpec(
            spring = Spring(stiffness = 400f, dampingRatio = 0.9999f),
            initialVelocity = 2f,
        )
        val sampler = SpringSampler(spec)
        for (i in 0..200) {
            val nanos = i * 10_000_000L
            val expected = referenceValueInDouble(spec, nanos)
            assertEquals(
                "value at ${nanos}ns",
                expected,
                sampler.sampleAt(nanos).value.toDouble(),
                STABILITY_BOUND,
            )
        }
    }

    /**
     * `SpringSampler.sampleAt` transcribed into `Double`, expression for expression.
     *
     * Deliberately not simplified and deliberately not rearranged. The one thing it does not copy
     * is the type.
     */
    private fun referenceValueInDouble(spec: SpringSpec, elapsedNanos: Long): Double {
        val omega = Math.sqrt(spec.spring.stiffness.toDouble())
        val zeta = spec.spring.dampingRatio.toDouble()
        val discriminant = (1.0 - zeta) * (1.0 + zeta)
        val underdamped = discriminant > 0.0
        val omegaScaled = omega * Math.sqrt(Math.abs(discriminant))
        val k = zeta * omega - spec.initialVelocity.toDouble()

        val t = elapsedNanos / 1_000_000_000.0
        val z = omegaScaled * t
        val c = if (underdamped) Math.cos(z) else Math.cosh(z)
        val s = t * (if (z == 0.0) 1.0 else (if (underdamped) Math.sin(z) / z else Math.sinh(z) / z))
        val decay = Math.exp(-zeta * omega * t)
        return 1.0 - decay * (c + k * s)
    }

    // --- Task 3: the physics tier ---------------------------------------------

    /**
     * The first time either physics property is asked about a real spring.
     *
     * It carries information only because Task 2 came first. `SpringContractTest` has shown that
     * both properties reject a spring built to violate them, that the witnesses fail by margins
     * independent of sampling rate, and that the oracle was not adjusted to produce this green.
     * Without that, a pass here could not be distinguished from a property examining nothing —
     * which was the live risk, since `completionMetric` and this trajectory both derive from the
     * same `ω`.
     *
     * If this had gone red with the sampler tier green, §7.1 of the contract would apply: the
     * metric would have been the suspect before the spring.
     */
    @Test
    fun everyShippedSpringSatisfiesThePhysicsContract() {
        val tokens = listOf(
            "bouncy" to MotionTokens.SPRING_BOUNCY,
            "gentle" to MotionTokens.SPRING_GENTLE,
            "snappy" to MotionTokens.SPRING_SNAPPY,
        )
        for ((label, token) in tokens) {
            val name = "SpringSampler/$label"
            val spec = SpringSpec(spring = token, initialVelocity = 2f)
            PhysicsContract.assertMetricNeverIncreases(name, spec, SpringSampler(spec))
            PhysicsContract.assertConvergesToOne(name, spec, SpringSampler(spec))
        }
    }

    @Test
    fun aSpringHandedAVelocityAwayFromItsTargetStillSatisfiesTheContract() {
        // The interesting initial condition, and the one a gesture actually produces: released
        // moving away from where it is going. The motion travels backwards before it turns, so
        // `value` goes negative - which the contract permits, since nothing is clamped - and the
        // completion metric must still fall the whole way.
        val spec = SpringSpec(spring = MotionTokens.SPRING_BOUNCY, initialVelocity = -4f)
        PhysicsContract.assertMetricNeverIncreases("SpringSampler/reversed", spec, SpringSampler(spec))
        PhysicsContract.assertConvergesToOne("SpringSampler/reversed", spec, SpringSampler(spec))
        SamplerContract.assertVelocityMatchesDerivative("SpringSampler/reversed", SpringSampler(spec))
    }

    private companion object {

        /**
         * Stated rather than tuned.
         *
         * float32 carries about seven significant digits and this trajectory is of order one, so
         * a correct implementation should stay within a few hundred ulps across two seconds.
         * **If this has to be widened to pass, that is the finding** — record it rather than
         * relaxing it, because the value of the test is entirely in the bound.
         */
        const val STABILITY_BOUND = 1e-4
    }
}
