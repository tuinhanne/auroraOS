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
 * Every contract property and the fixture that violates it. `verify-sprint06b0.sh` reads this
 * block and checks each name on the left is a real function in the harness, each name on the
 * right is a real class in `BrokenSamplers.kt`, and no `fun assert*` in the harness is missing
 * from the left column — so a property cannot be added later without declaring its fixture.
 *
 *   assertFinite                    <- NaNAfterConvergenceSampler
 *   assertDeterministic             <- SharedCounterSampler
 *   assertVelocityMatchesDerivative <- WrongDerivativeSampler
 *   assertMetricNeverIncreases      <- IncreasingEnvelopeSampler
 *   assertConvergesToOne            <- NonConvergingSampler
 *   assertOrderIndependent          <- solver-tier
 *
 * The physics pair are exercised in `PhysicsContractTest`, where that tier lives; the pairing is
 * declared here so there is one place to read it and one for the gate to check.
 *
 * `assertOrderIndependent` is listed with `solver-tier` rather than omitted. RULE-015 binds the
 * contract tier, so it needs no fixture — but an exemption that showed up as an absence would be
 * indistinguishable from someone forgetting one, which is the whole failure the gate exists to
 * catch. Every assertion appears; the ones that need no fixture say why.
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
