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

import aurora.sdk.animation.AnimationState
import aurora.sdk.animation.AnimationState.CANCELLED
import aurora.sdk.animation.AnimationState.COMPLETED
import aurora.sdk.animation.AnimationState.DISPOSED
import aurora.sdk.animation.AnimationState.IDLE
import aurora.sdk.animation.AnimationState.PAUSED
import aurora.sdk.animation.AnimationState.RUNNING
import aurora.sdk.animation.AnimationState.SCHEDULED

/**
 * What can happen to an animation.
 *
 * The alphabet of the machine, and deliberately not part of the SDK: RULE-010 gives the SDK
 * the nouns (the states a caller can observe) and leaves the runtime the verbs of its own
 * implementation. Seeking is absent because it moves an execution without changing its state.
 */
enum class AnimationEvent {

    /** A caller started the first execution. */
    PLAY,

    /** A frame arrived. */
    TICK,

    /** A caller held the animation. */
    PAUSE,

    /** A caller continued a held animation. */
    RESUME,

    /** A caller stopped the execution where it stood. */
    CANCEL,

    /** A caller began a new execution. */
    RESTART,

    /** The strategy reported that the motion ended. */
    FINISH,

    /** A caller finished with the handle for good. */
    DISPOSE,
}

/**
 * The animation lifecycle, as a pure function.
 *
 * No fields, no clock, no registry, no listener, no handle. Given a state and an event it
 * returns the next state or refuses, and it will answer the same way a million times running.
 * That is what makes the whole of RULE-009 reachable: if the lifecycle itself could drift,
 * nothing built on top of it could be deterministic.
 *
 * It is also why this is the piece with the most exhaustive test in the sprint -- fifty-six
 * cells, every one named.
 */
object AnimationStateMachine {

    /** Whether [event] is legal in [from]. Never throws. */
    @JvmStatic
    @JvmOverloads
    fun canTransition(
        from: AnimationState,
        event: AnimationEvent,
        pausedFrom: AnimationState = RUNNING,
    ): Boolean = nextOrNull(from, event, pausedFrom) != null

    /**
     * The state after [event].
     *
     * @param pausedFrom which state a [AnimationEvent.PAUSE] came from, so
     *     [AnimationEvent.RESUME] returns to it. Ignored for every other event.
     * @throws IllegalStateException when the event is not legal in [from]. Loudly, per
     *     RULE-003: a lifecycle call that quietly did nothing would leave the caller believing
     *     an animation was running when it was not.
     */
    @JvmStatic
    @JvmOverloads
    fun next(
        from: AnimationState,
        event: AnimationEvent,
        pausedFrom: AnimationState = RUNNING,
    ): AnimationState = nextOrNull(from, event, pausedFrom)
        ?: throw IllegalStateException(
            if (event == AnimationEvent.RESUME && from == PAUSED) {
                "RESUME from PAUSED is not legal with pausedFrom=$pausedFrom; a pause can only " +
                    "have come from SCHEDULED or RUNNING"
            } else {
                "$event is not legal in state $from"
            }
        )

    private fun nextOrNull(
        from: AnimationState,
        event: AnimationEvent,
        pausedFrom: AnimationState,
    ): AnimationState? = when (event) {

        // Legal everywhere, and idempotent. Teardown paths run more than once.
        AnimationEvent.DISPOSE -> DISPOSED

        AnimationEvent.PLAY -> if (from == IDLE) SCHEDULED else null

        // A tick starts a scheduled execution and keeps a running one running. Illegal
        // anywhere else: the registry does not tick a paused or resting handle, so a tick
        // arriving in those states means the engine lost track of something.
        AnimationEvent.TICK -> when (from) {
            SCHEDULED, RUNNING -> RUNNING
            else -> null
        }

        // Legal from SCHEDULED as well as RUNNING. Forbidding the former would make the
        // outcome of play() then pause() depend on whether a frame happened to arrive in
        // between, which is API behaviour varying with machine load.
        AnimationEvent.PAUSE -> when (from) {
            SCHEDULED, RUNNING, PAUSED -> PAUSED
            else -> null
        }

        // Only from PAUSED, and back to wherever the pause came from. Resuming continues an
        // execution; restarting begins one. A resting animation has no execution to continue.
        //
        // pausedFrom is validated rather than trusted. A pause can only have come from a state
        // that was about to advance, and returning anything else would let RESUME produce a
        // state no event ever transitioned to - handing back DISPOSED without DISPOSE having
        // been dispatched. The one caller cannot do this today; the machine does not rely on
        // that (RULE-003).
        AnimationEvent.RESUME -> when {
            from != PAUSED -> null
            pausedFrom != RUNNING && pausedFrom != SCHEDULED -> null
            else -> pausedFrom
        }

        AnimationEvent.CANCEL -> when (from) {
            IDLE, SCHEDULED, RUNNING, PAUSED -> CANCELLED
            COMPLETED, CANCELLED -> from
            DISPOSED -> null
        }

        // RULE-012: a resting handle is reusable. Only a disposed one is not.
        AnimationEvent.RESTART -> if (from == DISPOSED) null else SCHEDULED

        AnimationEvent.FINISH -> if (from == RUNNING) COMPLETED else null
    }
}
