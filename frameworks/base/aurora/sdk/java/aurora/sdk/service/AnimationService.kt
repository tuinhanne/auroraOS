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

package aurora.sdk.service

import aurora.sdk.animation.AnimationHandle
import aurora.sdk.design.Easing
import aurora.sdk.design.Spring

/**
 * Drives animations.
 *
 * A convenience facade over [aurora.sdk.animation.Animator] for the two shapes callers reach
 * for most. Anything needing pause, resume, restart, seek or lifecycle observation should use
 * the animator directly; this exists so that the common case is one call.
 *
 * ## Interruption is the point
 *
 * Every method here takes a *current value* rather than assuming a start of zero. Gesture-
 * driven motion is interrupted constantly — a swipe reverses, a second touch lands mid-flight
 * — and an animator that restarts from a fixed origin makes the interface visibly snap. The
 * [springTo] overload that takes an initial velocity exists for exactly this reason: it lets a
 * release continue the motion the finger was already making.
 *
 * ## Availability
 *
 * [springTo] cannot be satisfied until Sprint 06B adds the spring solver. An implementation
 * arriving before then must reject it rather than silently substituting a timed curve, since
 * a spring quietly replaced by an ease is exactly the kind of difference nobody notices in
 * review and everybody feels on device.
 */
interface AnimationService : AuroraService {

    /**
     * Animates from [from] to [to] over [durationMs], shaped by [easing].
     *
     * Use this for motion the system initiates. For anything continuing a gesture, prefer
     * [springTo].
     *
     * @param onUpdate called with each intermediate value
     */
    fun animateTo(
        from: Float,
        to: Float,
        durationMs: Int,
        easing: Easing,
        onUpdate: (value: Float) -> Unit,
    ): AnimationHandle

    /**
     * Animates to [to] using spring physics, starting from [from] with [initialVelocity].
     *
     * Passing the velocity the gesture ended with is what makes a release feel continuous
     * rather than restarted. Units are value-per-second in the same space as [from] and [to].
     */
    fun springTo(
        from: Float,
        to: Float,
        initialVelocity: Float,
        spring: Spring,
        onUpdate: (value: Float) -> Unit,
    ): AnimationHandle

    /** Cancels every animation this service is currently driving. */
    fun cancelAll()

    /** How many animations are running. Intended for diagnostics and tests. */
    val activeCount: Int
}
