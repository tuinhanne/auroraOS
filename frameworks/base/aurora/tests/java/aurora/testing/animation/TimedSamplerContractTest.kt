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

import aurora.runtime.animation.TimedSampler
import aurora.sdk.animation.Interpolator
import aurora.sdk.animation.TimedSpec
import aurora.sdk.time.Timeline
import org.junit.Test

/**
 * The harness pointed at real code.
 *
 * `TimedSampler` is the only `MotionSampler` that exists in Sprint 06B.0, so it is the only
 * production subject the contract has. The physics tier has none until 06B.1, which is why its
 * properties are carried by fixtures instead.
 *
 * It is a closed-form sampler — it reads `Timeline` and computes, holding no integration state —
 * so it is held to [ClosedFormSamplerContract] as well as the universal properties.
 */
class TimedSamplerContractTest {

    private fun spec(interpolator: Interpolator = Interpolator.LINEAR) = TimedSpec(
        timeline = Timeline(durationNanos = ONE_SECOND),
        interpolator = interpolator,
    )

    @Test
    fun aTimedSamplerSatisfiesTheSamplerContract() {
        SamplerContract.assertFinite(NAME, TimedSampler(spec()))
        SamplerContract.assertDeterministic(NAME) { TimedSampler(spec()) }
        SamplerContract.assertVelocityMatchesDerivative(NAME, TimedSampler(spec()))
    }

    @Test
    fun aTimedSamplerCanBeAskedInAnyOrder() {
        ClosedFormSamplerContract.assertOrderIndependent(NAME, TimedSampler(spec()))
    }

    /**
     * A repeating timeline is the shape most likely to break a derivative check, because progress
     * falls from 1 back to 0 at every iteration boundary — a step, whose central difference is
     * enormous and whose reported velocity is not.
     *
     * It is checked here rather than left out because leaving it out is how the interesting case
     * goes unexercised. If this fails at the boundaries, the finding is that a discontinuous
     * value has no derivative there, and the property needs to say so — do not widen the
     * tolerance until it passes.
     */
    @Test
    fun aRepeatingTimedSamplerIsFiniteAndDeterministic() {
        val repeating = TimedSpec(
            timeline = Timeline(durationNanos = ONE_SECOND / 2, repeatCount = 3),
            interpolator = Interpolator.LINEAR,
        )
        SamplerContract.assertFinite("$NAME/repeating", TimedSampler(repeating))
        SamplerContract.assertDeterministic("$NAME/repeating") { TimedSampler(repeating) }
    }

    private companion object {
        const val NAME = "TimedSampler"
        const val ONE_SECOND = 1_000_000_000L
    }
}
