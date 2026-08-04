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
import kotlin.math.abs
import org.junit.Assert.fail

/**
 * Properties every [MotionSampler] must satisfy, whatever motion it models.
 *
 * These are requirements, not consequences of the interface. An implementation can fail any of
 * them while still compiling and still looking plausible on screen, which is why
 * `ContractSelfTest` pairs each one with a sampler built to violate it (RULE-015). An assertion
 * never shown to go red has not been shown to check anything.
 *
 * ## Everything here samples forward, exactly once
 *
 * [MotionSampler] promises only that callers sample in non-decreasing order of elapsed time: a
 * closed-form sampler may be asked in any order, but **a stepped one integrates forward and
 * cannot go back**. A property that probed backwards, or that asked for the same instant twice,
 * would reject an implementation the interface explicitly permits — the Sprint 06A failure mode
 * where a test invents a promise the design never made.
 *
 * So each assertion takes one ascending pass, keeps the samples, and does its arithmetic on what
 * it kept. Order-independence is a real and useful property, but it belongs to closed-form
 * samplers alone and lives in [ClosedFormSamplerContract], not here.
 *
 * Every failure names the sampler it ran against, so a sampler nobody checked cannot hide inside
 * a green run.
 */
object SamplerContract {

    /**
     * Two seconds at 10ms, ascending. The coverage grid.
     *
     * Dense enough to catch a defect that appears only in part of a motion. It is **not** the
     * grid the derivative check differentiates on — see [DERIVATIVE_PROBES_NANOS]. Coverage and
     * measurement accuracy are separate concerns and move for separate reasons; conflating them
     * is what left the derivative check measuring itself when its first stiff subject arrived.
     */
    val PROBES_NANOS: List<Long> = (0..200).map { it * 10_000_000L }

    /** One ascending pass. Every assertion here starts with this and touches the sampler no more. */
    private fun scan(sampler: MotionSampler): List<MotionSample> =
        PROBES_NANOS.map { sampler.sampleAt(it) }

    /**
     * No sample is NaN or infinite.
     *
     * The cheapest property and among the most valuable: a NaN reaches the screen as a view that
     * silently stops drawing, with no exception anywhere to point at the cause.
     */
    fun assertFinite(name: String, sampler: MotionSampler) {
        scan(sampler).forEachIndexed { i, s ->
            if (!s.value.isFinite() || !s.velocity.isFinite()) {
                fail("$name produced a non-finite sample at ${PROBES_NANOS[i]}ns: $s")
            }
        }
    }

    /**
     * Two samplers built the same way and driven through the same schedule agree at every step.
     *
     * Takes a factory rather than an instance, because that is the only way to state determinism
     * without also demanding replay. Asking one sampler for the same instant twice would test
     * order-independence instead, and a stepped sampler is allowed to refuse that.
     *
     * What this catches is a sampler whose output depends on anything other than the elapsed time
     * it was given — a wall clock, a random seed, or state shared between instances.
     */
    fun assertDeterministic(name: String, newSampler: () -> MotionSampler) {
        val first = scan(newSampler())
        val second = scan(newSampler())
        for (i in PROBES_NANOS.indices) {
            if (first[i] != second[i]) {
                fail(
                    "$name is not deterministic: two samplers disagreed at " +
                        "${PROBES_NANOS[i]}ns, ${first[i]} against ${second[i]}"
                )
            }
        }
    }

    /**
     * Two seconds at 1ms, ascending. The grid this property differentiates on.
     *
     * Separate from [PROBES_NANOS] on purpose: that one exists for **coverage** — enough points
     * across the motion to catch a local defect — while this one sets the **accuracy of the
     * measurement**, which is a different concern and moves for different reasons.
     *
     * Still not any sampler's own step, so RULE-016 holds: `TimedSampler` differentiates at
     * 0.5ms, and comparing a central difference against itself at the same step would pass for
     * every input.
     */
    private val DERIVATIVE_PROBES_NANOS: List<Long> = (0..2000).map { it * 1_000_000L }

