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
import aurora.sdk.animation.DecaySpec

/**
 * Builds the `Animation` for a fling, and is the only place a decay's target is inferred.
 *
 * ## Why this is a factory rather than a service method
 *
 * Sprint 06B.2 named its subject *the first pipeline whose target is inferred rather than
 * supplied*, deliberately without a class name. `AnimationService.fling` was the expected home,
 * but `AnimationService` has no implementing class at all — `springTo` was declared in 06A and
 * never implemented — so putting it there would have meant building a service, a controller and
 * callback wiring, none of which is what this sprint is about. The definition survived the change
 * of address; the address was never the subject.
 *
 * ## The unit boundary lives here
 *
 * Above this function everything is in **value units**: a gesture releasing at 800 pixels per
 * second. Below it everything is in **normalised progress**. Those are the two halves of one
 * friction model, and if they disagree the animation still runs, still looks smooth, and stops
 * somewhere other than its target with nothing to report it.
 *
 * Neither contract tier can see that, because both take a `MotionSampler` and work entirely on the
 * normalised side. `IntegrationContract.assertInferredTravelReturnsTheVelocity` is what observes
 * it, and it was shown able to reject a caller that gets this wrong before this function existed.
 */
object FlingFactory {

    /**
     * A decay released at [gestureVelocity], in the same units as [from].
     *
     * Two conversions happen here and they must be exact inverses of each other:
     *
     * ```
     * to               = from + spec.restingDisplacement(v)       value units
     * initialVelocity  = v / (to - from)  =  friction             normalised
     * ```
     *
     * The second is written as `spec.friction` rather than as the division it equals. That is not
     * a shortcut: `v/(v/f)` **is** `f` identically, so performing the division would introduce a
     * rounding error and a zero case in exchange for nothing. Sprint 06B.0 proved the identity;
     * this is the first caller to depend on it.
     *
     * @throws IllegalArgumentException if [gestureVelocity] is zero — such a fling travels
     *     nowhere, so `to` would equal `from` and there is no animation to run. `DecaySpec`
     *     rejects it too; saying so here means the caller learns it at the level they called.
     */
    fun fling(
        name: String,
        from: Float,
        gestureVelocity: Float,
        spec: DecaySpec,
    ): Animation {
        require(gestureVelocity != 0f) {
            "a fling released at rest travels nowhere; there is nothing to animate"
        }
        val travel = spec.restingDisplacement(gestureVelocity)
        return Animation(
            name = name,
            spec = spec.copy(initialVelocity = spec.friction),
            from = from,
            to = from + travel,
        )
    }
}
