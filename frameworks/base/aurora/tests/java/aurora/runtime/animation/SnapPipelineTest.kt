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
import aurora.sdk.animation.SnapSpec
import aurora.sdk.animation.SpringSpec
import aurora.testing.animation.IntegrationContract
import aurora.testing.animation.SamplerContract
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Sprint 06B.3 Task 3: the third family, and the prediction it was written to test.
 *
 * Two claims are settled here, and they are independent.
 *
 * **Question 1, completed.** `assertTravelPreservesTheGestureVelocity` covers a *selected* target
 * with no change to the invariant, having already covered a supplied one (Task 1) and a derived one
 * (06B.2). One law, three provenances, and the layer never observes which ran.
 *
 * **The prediction of `fa99e81`, tested.** It was recorded before `SnapFactory` existed and says
 * where this family's witness must fall silent: at a travel of exactly 1, and nowhere else. A
 * prediction written afterwards would be a description, so the two tests at the bottom of this file
 * are the whole reason the design commit came first.
 */
class SnapPipelineTest {

    private val threeStops = SnapSpec(targets = listOf(0f, 100f, 400f))

    // -------------------------------------------------------------------------------------------
    // Question 1 on a selected target
    // -------------------------------------------------------------------------------------------

    @Test
    fun aSnapNormalisesItsGestureVelocityByTheTravelToTheTargetItSelected() {
        val animation = SnapFactory.snapTo(
            "snap", from = 0f, candidate = 380f, gestureVelocity = 800f, spec = threeStops
        )
        IntegrationContract.assertTravelPreservesTheGestureVelocity(
            "SnapFactory", animation, animation.spec as SpringSpec, 800f
        )
        // The same worked example the other two families carry: 800 px/s over a 400 px travel is
        // two whole travels per second. Only the way `to` was obtained differs.
        assertEquals(400f, animation.to, 0f)
        assertEquals(2f, (animation.spec as SpringSpec).initialVelocity, 1e-5f)
    }

    @Test
    fun aSnapReachesTheEngineAsASpringAndNeedsNoSamplerOfItsOwn() {
        val animation = SnapFactory.snapTo(
            "snap", from = 0f, candidate = 380f, gestureVelocity = 800f, spec = threeStops
        )
        val spec = animation.spec as SpringSpec
        // Sprint 06B.0 named this as what would count as evidence that the abstraction was found
        // rather than imposed: the solver layer does not change at all.
        SamplerContract.assertFinite("snap", SpringSampler(spec))
        SamplerContract.assertVelocityMatchesDerivative("snap", SpringSampler(spec))
    }

    // -------------------------------------------------------------------------------------------
    // The factory selects through the policy rather than reimplementing it
    // -------------------------------------------------------------------------------------------

    @Test
    fun theTargetIsTheOneThePolicyChose() {
        for (candidate in listOf(-50f, 10f, 60f, 260f, 380f, 900f)) {
            val animation = SnapFactory.snapTo(
                "snap", from = -1000f, candidate = candidate, gestureVelocity = 800f,
                spec = threeStops,
            )
            assertEquals(
                TargetSelectionPolicy.nearest(candidate, threeStops.targets),
                animation.to,
                0f,
            )
        }
    }

    @Test
    fun theFactoryInheritsThePolicysTieBreakRatherThanTheListOrder() {
        // A factory that reached for minByOrNull instead of the policy would agree everywhere
        // except here, and would disagree with itself when the same targets arrived reversed.
        val forwards = SnapFactory.snapTo(
            "snap", from = -5f, candidate = 5f, gestureVelocity = 100f,
            spec = SnapSpec(targets = listOf(0f, 10f)),
        )
        val backwards = SnapFactory.snapTo(
            "snap", from = -5f, candidate = 5f, gestureVelocity = 100f,
            spec = SnapSpec(targets = listOf(10f, 0f)),
        )
        assertEquals(0f, forwards.to, 0f)
        assertEquals(0f, backwards.to, 0f)
    }

