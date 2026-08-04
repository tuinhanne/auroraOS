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

import aurora.sdk.animation.DecaySpec
import aurora.testing.animation.DecayTrajectory
import aurora.testing.animation.IntegrationContract
import aurora.testing.animation.PhysicsContract
import aurora.testing.animation.SamplerContract
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Sprint 06B.2 Tasks 2 and 3: the real caller, and the sampler it hands off to.
 *
 * The integration assertion used here was calibrated in Task 1, against a caller built to violate
 * it, before any of this existed. That is what makes a green run below mean something.
 */
class DecayPipelineTest {

    // --- Task 2: the caller, at the unit boundary ----------------------------

    @Test
    fun aFlingInfersATravelThatReturnsTheVelocityItWasGiven() {
        val spec = DecaySpec(friction = FRICTION, initialVelocity = FRICTION)
        val animation = FlingFactory.fling("fling", from = 0f, gestureVelocity = GESTURE, spec = spec)
        IntegrationContract.assertInferredTravelReturnsTheVelocity(
            "FlingFactory", animation, spec, GESTURE
        )
    }

    @Test
    fun aFlingRestsWhereTheFrictionModelSays() {
        // The number, stated once, in value units: 800 px/s at friction 4.6 coasts 173.9 px.
        // Not a second implementation of the model - a spot check that the one implementation is
        // wired to the arithmetic a caller would expect, in the units a caller thinks in.
        val spec = DecaySpec(friction = 4.6f, initialVelocity = 4.6f)
        val animation = FlingFactory.fling("fling", from = 100f, gestureVelocity = 800f, spec = spec)
        assertEquals(273.9f, animation.to, 0.1f)
    }

    @Test
    fun aFlingReleasedAtRestIsRejected() {
        try {
            FlingFactory.fling("fling", 0f, 0f, DecaySpec(initialVelocity = 2f))
            fail("a fling released at rest travels nowhere")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun aFlingNormalisesItsVelocityToItsFriction() {
        // The identity from 06B.0, now with a caller depending on it: v/(v/f) = f. If this ever
        // fails, the two halves of the friction model have drifted apart.
        val spec = DecaySpec(friction = 3f, initialVelocity = 99f)
        val animation = FlingFactory.fling("fling", 0f, 500f, spec)
        assertEquals(3f, (animation.spec as DecaySpec).initialVelocity, 1e-5f)
    }

    // --- Task 3: the sampler -------------------------------------------------

    @Test
    fun theShippedSamplerAgreesWithTheAnalyticSubject() {
        // DecaySampler and DecayTrajectory are two copies of one expression, which is why the
        // sampler adds no evidence and why the trajectory is not deleted: it is the subject the
        // contract was verified against in 06B.0.
        val shipped = DecaySampler(DecaySpec(friction = FRICTION, initialVelocity = FRICTION))
        val analytic = DecayTrajectory(friction = FRICTION)
        for (i in 0..200) {
            val nanos = i * 10_000_000L
            assertEquals(analytic.sampleAt(nanos).value, shipped.sampleAt(nanos).value, 0f)
            assertEquals(analytic.sampleAt(nanos).velocity, shipped.sampleAt(nanos).velocity, 0f)
        }
    }

    @Test
    fun theShippedSamplerSatisfiesBothContractTiers() {
        val spec = DecaySpec(friction = FRICTION, initialVelocity = FRICTION)
        SamplerContract.assertFinite(NAME, DecaySampler(spec))
        SamplerContract.assertDeterministic(NAME) { DecaySampler(spec) }
        SamplerContract.assertVelocityMatchesDerivative(NAME, DecaySampler(spec))
        PhysicsContract.assertMetricNeverIncreases(NAME, spec, DecaySampler(spec))
        PhysicsContract.assertConvergesToOne(NAME, spec, DecaySampler(spec))
    }

    private companion object {
        const val NAME = "DecaySampler"
        const val FRICTION = 4.6f
        const val GESTURE = 800f
    }
}
