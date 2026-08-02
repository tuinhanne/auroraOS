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

/**
 * The time base of one execution.
 *
 * Answers exactly one question -- *how long has this execution been running?* -- and leaves
 * *how far through is it* to an `AnimationStrategy`. That split is ADR-006, and it is what
 * lets Sprint 06B add spring and decay solvers without opening this file.
 *
 * ## No clock, anywhere
 *
 * Elapsed time is `frameTimeNanos - origin`, where the origin comes from the first frame this
 * execution was given. Nothing here reads `AuroraClock`, and nothing can: there is no clock
 * to read (RULE-008). A pause is measured in frame timestamps too, so a test that hands out
 * frames a simulated hour apart gets exactly the behaviour the device would.
 *
 * ## Two things it refuses
 *
 * It will not advance while paused, and it will not accept a frame time earlier than the last
 * one it saw. Neither should ever happen: the state machine makes `TICK` illegal from `PAUSED`,
 * and `FrameTime` is built from a monotonic source. Both are checked anyway, because the symptom
 * of either would otherwise be a silently wrong elapsed time -- a number that looks plausible and
 * is not -- rather than a failure anyone would notice (RULE-003).
 *
 * ## Why seeking while paused needs no special handling
 *
 * A paused execution is not ticked, so [lastFrameNanos] stays frozen at the frame the pause
 * happened on, which means `pausedAtFrameNanos == lastFrameNanos` for as long as a pause is in
 * play. Seeking then moves the origin relative to that frozen frame, and the resume shift
 * cancels it out exactly.
 *
 * Not thread safe, and does not need to be: one execution is only ever advanced from the
 * frame that is currently being processed.
 */
class ExecutionTimeline {

    private var originNanos: Long = 0L
    private var lastFrameNanos: Long = UNSET
    private var pausedAtFrameNanos: Long = UNSET
    private var pendingResume: Boolean = false
    private var seekTargetNanos: Long = 0L
    private var started: Boolean = false
    private var paused: Boolean = false

    /** Elapsed time as of the last [advanceTo] or [seekTo]. */
    var elapsedNanos: Long = 0L
        private set

    /** Whether this execution has been given a frame yet. */
    val hasStarted: Boolean
        get() = started

    /**
     * Advances to a frame and returns the elapsed time for it.
     *
     * @param frameTimeNanos the instant the frame is being composed for, from [FrameTime].
     *     Never a clock reading.
     */
    fun advanceTo(frameTimeNanos: Long): Long {
        check(!paused) {
            "a paused execution must not be ticked. The state machine makes TICK illegal from " +
                "PAUSED, so reaching here means the caller advanced a handle it should have " +
                "skipped - and the symptom would otherwise be a silently wrong elapsed time " +
                "rather than a failure (RULE-003)."
        }
        require(!started || frameTimeNanos >= lastFrameNanos) {
            "frame time must not go backwards: $frameTimeNanos after $lastFrameNanos (RULE-006). " +
                "FrameTime guards this at construction; this class takes a raw Long and so has " +
                "to guard it itself."
        }
        if (!started) {
            started = true
            // A seek before the first frame chooses where the execution begins.
            originNanos = frameTimeNanos - seekTargetNanos
        } else if (pendingResume) {
            // Shift the origin by exactly the time that passed while paused, so the animation
            // continues instead of jumping forward by the length of the pause.
            originNanos += frameTimeNanos - pausedAtFrameNanos
            pendingResume = false
            pausedAtFrameNanos = UNSET
        }
        lastFrameNanos = frameTimeNanos
        elapsedNanos = frameTimeNanos - originNanos
        return elapsedNanos
    }

    /** Holds the execution. Records where to resume from. */
    fun pause() {
        if (started) pausedAtFrameNanos = lastFrameNanos
        pendingResume = false
        paused = true
    }

    /**
     * Continues the execution.
     *
     * The shift is applied on the next frame, because that is the first moment the new frame
     * time is known. Pausing before the first frame leaves nothing to shift.
     */
    fun resume() {
        pendingResume = started && pausedAtFrameNanos != UNSET
        paused = false
    }

    /**
     * Moves the execution to [elapsedTargetNanos].
     *
     * Moving backwards is allowed. RULE-006 forbids a *clock* going backwards; this is a
     * position, and moving a position back is what scrubbing means.
     */
    fun seekTo(elapsedTargetNanos: Long) {
        require(elapsedTargetNanos >= 0) {
            "cannot position an execution before it began: $elapsedTargetNanos"
        }
        if (started) {
            originNanos = lastFrameNanos - elapsedTargetNanos
        } else {
            seekTargetNanos = elapsedTargetNanos
        }
        elapsedNanos = elapsedTargetNanos
    }

    /** Returns to the state of a fresh execution. Called by restart. */
    fun reset() {
        originNanos = 0L
        lastFrameNanos = UNSET
        pausedAtFrameNanos = UNSET
        pendingResume = false
        seekTargetNanos = 0L
        started = false
        elapsedNanos = 0L
        paused = false
    }

    private companion object {
        /** No frame seen. Not zero, which is a legitimate frame timestamp. */
        const val UNSET = Long.MIN_VALUE
    }
}