    @Test
    fun theCompletionThresholdSurvivesTheJourneyIntoTheSpring() {
        // Why SpringFactory grew a parameter in Task 3. Dropped silently, this would settle the
        // motion at a different moment with nothing to report it.
        val animation = SnapFactory.snapTo(
            "snap", from = 0f, candidate = 380f, gestureVelocity = 800f,
            spec = SnapSpec(targets = listOf(0f, 400f), completionThreshold = 0.02f),
        )
        assertEquals(0.02f, (animation.spec as SpringSpec).completionThreshold, 0f)
    }

    @Test
    fun aSnapAlreadyRestingOnItsNearestTargetIsRejected() {
        try {
            SnapFactory.snapTo(
                "snap", from = 100f, candidate = 101f, gestureVelocity = 50f, spec = threeStops
            )
            fail("a snap with no travel cannot normalise a velocity")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    // -------------------------------------------------------------------------------------------
    // The witness, and the prediction
    // -------------------------------------------------------------------------------------------

    /**
     * A snap that selects correctly and then hands the gesture velocity through unnormalised.
     *
     * Declared red set: **integration only**, exactly as the spring and decay witnesses. Selection
     * is not what it gets wrong — it uses the real policy — so the policy properties stay green,
     * and the spring it produces is internally coherent, so the sampler layer stays green too.
     */
    private fun snapForgettingToNormalise(
        from: Float,
        candidate: Float,
        gestureVelocity: Float,
        spec: SnapSpec,
    ): Animation {
        val selected = TargetSelectionPolicy.nearest(candidate, spec.targets)
        return Animation(
            "snap/unnormalised",
            SpringSpec(spring = spec.spring, initialVelocity = gestureVelocity),
            from = from,
            to = selected,
        )
    }

    @Test
    fun aSnapThatForgetsToNormaliseIsCaughtByTheIntegrationLayerAlone() {
        val wrong = snapForgettingToNormalise(0f, 380f, 800f, threeStops)
        val spec = wrong.spec as SpringSpec

        SamplerContract.assertFinite("unnormalised", SpringSampler(spec))
        SamplerContract.assertVelocityMatchesDerivative("unnormalised", SpringSampler(spec))

        assertRejects("snapForgettingToNormalise") {
            IntegrationContract.assertTravelPreservesTheGestureVelocity(
                "unnormalised", wrong, spec, 800f
            )
        }
    }

    /**
     * The prediction, first half: **silent where the normalisation degenerates to the identity.**
     *
     * Recorded in `fa99e81` before this file existed. Snap normalises by `selectedTarget - from`,
     * so the predicted blind spot is a selected target exactly one unit from `from` — the same
     * point as decay's `friction == 1` and spring's `travel == 1`, in a third coordinate.
     *
     * The second case is the sharper one. Its candidate is ten units away and its other target
     * fifty, so nothing about the *gesture* is degenerate; only the quantity the normalisation
     * divides by is. If the blind spot tracked the candidate rather than the travel, this case
     * would be red and the prediction would have named the wrong quantity.
     */
    @Test
    fun theWitnessIsSilentWhenTheSelectedTargetSitsExactlyOneUnitFromFrom() {
        val adjacent = snapForgettingToNormalise(
            from = 0f, candidate = 0.9f, gestureVelocity = 800f,
            spec = SnapSpec(targets = listOf(0.5f, 1f)),
        )
        IntegrationContract.assertTravelPreservesTheGestureVelocity(
            "degenerate", adjacent, adjacent.spec as SpringSpec, 800f
        )

        val farCandidateNearTarget = snapForgettingToNormalise(
            from = 0f, candidate = 10f, gestureVelocity = 800f,
            spec = SnapSpec(targets = listOf(1f, 50f)),
        )
        assertEquals(1f, farCandidateNearTarget.to, 0f)
        IntegrationContract.assertTravelPreservesTheGestureVelocity(
            "degenerate/far candidate", farCandidateNearTarget,
            farCandidateNearTarget.spec as SpringSpec, 800f
        )
    }

    /**
     * The prediction, second half: **and nowhere else.**
     *
     * The half that can actually refute it. A witness silent at the predicted point but also silent
     * somewhere unpredicted would leave the pattern describing one case out of two, which is not
     * what `fa99e81` claimed. Travels above and below 1, and of both signs.
     *
     * The nearest travels here are 1.001 and 0.999 rather than 1.0001, and that is not a rounded
     * number chosen for looks — see
     * [theToleranceEnlargesTheOraclesBlindSpotWithoutMovingTheDegeneracy].
     */
    @Test
    fun theWitnessIsCaughtEverywhereTheTravelIsNotOne() {
        val travels = listOf(400f, 100f, 2f, 1.001f, 0.999f, 0.5f, -1f, -400f)
        for (travel in travels) {
            val spec = SnapSpec(targets = listOf(travel))
            val wrong = snapForgettingToNormalise(
                from = 0f, candidate = travel, gestureVelocity = 800f, spec = spec
            )
            assertRejects("snapForgettingToNormalise at a travel of $travel") {
                IntegrationContract.assertTravelPreservesTheGestureVelocity(
                    "travel $travel", wrong, wrong.spec as SpringSpec, 800f
                )
            }
        }
    }

    /**
     * Two blind spots, belonging to two different things, and this exists to keep them apart.
     *
     * **The degeneracy is still exactly `travel == 1`.** That is a property of the model: it is
     * where the normalising quantity equals 1, so the normalisation *is* the identity and a caller
     * who skips it cannot be told from one who performs it. A finite assertion tolerance does not
     * move that point and does not widen it.
     *
     * **What the tolerance enlarges is the oracle's indistinguishable region around it.**
     * `assertTravelPreservesTheGestureVelocity` compares floats at a relative 1e-4, so a caller
     * whose travel is within about 1e-4 of 1 is wrong and invisible *to this assertion*. That is a
     * fact about the oracle, not about snap, and the same region surrounds the spring and decay
     * witnesses — neither of which was ever asked about the neighbourhood of its own blind spot, so
     * this is the first time anyone looked rather than something the third family introduced.
     *
     * Read as one thing, the pair becomes "the property of a snap holds on an interval", which is
     * false and would go on being repeated. The prediction of `fa99e81` is about the first; the
     * numbers below are about the second.
     *
     * Found rather than designed: the first draft of [theWitnessIsCaughtEverywhereTheTravelIsNotOne]
     * used a travel of 1.0001, where the discrepancy `v(travel - 1)` is 0.08 against a scale of
     * 800.08 — 9.999e-5, just under the tolerance, so the witness passed and the test failed. The
     * value moved to 1.001 there and the reason moved here, rather than the number being quietly
     * rounded until the suite went green.
     */
    @Test
    fun theToleranceEnlargesTheOraclesBlindSpotWithoutMovingTheDegeneracy() {
        // Inside the band: wrong, and invisible to this assertion at this tolerance.
        val insideTheBand = snapForgettingToNormalise(
            from = 0f, candidate = 1.00005f, gestureVelocity = 800f,
            spec = SnapSpec(targets = listOf(1.00005f)),
        )
        IntegrationContract.assertTravelPreservesTheGestureVelocity(
            "inside the band", insideTheBand, insideTheBand.spec as SpringSpec, 800f
        )

        // Outside it: the same error, one order of magnitude further out, and caught.
        val outsideTheBand = snapForgettingToNormalise(
            from = 0f, candidate = 1.0005f, gestureVelocity = 800f,
            spec = SnapSpec(targets = listOf(1.0005f)),
        )
        assertRejects("a travel of 1.0005") {
            IntegrationContract.assertTravelPreservesTheGestureVelocity(
                "outside the band", outsideTheBand, outsideTheBand.spec as SpringSpec, 800f
            )
        }
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
