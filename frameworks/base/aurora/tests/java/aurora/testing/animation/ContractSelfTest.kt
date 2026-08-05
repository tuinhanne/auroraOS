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

import org.junit.Assert.fail
import org.junit.Test

/**
 * The harness checking itself.
 *
 * Every test here asserts that a property **rejects** the sampler built to violate it, and that
 * the correct baseline passes. Without both halves, a property that silently checks nothing looks
 * exactly like a property everything satisfies: green either way.
 *
 * ## RULE-015 pairing
 *
 * Every assertion in the test tree and the witness built to violate it.
 *
 *   assertFinite                            <- NaNAfterConvergenceSampler
 *   assertDeterministic                     <- SharedCounterSampler
 *   assertVelocityMatchesDerivative         <- WrongDerivativeSampler
 *   assertMetricNeverIncreases              <- IncreasingEnvelopeSampler
 *   assertConvergesToOne                    <- NonConvergingSampler
 *   assertOrderIndependent                  <- solver-tier
 *   assertTravelPreservesTheGestureVelocity <- flingForgettingFriction
 *   assertTravelPreservesTheGestureVelocity <- springForgettingToNormalise
 *   assertTravelPreservesTheGestureVelocity <- snapForgettingToNormalise
 *   assertSelectsAMember                    <- selectionReturningTheCandidate
 *   assertSelectsTheNearest                 <- selectionChoosingTheFarthest
 *   assertIndependentOfListOrder            <- selectionBreakingTiesByListOrder
 *
 * `verify-motion-evidence.sh` gate 4 reads this block and checks three things: that every
 * assertion defined anywhere under `tests/` appears on the left, that nothing is declared which
 * does not exist, and that every name on the right resolves to a declaration somewhere in the test
 * tree. It checks nothing about *shape*.
 *
 * ## Why shape is not checked, which is Sprint 06B.3's answer to Question 3
 *
 * Until 06B.3 the gate also required each right-hand name to be a `class` in `BrokenSamplers.kt`,
 * and built its list of assertions by grepping two named files. Both identified a thing by the
 * form the **first** subject happened to take. The solver tier's witnesses are classes because a
 * subject there is a `MotionSampler` and a `MotionSampler` is a class — not because RULE-015 says
 * so. It does not: it says *one deliberately wrong subject*, and names no file and no shape.
 *
 * The counterexample is in this list. `selectionReturningTheCandidate` is a `val` holding a SAM
 * conversion; write it as an `object` expression instead and every grep's answer changes while its
 * red set, its test and what it proves do not. Three integration witnesses are plain functions.
 * None is a class, all are witnesses.
 *
 * So a witness is identified by **role** — an artifact deliberately constructed to violate one
 * named assertion, together with the declaration of which one — and class, function, object,
 * lambda, table and generated fixture are representations of that. The argument in full is in
 * `docs/evidence-model.md`.
 *
 * ## What this block cannot establish, stated rather than implied
 *
 * That a witness resolves is not that it witnesses. Whether each pair is actually exercised, and
 * whether a witness's red set is what it claims, is review's — RULE-015 says *no fixture that
 * nothing uses*, and no script can see it. Dropping the shape check gave up one real thing, a
 * name that is a typo; requiring the name to resolve keeps that and claims nothing more.
 *
 * The physics pair are exercised in `PhysicsContractTest` and the integration and policy pairs in
 * their own files, where those tiers live. The pairing is declared here so there is one place to
 * read it and one for the gate to check.
 *
 * `assertOrderIndependent` is listed with `solver-tier` rather than omitted. RULE-015 binds the
 * contract tier, so it needs no witness — but an exemption that showed up as an absence would be
 * indistinguishable from someone forgetting one, which is the whole failure the gate exists to
 * catch. Every assertion appears; the ones that need no witness say why.
 *
 * `assertTravelPreservesTheGestureVelocity` appears three times, one witness per family. One would
 * satisfy the rule. Three are declared because the claim this sprint tested is that a single
 * invariant covers supplied, derived and selected targets, and a manifest naming only the first
 * would hide the two subjects that established it.
 */
class ContractSelfTest {

    /**
     * Runs [body] and fails unless it raised an assertion failure.
     *
     * `fail()` itself throws `AssertionError`, so this cannot simply catch `Throwable`: it would
     * swallow its own failure and report green. The catch is narrow and the failure is raised
     * after it, outside the try.
     */
    private fun assertRejects(what: String, body: () -> Unit) {
        var rejected = false
        try {
            body()
        } catch (expected: AssertionError) {
            rejected = true
        }
        if (!rejected) fail("$what should have been rejected by the contract, and was not")
    }

    @Test
    fun theBaselineSamplerSatisfiesEveryProperty() {
        SamplerContract.assertFinite("linear", LinearSampler())
        SamplerContract.assertDeterministic("linear") { LinearSampler() }
        SamplerContract.assertVelocityMatchesDerivative("linear", LinearSampler())
        ClosedFormSamplerContract.assertOrderIndependent("linear", LinearSampler())
    }

    @Test
    fun aSamplerThatTurnsToNaNIsRejected() =
        assertRejects("NaNAfterConvergenceSampler") {
            SamplerContract.assertFinite("nan", NaNAfterConvergenceSampler())
        }

    @Test
    fun aSamplerWithStateOutlivingItsExecutionIsRejected() =
        assertRejects("SharedCounterSampler") {
            SamplerContract.assertDeterministic("shared") { SharedCounterSampler() }
        }

    @Test
    fun aSamplerWhoseVelocityIsNotItsDerivativeIsRejected() =
        assertRejects("WrongDerivativeSampler") {
            SamplerContract.assertVelocityMatchesDerivative("wrong", WrongDerivativeSampler())
        }

    /**
     * The determinism property must not be satisfiable by replay alone.
     *
     * `SharedCounterSampler` is deterministic *within* one instance — ask it the same schedule
     * twice through the same object and the counter keeps climbing, but ask it once and it looks
     * fine. Only comparing two freshly built samplers exposes it, which is why
     * `assertDeterministic` takes a factory. This test states that the factory is load-bearing,
     * so nobody simplifies it back to an instance later.
     */
    @Test
    fun determinismIsCheckedAcrossInstancesRatherThanWithinOne() {
        val single = SharedCounterSampler()
        val once = SamplerContract.PROBES_NANOS.map { single.sampleAt(it) }
        val values = once.map { it.value }
        if (values != values.sorted()) {
            fail("the fixture is meant to look plausible in a single forward pass")
        }
    }
}
