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

import aurora.sdk.animation.MotionSample
import aurora.sdk.animation.MotionSampler
import aurora.sdk.animation.TimedSpec

/**
 * The only sampler Sprint 06A.5 ships: time decides position.
 *
 * Holds nothing. [aurora.sdk.time.Timeline] is already stateless and the interpolator is a pure
 * function, so a sample is derived from elapsed time on every call and never accumulated. Two
 * calls that reach the same elapsed time by different routes agree exactly, which is what stops
 * a dropped frame from drifting.
 *
 * ## Velocity
 *
 * A central finite difference around the sampled instant. It is a pure function of elapsed, so
 * determinism is unaffected, and unlike an analytic derivative it works for any [Interpolator] —
 * including an arbitrary Bézier that has no closed form to differentiate.
 *
 * The contract says only that velocity is the rate of change of value with respect to time. This
 * is one way to produce it, not the required way.
 */
class TimedSampler(private val spec: TimedSpec) : MotionSampler {

    override fun sampleAt(elapsedNanos: Long): MotionSample {
        val value = valueAt(elapsedNanos)
        // Central difference, clamped at zero so the first sample looks forward rather than
        // before the execution began.
        val before = valueAt(if (elapsedNanos < EPSILON_NANOS) 0L else elapsedNanos - EPSILON_NANOS)
        val after = valueAt(elapsedNanos + EPSILON_NANOS)
        val spanSeconds =
            (if (elapsedNanos < EPSILON_NANOS) elapsedNanos + EPSILON_NANOS else 2 * EPSILON_NANOS)
                .toDouble() / NANOS_PER_SECOND
        val velocity = if (spanSeconds == 0.0) 0f else ((after - before) / spanSeconds).toFloat()
        return MotionSample(value = value, velocity = velocity)
    }

    private fun valueAt(elapsedNanos: Long): Float =
        spec.interpolator.transform(spec.timeline.progressAt(elapsedNanos))

    private companion object {
        /** Half a millisecond: far below a frame, far above float noise at animation scale. */
        const val EPSILON_NANOS = 500_000L
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
