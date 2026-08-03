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
     * Two seconds at 10ms, ascending.
     *
     * The spacing is deliberately not any sampler's internal step. `TimedSampler` differentiates
     * with `EPSILON_NANOS = 500_000L`; checking it at the same step with the same method would
     * compare a computation against itself and pass for every input (RULE-016).
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
     * Reported velocity is the derivative of reported value.
     *
     * The comparison is a central difference over the probe spacing — 10ms, twenty times
     * `TimedSampler`'s own 0.5ms — computed from samples already collected in the single forward
     * pass. Using the sampler's own step and its own method would make this a tautology; using a
     * different one means a sampler whose velocity is genuinely the derivative still agrees,
     * while one that reports something else does not.
     *
     * The tolerance is relative because velocity spans orders of magnitude across a motion, and a
     * fixed epsilon would be either meaningless early or unmeetable late. Endpoints are skipped:
     * a central difference needs a neighbour on each side.
     */
    fun assertVelocityMatchesDerivative(name: String, sampler: MotionSampler) {
        val samples = scan(sampler)
        for (i in 1 until samples.size - 1) {
            val seconds = (PROBES_NANOS[i + 1] - PROBES_NANOS[i - 1]) / 1_000_000_000f
            val numeric = (samples[i + 1].value - samples[i - 1].value) / seconds
            val reported = samples[i].velocity
            val scale = maxOf(abs(numeric), abs(reported), 1f)
            if (abs(numeric - reported) / scale > TOLERANCE) {
                fail(
                    "$name reported velocity $reported at ${PROBES_NANOS[i]}ns, but its value " +
                        "changes at $numeric per second there"
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
