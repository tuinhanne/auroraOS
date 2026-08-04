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

import aurora.sdk.animation.MotionSampler
import aurora.sdk.animation.SpringSpec
import aurora.sdk.design.MotionTokens
import aurora.sdk.design.Spring
import org.junit.Assert.fail
import org.junit.Test

/**
 * Sprint 06B.1 Task 2: proving the physics properties can reject a spring.
 *
 * The subject of every test here is the **property**, not the spring. A green physics tier on the
 * real spring is not evidence until the property has been shown able to refuse one — and for a
 * spring that is not a formality, because `SpringSpec.completionMetric` and a spring trajectory
 * both derive from the same `ω`. A correct closed form makes the metric exactly `A·e^(-ζωt)`,
 * monotone by construction, so the property could pass having examined nothing.
 *
 * ## Every test asserts a complete red set
 *
 * Both directions: what must fail, and what must stay green. Asserting only the failures would
 * let a property that later begins failing unexpectedly hide behind "the fixture was meant to
 * fail anyway", which destroys exactly the information the declared red set preserves.
 *
 * A red set **larger** than declared means the witness broke more than one thing and the
 * attribution is gone. A red set **smaller** means the property is defective, which is the
 * failure this task exists to find.
 */
class SpringContractTest {

    // --- witness 1: physics tier only -----------------------------------------

    @Test
    fun anUndampedEnvelopeIsCaughtByThePhysicsTierAlone() {
        val spec = SpringSpec(spring = MotionTokens.SPRING_BOUNCY, initialVelocity = 2f)
        val wrong = UndampedEnvelopeSpring(spec)

        // Must stay green: the trajectory is perfectly self-consistent. Its velocity really is
        // the exact derivative of its own wrong position, which is what makes this witness
        // attributable - the sampler tier has nothing to say about it.
        SamplerContract.assertFinite(NAME_1, wrong)
        SamplerContract.assertDeterministic(NAME_1) { UndampedEnvelopeSpring(spec) }
        SamplerContract.assertVelocityMatchesDerivative(NAME_1, wrong)

        // Must go red, both of them: the metric is monotone along solutions of the spring
        // equation and this trajectory solves a different one, and an undamped oscillation
        // never arrives.
        assertRejects("$NAME_1/metric") {
            PhysicsContract.assertMetricNeverIncreases(NAME_1, spec, wrong)
        }
        assertRejects("$NAME_1/convergence") {
            PhysicsContract.assertConvergesToOne(NAME_1, spec, wrong)
        }
    }

    // --- witness 2: sampler tier only, contrary to the spec ---------------------

    /**
     * The spec predicted both tiers here and was wrong, which is what Task 2 is for.
     *
     * Its argument was that `completionMetric` reads velocity, so no wrong velocity can leave the
     * metric untouched. The term this witness drops is precisely the damping part of the
     * derivative, and `y' + ζωy` is the undamped part, so the metric becomes
     * `A²e^(-2ζωt)[cos² + (1-ζ²)sin²]` — a bracket that oscillates inside an exponential that
     * falls faster. Still monotone. The physics tier is right to accept it.
     *
     * The witness is therefore orthogonal, which is a better result than the coupling that was
     * expected, and it leaves only [WrongBranchSpring] genuinely coupled.
     */
    @Test
    fun aVelocityMissingItsDampingTermIsCaughtByTheSamplerTierAlone() {
        val spec = SpringSpec(spring = MotionTokens.SPRING_BOUNCY, initialVelocity = 2f)
        val wrong = UndampedVelocitySpring(spec)

        SamplerContract.assertFinite(NAME_2, wrong)
        SamplerContract.assertDeterministic(NAME_2) { UndampedVelocitySpring(spec) }

        assertRejects(NAME_2) {
            SamplerContract.assertVelocityMatchesDerivative(NAME_2, wrong)
        }

        // Must stay green. Its position is correct, so the motion still arrives and its metric
        // still falls - asserted rather than left unstated, so a later change that made this red
        // would be seen as the event it is.
        PhysicsContract.assertMetricNeverIncreases(NAME_2, spec, wrong)
        PhysicsContract.assertConvergesToOne(NAME_2, spec, wrong)
    }

    // --- witness 3: both tiers, more strongly --------------------------------

    @Test
    fun theHyperbolicBranchTakenForAnUnderdampedSpringIsCaughtByBothTiers() {
        val spec = SpringSpec(spring = MotionTokens.SPRING_BOUNCY, initialVelocity = 2f)
        val wrong = WrongBranchSpring(spec)

        SamplerContract.assertDeterministic(NAME_3) { WrongBranchSpring(spec) }

        assertRejects("$NAME_3/derivative") {
            SamplerContract.assertVelocityMatchesDerivative(NAME_3, wrong)
        }
        assertRejects("$NAME_3/convergence") {
            PhysicsContract.assertConvergesToOne(NAME_3, spec, wrong)
        }
    }

    // --- witness 4: neither tier, and that is the statement -------------------

    /**
     * The witness for the contract's **boundary**.
     *
     * Its declared red set is empty, so this test is an assertion about what the Aurora physics
     * contract does not promise: there is a class of numerical defect that both tiers accept.
     *
     * If this ever starts failing a tier, do not repair the test. Stop and decide which of three
     * things happened — the contract grew stronger, a property changed scope, or this witness
     * stopped isolating the class of error it was built for. That is a design event.
     */
    @Test
    fun aCancellingDiscriminantIsAcceptedByBothTiersAndIsStillWrong() {
        val spec = SpringSpec(
            spring = Spring(stiffness = 400f, dampingRatio = 0.9999f),
            initialVelocity = 2f,
        )
        val wrong = CancellingDiscriminantSpring(spec)

        SamplerContract.assertFinite(NAME_4, wrong)
        SamplerContract.assertDeterministic(NAME_4) { CancellingDiscriminantSpring(spec) }
        SamplerContract.assertVelocityMatchesDerivative(NAME_4, wrong)
        PhysicsContract.assertMetricNeverIncreases(NAME_4, spec, wrong)
        PhysicsContract.assertConvergesToOne(NAME_4, spec, wrong)
    }

    private fun assertRejects(what: String, body: () -> Unit) {
        var rejected = false
        try {
            body()
        } catch (expected: AssertionError) {
            rejected = true
        }
        if (!rejected) fail("$what should have been rejected, and was not")
    }

    private companion object {
        const val NAME_1 = "UndampedEnvelopeSpring"
        const val NAME_2 = "UndampedVelocitySpring"
        const val NAME_3 = "WrongBranchSpring"
        const val NAME_4 = "CancellingDiscriminantSpring"
    }
}
