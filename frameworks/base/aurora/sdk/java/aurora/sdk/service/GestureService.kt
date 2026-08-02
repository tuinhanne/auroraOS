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

/** Screen edge a gesture starts from. */
enum class GestureEdge { LEFT, RIGHT, TOP, BOTTOM }

/** What stage of its life a gesture is at. */
enum class GesturePhase {
    /** The finger crossed the activation threshold. */
    BEGAN,

    /** The finger moved while the gesture was active. */
    CHANGED,

    /** The finger lifted and the gesture completed. */
    ENDED,

    /** The system took the gesture away, for example because a call arrived. */
    CANCELLED,
}

/**
 * One sample of an in-flight gesture.
 *
 * Coordinates and distances are in pixels; [velocity] is pixels per second.
 *
 * [velocity] is carried because it is the input a spring needs to continue the motion after
 * the finger lifts. Dropping it and animating from rest is what makes a release feel detached
 * from the swipe that caused it.
 *
 * [progress] is the fraction of the way to the gesture's completion threshold, clamped to
 * 0..1, so callers can drive an animation directly from it without repeating the arithmetic.
 */
data class GestureSample(
    val phase: GesturePhase,
    val x: Float,
    val y: Float,
    val velocity: Float,
    val progress: Float,
)

/**
 * Routes system-level gestures.
 *
 * Recognition itself belongs to the platform; this is the contract through which features
 * subscribe to it.
 *
 * ## Conflict is the hard part
 *
 * Several things want the same swipe: back navigation, the app's own content, and any overlay
 * on screen. [registerEdgeGesture] takes a priority so that ordering is declared rather than
 * decided by whichever component happened to register first.
 */
interface GestureService : AuroraService {

    /**
     * Subscribes to swipes starting at [edge].
     *
     * @param priority higher wins when several handlers want the same gesture
     * @param handler receives every phase of the gesture; return true to consume it and stop
     *     it reaching lower-priority handlers
     * @return a token for [unregister]
     */
    fun registerEdgeGesture(
        edge: GestureEdge,
        priority: Int,
        handler: (sample: GestureSample) -> Boolean,
    ): Long

    /** Removes a handler registered earlier. Safe to call with a token already removed. */
    fun unregister(token: Long)

    /**
     * Distance in pixels the finger must travel from the edge before a gesture activates.
     *
     * Exposed because it varies with screen size and with how aggressively the device claims
     * edge swipes, and callers driving their own preview need the same number the recogniser
     * uses.
     */
    fun activationThreshold(edge: GestureEdge): Float

    /** Whether any gesture is in flight. */
    val isGestureActive: Boolean
}
