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

package aurora.sdk.animation

/**
 * Where an animation is in its lifecycle.
 *
 * ## Two kinds of ending
 *
 * [COMPLETED] and [CANCELLED] end an *execution*. [DISPOSED] ends the *handle*. The
 * distinction is RULE-012 and it is what lets Volume, Dynamic Island and Control Center
 * re-run one handle indefinitely instead of allocating a new one for every gesture: a
 * cancelled animation is resting, not dead, and [aurora.sdk.animation.AnimationHandle.restart]
 * starts a fresh execution on the same object.
 *
 * `resume()` is deliberately *not* legal from a resting state. Resuming continues an
 * execution; restarting begins another. Collapsing the two would make the API unable to say
 * which one a caller meant.
 *
 * ## The three predicates do not partition the states
 *
 * [IDLE] and [PAUSED] match none of them, deliberately. Neither is ticking and neither has
 * finished: a paused animation is live, resumable and seekable. Both are queried by direct
 * equality rather than through a predicate, so `else` in a chain of these three is not
 * "disposed" — it is "idle or paused", and treating it as disposed would strand a handle the
 * user is still holding.
 */
enum class AnimationState {

    /** Created, never played. The state a handle from `Animator.create` starts in. */
    IDLE,

    /** Registered with the engine, has not yet received a tick for this execution. */
    SCHEDULED,

    /**
     * Has received at least one tick and has not been held or ended.
     *
     * Not a promise that time is passing. Whether frames are arriving is a property of the
     * engine, not of the animation: `AnimationController.stop()` deliberately leaves in-flight
     * animations in this state rather than cancelling them, so that a display turning off does
     * not visibly reset the interface when it comes back. A handle in this state is advancing
     * *whenever the engine is running*.
     */
    RUNNING,

    /** Held. Time does not accumulate; see `ExecutionTimeline`. */
    PAUSED,

    /** The execution reached its end. Restartable. */
    COMPLETED,

    /** The execution was stopped where it stood, without jumping to the end. Restartable. */
    CANCELLED,

    /** The handle is finished for good. Nothing recovers from here. */
    DISPOSED;

    /** Receiving, or about to receive, ticks. */
    val isActive: Boolean
        get() = this == SCHEDULED || this == RUNNING

    /** The execution ended; the handle is still usable. RULE-012. */
    val isResting: Boolean
        get() = this == COMPLETED || this == CANCELLED

    /** [DISPOSED] only. The handle is dead. */
    val isTerminal: Boolean
        get() = this == DISPOSED
}
