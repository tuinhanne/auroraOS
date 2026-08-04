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

import aurora.sdk.animation.DecaySpec
import aurora.sdk.animation.MotionSample
import aurora.sdk.animation.MotionSampler
import kotlin.math.exp

/**
 * Motion coasting to a stop under exponential friction, in closed form.
 *
 * ```
 * value = 1 - e^(-f·t)        velocity = f·e^(-f·t)
 * ```
 *
 * ## This adds no evidence, and that is why it is not this sprint's first task
 *
 * These are the same two expressions as `DecayTrajectory` in the test tree, which is the analytic
 * subject Sprint 06B.0 verified the contract against. Moving them from `tests/` to `runtime/`
 * produces nothing the contract does not already have — unlike `SpringSampler`, whose closed form
 * had two branches, a removable singularity and a cancellation, and which contradicted its spec
 * four times.
 *
 * `DecayTrajectory` therefore **stays** where it is. Deleting it would remove the evidence rather
 * than the duplication, and `DecaySamplerTest` asserts the two agree, since they are now two
 * copies of one expression.
 *
 * ## `v₀` does not appear here
 *
 * Normalised against its own total travel, a decay's shape is a function of friction and time
 * alone: initial velocity decides how far it goes, not how it goes. That cancellation is what lets
 * `samplerFor(spec)` keep its single parameter, and it is why the value-unit conversion lives in
 * `FlingFactory` rather than in this class. See ADR-008.
 */
class DecaySampler(spec: DecaySpec) : MotionSampler {

    private val friction = spec.friction

    override fun sampleAt(elapsedNanos: Long): MotionSample {
        val remaining = exp(-friction * elapsedNanos / NANOS_PER_SECOND)
        return MotionSample(value = 1f - remaining, velocity = friction * remaining)
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000f
    }
}
