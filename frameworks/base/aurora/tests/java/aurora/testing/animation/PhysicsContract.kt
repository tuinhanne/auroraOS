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
import aurora.sdk.animation.PhysicsSpec
import kotlin.math.abs
import org.junit.Assert.fail

/**
 * Properties a [PhysicsSpec] and its sampler must satisfy together.
 *
 * The **contract tier**: a failure on every sampler means the semantics are wrong rather than the
 * solver. A property only one family can satisfy belongs beside that family instead, and when
 * 06B.2 shows a decay cannot satisfy something a spring can, moving it there is the expected
 * outcome and not a defect in 06B.0.
 *
 * As with [SamplerContract], every assertion takes one ascending pass. [MotionSampler] permits an
 * implementation that integrates forward and cannot be asked to go back.
 */
object PhysicsContract {

    /** Three seconds at 10ms — long enough for a decay at the default friction to settle. */
    private val PROBES_NANOS: List<Long> = (0..300).map { it * 10_000_000L }

    /**
     * The completion metric never increases while the animation is still running.
     *
     * Bounded to the running region deliberately, and that bound is what makes the assertion
     * usable rather than merely true. A claim over the whole trajectory would have to hold where
     * the metric has fallen to 1e-9, and there float32 rounds in both directions — the same
     * arithmetic that made `0.048f - 0.032f` come out as `0.015999999` in Sprint 06A. Above the
     * threshold one ULP is around 1e-10, so no tolerance constant is needed and none is invented.
     *
     * Sampling stops at the threshold because that is where the engine stops: `isFinished` is
     * consulted each frame and the animation ends at the first true. What a sampler reports after
     * that is not observable, so the contract does not constrain it.
     */
    fun assertMetricNeverIncreases(name: String, spec: PhysicsSpec, sampler: MotionSampler) {
        var previous = Float.MAX_VALUE
        for (t in PROBES_NANOS) {
            val metric = spec.completionMetric(sampler.sampleAt(t))
            if (metric < spec.completionThreshold) return
            if (metric > previous) {
                fail("$name completion metric rose from $previous to $metric at ${t}ns")
            }
            previous = metric
        }
    }

    /**
     * The motion arrives.
     *
     * ## What this does not do
     *
     * Sprint 06B.0 introduced it believing it kept a decay's two halves honest — the travel
     * `v₀/f` computed by whoever builds the `Animation`, against the shape `1 - e^(-ft)` computed
     * by the sampler. **It cannot.** This reads normalised progress, which reaches 1 for any
     * friction, and the sampler is never given `to`. A caller using the wrong friction, the wrong
     * formula, or no division at all still produces a green run here.
     *
     * The crossing is a unit boundary, and this entire harness lives on the normalised side of
     * it. The assertion that would catch it takes a whole pipeline rather than a sampler, belongs
     * at `AnimationService.fling`, and does not exist yet.
     *
     * The allowance is ten thresholds rather than one, because a sampler is entitled to still be
     * a little short at the last probe — the contract is that it converges, not that it has
     * converged by any particular instant.
     */
    fun assertConvergesToOne(name: String, spec: PhysicsSpec, sampler: MotionSampler) {
        val settled = sampler.sampleAt(PROBES_NANOS.last()).value
        if (abs(1f - settled) > spec.completionThreshold * 10f) {
            fail("$name settled at $settled rather than at 1")
        }
    }
}
