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

import aurora.sdk.design.Easing
import aurora.sdk.design.Spring

/**
 * A running animation, from the caller's point of view.
 *
 * Deliberately minimal: the caller needs to know whether it is still going, be able to stop
 * it, and be told when it ends. Everything else is the engine's business.
 */
interface AnimationHandle {

    /** Whether the animation is still producing values. */
    val isRunning: Boolean

    /**
     * Stops the animation where it is.
     *
     * Does not jump to the end value. An animation cancelled mid-flight should stay where the
     * user last saw it, or the interface appears to teleport.
     */
    fun cancel()

    /**
     * Registers a callback for when the animation finishes.
     *
     * @param listener receives true if the animation reached its target, false if it was
     *     cancelled or interrupted
     */
    fun onFinished(listener: (completed: Boolean) -> Unit)
}

/**
 * Drives animations.
 *
 * The engine behind this arrives in Sprint 06; this contract exists so callers can be written
 * against it first.
 *
 * ## Interruption is the point
 *
 * Every method here takes a *current value* rather than assuming a start of zero. Gesture-
 * driven motion is interrupted constantly — a swipe reverses, a second touch lands mid-flight
 * — and an animator that restarts from a fixed origin makes the interface visibly snap. The
 * [springTo] overload that takes an initial velocity exists for exactly this reason: it lets a
 * release continue the motion the finger was already making.
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
