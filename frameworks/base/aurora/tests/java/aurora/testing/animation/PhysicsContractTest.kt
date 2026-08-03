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

import aurora.sdk.animation.DecaySpec
import aurora.sdk.animation.MotionSample
import aurora.sdk.animation.SnapSpec
import aurora.sdk.animation.SpringSpec
import aurora.sdk.design.MotionTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The physics tier, run against what Sprint 06B.0 has.
 *
 * ## What has a subject here and what does not
 *
 * A decay does: `DecayTrajectory` is the analytic solution and its metric — `|1 - value|`, which
 * is `e^(-f·t)` — falls by inspection. Both contract properties run against it.
 *
 * A spring does not, and that is stated rather than worked around. Its metric is never-increasing
 * because `dE/dt = -2ζωv²` along **solutions of** `ẍ + 2ζωẋ + ω²x = 0`; a hand-built oscillation
 * that merely looks damped is not a solution, so the metric need not fall along it and a red
 * would not say whether the metric or the fixture was wrong. Making it a genuine solution means
 * writing the closed form, which is Sprint 06B.1.
 *
 * So the envelope is checked here **algebraically**, on hand-built samples. That makes no claim
 * about a trajectory and needs none: it pins the properties the formula must have for the
 * monotonicity argument to be about the right quantity at all.
 */
class PhysicsContractTest {

    // --- the decay tier, with a trajectory ----------------------------------

    @Test
    fun ananalyticDecaySatisfiesThePhysicsContract() {
        val spec = DecaySpec(friction = 4.6f, initialVelocity = 2f)
        val trajectory = DecayTrajectory(friction = 4.6f)
        PhysicsContract.assertMetricNeverIncreases(DECAY, spec, trajectory)
        PhysicsContract.assertConvergesToOne(DECAY, spec, trajectory)
    }

    @Test
    fun ananalyticDecayAlsoSatisfiesTheSamplerContract() {
        // Its velocity is the derivative of its value in closed form, so it should pass the tier
        // below as well. If it does not, the failure is in the harness rather than in physics.
        val trajectory = DecayTrajectory(friction = 4.6f)
        SamplerContract.assertFinite(DECAY, trajectory)
        SamplerContract.assertDeterministic(DECAY) { DecayTrajectory(friction = 4.6f) }
        SamplerContract.assertVelocityMatchesDerivative(DECAY, trajectory)
    }

    @Test
    fun aMotionThatLosesGroundWhileRunningIsRejected() =
        assertRejects("IncreasingEnvelopeSampler") {
            PhysicsContract.assertMetricNeverIncreases(
                "rising", DecaySpec(initialVelocity = 2f), IncreasingEnvelopeSampler()
            )
        }

    @Test
    fun aMotionThatStopsShortIsRejected() =
        assertRejects("NonConvergingSampler") {
            PhysicsContract.assertConvergesToOne(
                "short", DecaySpec(initialVelocity = 2f), NonConvergingSampler()
            )
        }

    @Test
    fun stoppingShortIsInvisibleToMonotonicityAlone() {
        // The two properties are not redundant, and this states why. NonConvergingSampler moves
        // smoothly and its metric falls the whole way, so monotonicity accepts it; only
        // convergence notices that it arrived somewhere other than its target. A decay whose
        // caller-computed travel disagrees with its sampler's shape fails exactly this way.
        PhysicsContract.assertMetricNeverIncreases(
            "short", DecaySpec(initialVelocity = 2f), NonConvergingSampler()
        )
    }

    // --- the spring envelope, algebraically ---------------------------------

    @Test
    fun theEnvelopeIsZeroOnlyAtRestOnTheTarget() {
        val spec = SpringSpec()
        assertEquals(0f, spec.completionMetric(MotionSample(1f, 0f)), 0f)
        assertTrue(spec.completionMetric(MotionSample(1f, 1f)) > 0f)
        assertTrue(spec.completionMetric(MotionSample(0.9f, 0f)) > 0f)
    }

    @Test
    fun theEnvelopeTreatsOvershootAndUndershootAlike() {
        // A spring 0.1 past its target is as far from rest as one 0.1 short of it. If this were
        // asymmetric, a bouncy spring would report finished on one side of every oscillation and
        // not the other - which is a subtler form of the flicker the envelope was written to fix.
        val spec = SpringSpec(spring = MotionTokens.SPRING_BOUNCY)
        assertEquals(
            spec.completionMetric(MotionSample(0.9f, 0f)),
            spec.completionMetric(MotionSample(1.1f, 0f)),
            1e-6f,
        )
    }

    @Test
    fun theEnvelopeCountsSpeedAsDistanceStillToTravel() {
        // At the target but still moving, a spring is not finished: it will overshoot by roughly
        // v/omega, and that is exactly what the metric reports. This is the term the old rule
        // lacked, and the reason a turning point could read as arrival.
        val spec = SpringSpec()
        val omega = kotlin.math.sqrt(spec.spring.stiffness)
        assertEquals(2f / omega, spec.completionMetric(MotionSample(1f, 2f)), 1e-6f)
    }

    @Test
    fun aStifferSpringTolerantOfTheSameSpeedIsCloserToRest() {
        // omega scales the velocity term, so the same speed means less remaining travel in a
        // stiffer spring. Without this, completionThreshold would mean different things to
        // different tokens and could not be compared across them.
        val gentle = SpringSpec(spring = MotionTokens.SPRING_GENTLE)
        val snappy = SpringSpec(spring = MotionTokens.SPRING_SNAPPY)
        val moving = MotionSample(1f, 2f)
        assertTrue(snappy.completionMetric(moving) < gentle.completionMetric(moving))
    }

    @Test
    fun aSnapMeasuresRestExactlyAsItsSpringWould() {
        // Snap duplicates the formula rather than sharing it, so this is the check that the
        // duplicate has not drifted. It is the cost of declining to extract a base class two
        // specs would use, and it is cheaper than the base class.
        val sample = MotionSample(0.8f, 1.5f)
        val snap = SnapSpec(targets = listOf(0f, 1f), spring = MotionTokens.SPRING_BOUNCY)
        val spring = SpringSpec(spring = MotionTokens.SPRING_BOUNCY)
        assertEquals(spring.completionMetric(sample), snap.completionMetric(sample), 0f)
    }

    private fun assertRejects(what: String, body: () -> Unit) {
        var rejected = false
        try {
            body()
        } catch (expected: AssertionError) {
            rejected = true
        }
        if (!rejected) fail("$what should have been rejected by the contract, and was not")
    }

    private companion object {
        const val DECAY = "DecayTrajectory"
    }
}
