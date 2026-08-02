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

package aurora.sdk.time

/**
 * Everything an animation is allowed to know about time, for one frame.
 *
 * ## RULE-008: consume time, never acquire it
 *
 * An animation is handed one of these. It must not call a clock, and it must not read
 * [System.nanoTime]. The difference sounds philosophical and is entirely practical:
 *
 * - **Determinism.** The same sequence of [FrameTime] values always produces the same output,
 *   so a failure can be replayed exactly instead of being chased.
 * - **Coherence.** Every animation in a frame is given the same [frameTimeNanos], so they stay
 *   in step. If each read a clock for itself they would drift apart by the microseconds
 *   between their reads, and long parallel transitions would visibly desynchronise.
 * - **Testing and benchmarking.** A test hands out frames at whatever spacing it likes,
 *   including pathological ones, without a device and without waiting.
 *
 * @param frameTimeNanos the instant this frame is being composed for, on [AuroraClock]'s
 *     timebase. Not "now": by the time an animation runs, "now" is already later.
 * @param deltaNanos time since the previous frame, or 0 on the first. Measured from real frame
 *     timestamps, never accumulated from a nominal interval — a dropped frame makes the real
 *     gap a multiple of it, and accumulating drifts.
 * @param frameIndex frames since the sequence began, useful for diagnostics and for spotting
 *     drops.
 */
data class FrameTime(
    val frameTimeNanos: Long,
    val deltaNanos: Long,
    val frameIndex: Long,
) {

    init {
        require(deltaNanos >= 0) { "deltaNanos must not be negative; time is monotonic" }
        require(frameIndex >= 0) { "frameIndex must not be negative" }
    }

    /** [deltaNanos] in milliseconds, as a fraction. */
    val deltaMillis: Double
        get() = deltaNanos.toDouble() / AuroraClock.NANOS_PER_MILLI

    /** [deltaNanos] in seconds. The form physics integration wants. */
    val deltaSeconds: Float
        get() = (deltaNanos.toDouble() / AuroraClock.NANOS_PER_SECOND).toFloat()

    /** Whether this is the first frame of a sequence, which has no previous frame to measure from. */
    val isFirstFrame: Boolean
        get() = frameIndex == 0L

    /**
     * The frame that follows this one at [nextFrameTimeNanos].
     *
     * Keeps the delta and the index consistent, so callers cannot accidentally produce a
     * sequence whose delta disagrees with its timestamps.
     */
    fun next(nextFrameTimeNanos: Long): FrameTime = FrameTime(
        frameTimeNanos = nextFrameTimeNanos,
        deltaNanos = nextFrameTimeNanos - frameTimeNanos,
        frameIndex = frameIndex + 1,
    )

    companion object {
        /** The first frame of a sequence: no previous frame, so no delta. */
        @JvmStatic
        fun first(frameTimeNanos: Long): FrameTime =
            FrameTime(frameTimeNanos = frameTimeNanos, deltaNanos = 0L, frameIndex = 0L)
    }
}
