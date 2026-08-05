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
import aurora.sdk.animation.SpringSpec
import aurora.sdk.design.MotionTokens
import aurora.testing.animation.IntegrationContract
import aurora.testing.animation.SamplerContract
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Sprint 06B.3 Task 1: the sprint's refutation point.
 *
 * The claim under test is Question 1 — that the integration layer begins once `to` exists, however
 * it came to exist, so one invariant covers supplied, derived and selected targets. A spring's
 * target is supplied, which makes it the cheapest possible test: no derivation, no selection, and
 * nothing new below the boundary.
 *
 * It passed, and it cost the assertion its signature. `friction` was never what the layer checked;
 * it was one family's way of obtaining the normalised velocity, and only a second family could
 * reveal that. The invariant did not change.
 */
class SpringPipelineTest {

    @Test
    fun aSpringNormalisesItsGestureVelocityByTheTravelItWasGiven() {
        val animation = SpringFactory.springTo("spring", from = 0f, to = 400f, gestureVelocity = 800f)
        IntegrationContract.assertTravelPreservesTheGestureVelocity(
            "SpringFactory", animation, animation.spec as SpringSpec, 800f
        )
        // 800 px/s over a 400 px travel is two whole travels per second - the worked example
        // PhysicsSpec's KDoc has carried since 06A, now with a caller that produces it.
        assertEquals(2f, (animation.spec as SpringSpec).initialVelocity, 1e-5f)
    }

    @Test
    fun aSpringWithNowhereToGoIsRejected() {
        try {
            SpringFactory.springTo("spring", from = 50f, to = 50f, gestureVelocity = 800f)
            fail("a zero travel cannot normalise a velocity")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    /**
     * The witness: a caller that hands the gesture velocity through unnormalised.
     *
     * Declared red set: **integration only**. `SpringSampler` is untouched, so the sampler layer
     * has nothing to object to — the spec it receives is internally consistent, it simply
     * describes a different motion from the one the gesture asked for.
     *
     * ## It is silent at a travel of exactly 1
     *
     * The discrepancy is `v(travel - 1)`, zero when `travel == 1`, where a normalised velocity and
     * a value-unit velocity coincide. Exactly the shape of `friction == 1` for the decay witness,
     * in a different coordinate: a degeneracy where the correct caller and the broken one are
     * observationally identical. The travel below is 400, not 1.
     */
    private fun springForgettingToNormalise(from: Float, to: Float, gestureVelocity: Float) =
        Animation(
            "spring/unnormalised",
            SpringSpec(spring = MotionTokens.SPRING_GENTLE, initialVelocity = gestureVelocity),
            from = from,
            to = to,
        )

    @Test
    fun aSpringThatForgetsToNormaliseIsCaughtByTheIntegrationLayerAlone() {
        val wrong = springForgettingToNormalise(0f, 400f, 800f)
        val spec = wrong.spec as SpringSpec

        // Must stay green: nothing below the boundary is wrong. The sampler is handed a
        // perfectly coherent spring - just not the one the gesture described.
        SamplerContract.assertFinite("unnormalised", SpringSampler(spec))
        SamplerContract.assertVelocityMatchesDerivative("unnormalised", SpringSampler(spec))

        assertRejects("springForgettingToNormalise") {
            IntegrationContract.assertTravelPreservesTheGestureVelocity(
                "unnormalised", wrong, spec, 800f
            )
        }
    }

    @Test
    fun theWitnessCannotDistinguishAnythingAtATravelOfOne() {
        // Asserted rather than remembered, for the same reason the decay witness asserts its
        // silence at friction 1: a degeneracy left in a comment is a witness that can be
        // disarmed by an unrelated change to a default.
        val degenerate = springForgettingToNormalise(0f, 1f, 800f)
        IntegrationContract.assertTravelPreservesTheGestureVelocity(
            "degenerate", degenerate, degenerate.spec as SpringSpec, 800f
        )
    }

    private fun assertRejects(what: String, body: () -> Unit) {
        var rejected = false
        try {
            body()
        } catch (expected: AssertionError) {
            rejected = true
        }
        if (!rejected) fail("$what should have been rejected by the integration layer, and was not")
    }
}