    /**
     * Reported velocity is the derivative of reported value.
     *
     * ## The oracle's accuracy is itself a criterion
     *
     * This compares against a numerical approximation, so the approximation's error has to stay
     * well under [TOLERANCE] for **every subject the contract admits** — otherwise the property
     * stops measuring the sampler and starts measuring itself.
     *
     * The step was 10ms in Sprint 06B.0, chosen when this harness had exactly one subject:
     * `TimedSampler`, whose timescale is a whole timeline. Sprint 06B.1 brought the first subject
     * with a high natural frequency, `SPRING_SNAPPY` at `ω = √800 ≈ 28.3`, and central difference
     * truncation error grows as `(h·ω)²/6` — which reached about 6% there, above the 5% it was
     * being compared against. The spring was right: its analytic derivative agreed with the closed
     * form to four digits.
     *
     * So the step is 1ms and the tolerance is unchanged. **The tolerance is the standard of
     * acceptance; the step is the quality of the measurement**, and it was the measurement that
     * was inadequate. Widening the tolerance would have hidden the class of error this property
     * exists to catch. Deriving the step from the subject's own `ω` would have been worse still —
     * the property would then take its method from the thing it verifies.
     *
     * At 1ms the approximation error is near 0.01% for that spring and about 1.7% even at a
     * stiffness of 10 000. A faster solver than that raises the question *"is the oracle still
     * accurate enough?"*, and the answer belongs here rather than in the solver.
     *
     * The tolerance is relative because velocity spans orders of magnitude across a motion, and a
     * fixed epsilon would be either meaningless early or unmeetable late. Endpoints are skipped:
     * a central difference needs a neighbour on each side.
     */
    fun assertVelocityMatchesDerivative(name: String, sampler: MotionSampler) {
        val samples = DERIVATIVE_PROBES_NANOS.map { sampler.sampleAt(it) }
        for (i in 1 until samples.size - 1) {
            val seconds =
                (DERIVATIVE_PROBES_NANOS[i + 1] - DERIVATIVE_PROBES_NANOS[i - 1]) / 1_000_000_000f
            val numeric = (samples[i + 1].value - samples[i - 1].value) / seconds
            val reported = samples[i].velocity
            val scale = maxOf(abs(numeric), abs(reported), 1f)
            if (abs(numeric - reported) / scale > TOLERANCE) {
                fail(
                    "$name reported velocity $reported at ${DERIVATIVE_PROBES_NANOS[i]}ns, but " +
                        "its value changes at $numeric per second there"
                )
            }
        }
    }

    /**
     * Five percent, relative.
     *
     * Loose enough that a central difference over 10ms does not flag a curve for being curved,
     * tight enough to reject a velocity off by a constant factor. `ContractSelfTest` proves the
     * second half by construction: `WrongDerivativeSampler` is wrong by 2x and must be rejected.
     */
    private const val TOLERANCE = 0.05f
}

/**
 * Properties only a **closed-form** sampler can satisfy.
 *
 * The solver tier, and the first entry in it. Order-independence is genuinely valuable — it is
 * what makes seeking possible — but [MotionSampler] permits a stepped implementation that
 * integrates forward and cannot be asked to go back. Filing this alongside the universal
 * properties would turn the harness into a demand that every sampler be closed-form, which is
 * precisely the mis-filing the two-tier split exists to prevent.
 */
object ClosedFormSamplerContract {

    /** The same instant yields the same sample, whenever it is asked and in whatever order. */
    fun assertOrderIndependent(name: String, sampler: MotionSampler) {
        val forward = SamplerContract.PROBES_NANOS.map { it to sampler.sampleAt(it) }
        for ((t, expected) in forward.reversed()) {
            val actual = sampler.sampleAt(t)
            if (actual != expected) {
                fail("$name answered ${t}ns as $expected going forward and $actual coming back")
            }
        }
    }
}
