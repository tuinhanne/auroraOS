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
import aurora.sdk.animation.SpringSpec
import aurora.sdk.design.MotionTokens
import aurora.sdk.design.Spring

/**
 * Builds the `Animation` for a spring released from a gesture.
 *
 * The simplest of the three callers, and deliberately the first: its target is **supplied**, so it
 * involves no derivation and no selection, and it isolates the integration invariant from every
 * question about how `to` is obtained.
 *
 * That is what made it the sprint's refutation point. If a single invariant could not cover the
 * case where `to` is simply handed over, nothing later would rescue the claim that one covers all
 * three. It could — but stating it required the assertion to stop naming `friction`, a quantity
 * springs do not have. See `IntegrationContract`.
 */
object SpringFactory {

    /**
     * A spring from [from] to [to], continuing a gesture that ended at [gestureVelocity].
     *
     * The unit boundary is the one division here. Above it everything is in value units — pixels
     * per second — and below it the sampler works in normalised progress, where velocity is
     * measured in units of the whole travel per second.
     *
     * ## Why the threshold is a parameter
     *
     * Added by Task 3, and not for a spring's own sake — every direct caller was happy with the
     * default. A snap **becomes** a spring once its target is chosen, so `SnapFactory` builds its
     * animation through here; without this, a `SnapSpec.completionThreshold` a caller had set would
     * be dropped on the way and the motion would settle at some other moment, with nothing to
     * report it. That is the same shape as the unit-boundary bug this whole layer exists to catch,
     * one field over.
     *
     * The default is read from [SpringSpec] rather than written again here, so the two cannot
     * drift into disagreeing about what a caller who says nothing has asked for.
     *
     * @throws IllegalArgumentException if [to] equals [from]. A spring with nowhere to go has no
     *     travel to normalise by, and dividing would produce an infinity that reaches the screen
     *     as a view that stops drawing. `DecaySpec` rejects the analogous case at construction;
     *     here the zero range is the caller's to avoid, so the caller is told.
     */
    fun springTo(
        name: String,
        from: Float,
        to: Float,
        gestureVelocity: Float,
        spring: Spring = MotionTokens.SPRING_GENTLE,
        completionThreshold: Float = SpringSpec().completionThreshold,
    ): Animation {
        require(to != from) {
            "a spring from $from to $to has no travel to normalise a velocity by"
        }
        val travel = to - from
        return Animation(
            name = name,
            spec = SpringSpec(
                spring = spring,
                initialVelocity = gestureVelocity / travel,
                completionThreshold = completionThreshold,
            ),
            from = from,
            to = to,
        )
    }
}
