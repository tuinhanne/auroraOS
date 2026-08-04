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

import aurora.sdk.animation.Animation
import aurora.sdk.animation.DecaySpec
import org.junit.Assert.fail
import org.junit.Test

/**
 * Sprint 06B.2 Task 1: calibrating the integration layer, before anything in production uses it.
 *
 * No production code exists for this layer yet, and that is RULE-018 rather than a preference. The
 * layer has never existed, so if its assertion and its subject arrived together nothing
 * independent would remain and a green run would carry no information at all. The witness comes
 * first; `AnimationService.fling` comes second.
 *
 * ## The sampler is a constant in this experiment
 *
 * The sampler side is played by [DecayTrajectory], the analytic subject Sprint 06B.0 verified the
 * contract against. `DecaySampler` does not exist until Task 3 and is not needed: this witness's
 * defect lies entirely on the caller's side of the unit boundary, so holding the sampler fixed is
 * what makes the result attributable.
 */
class DecayIntegrationTest {

    /**
     * A caller that forgets to divide by friction — the first witness of the integration layer.
     *
     * Declared red set: **integration only**. Nothing below the boundary is touched, so both
     * existing layers must accept it, and that signature is the whole reason the layer exists.
     */
    private fun flingForgettingFriction(
        from: Float,
        gestureVelocity: Float,
        spec: DecaySpec,
    ): Animation = Animation("fling/forgot-friction", spec, from = from, to = from + gestureVelocity)

    @Test
    fun aFlingThatForgetsFrictionIsCaughtByTheIntegrationLayerAlone() {
        val spec = DecaySpec(friction = FRICTION, initialVelocity = FRICTION)
        val trajectory = DecayTrajectory(friction = FRICTION)

        // Must stay green: nothing below the unit boundary changed, and asserting that is what
        // distinguishes "the caller is wrong" from "something is wrong".
        SamplerContract.assertFinite(NAME, trajectory)
        SamplerContract.assertDeterministic(NAME) { DecayTrajectory(friction = FRICTION) }
        SamplerContract.assertVelocityMatchesDerivative(NAME, trajectory)
        PhysicsContract.assertMetricNeverIncreases(NAME, spec, trajectory)
        PhysicsContract.assertConvergesToOne(NAME, spec, trajectory)

        // Must go red. The discrepancy is v0(f - 1) = 800 * 3.6, independent of sampling rate,
        // tolerance and elapsed time.
        assertRejects(NAME) {
            IntegrationContract.assertTravelPreservesTheGestureVelocity(
                NAME, flingForgettingFriction(0f, GESTURE, spec), spec, GESTURE
            )
        }
    }

    /**
     * The witness is silent at friction 1, and that is asserted rather than remembered.
     *
     * At `f = 1` the correct pipeline and this one produce **identical** observations — the
     * discrepancy `v₀(f - 1)` is exactly zero — so the witness distinguishes nothing there. It is a
     * degeneracy in parameter space, the same shape of failure Sprint 06B.1 met in resolution
     * space when a witness sat inside the oracle's blind spot.
     *
     * Written as a test so that changing `DecaySpec`'s default friction to 1 fails here, loudly,
     * rather than quietly disarming the witness above.
     */
    @Test
    fun theWitnessCannotDistinguishAnythingAtFrictionOne() {
        val degenerate = DecaySpec(friction = 1f, initialVelocity = 1f)
        IntegrationContract.assertTravelPreservesTheGestureVelocity(
            "degenerate", flingForgettingFriction(0f, GESTURE, degenerate), degenerate, GESTURE
        )
    }

    private fun assertRejects(what: String, body: () -> Unit) {
        var rejected = false
        try {
            body()
        } catch (expected: AssertionError) {
            rejected = true
        }
        if (!rejected) {
            fail("$what should have been rejected by the integration layer, and was not")
        }
    }

    private companion object {
        const val NAME = "fling/forgot-friction"
        const val FRICTION = 4.6f
        const val GESTURE = 800f
    }
}
