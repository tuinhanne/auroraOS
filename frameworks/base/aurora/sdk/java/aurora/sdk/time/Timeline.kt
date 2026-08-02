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
 * Maps elapsed time onto normalised progress. Delay, duration, repeat, reverse and seek.
 *
 * ## What this deliberately does not do
 *
 * No easing, no springs, no interpolation of values. This answers exactly one question — *how
 * far through are we?* — and returns a linear 0..1. Shaping that number is the animation
 * engine's job, and keeping the two apart is what makes the time behaviour testable on its
 * own. Physics bugs and timing bugs look identical from the outside, so they are worth being
 * able to rule out separately.
 *
 * ## Stateless, so seeking is free
 *
 * Nothing here remembers where it is. [progressAt] is a pure function of elapsed time, so
 * scrubbing backwards, jumping to the end, or replaying a frame all work with no extra
 * machinery. A stateful timeline would need an explicit seek that resets internal counters,
 * and that is exactly where off-by-one errors around repeat boundaries live.
 *
 * @param durationNanos length of one iteration. Zero means the timeline is complete the
 *     instant its delay elapses.
 * @param delayNanos time before the first iteration starts
 * @param repeatCount extra iterations after the first: 0 plays once, 2 plays three times,
 *     [REPEAT_INFINITE] never ends
 * @param reverseOnRepeat whether odd iterations run backwards, giving a ping-pong
 */
data class Timeline(
    val durationNanos: Long,
    val delayNanos: Long = 0L,
    val repeatCount: Int = 0,
    val reverseOnRepeat: Boolean = false,
) {

    init {
        require(durationNanos >= 0) { "durationNanos must not be negative" }
        require(delayNanos >= 0) { "delayNanos must not be negative" }
        require(repeatCount >= REPEAT_INFINITE) { "repeatCount must be >= $REPEAT_INFINITE" }
    }

    /** Whether this timeline runs forever. */
    val isInfinite: Boolean get() = repeatCount == REPEAT_INFINITE

    /**
     * Total length including delay and every repeat, or [REPEAT_INFINITE] when [isInfinite].
     */
    val totalNanos: Long
        get() = if (isInfinite) REPEAT_INFINITE.toLong()
        else delayNanos + durationNanos * (repeatCount + 1L)

    /**
     * Which iteration [elapsedNanos] falls in, counting from 0.
     *
     * Returns 0 during the delay, and the final iteration index once finished.
     */
    fun iterationAt(elapsedNanos: Long): Int {
        val active = elapsedNanos - delayNanos
        if (active <= 0L || durationNanos == 0L) return 0
        val raw = active / durationNanos
        if (isInfinite) return raw.toInt()
        return if (raw > repeatCount) repeatCount else raw.toInt()
    }

    /**
     * Linear progress at [elapsedNanos], clamped to 0..1.
     *
     * 0 throughout the delay. Once finished, holds the value the last iteration ended on,
     * which is 0 rather than 1 when [reverseOnRepeat] made that iteration run backwards.
     */
    fun progressAt(elapsedNanos: Long): Float {
        val active = elapsedNanos - delayNanos
        // Strictly before the delay ends, nothing has started.
        if (active < 0L) return 0f
        // A zero-duration timeline is complete the instant its delay elapses, so this has to
        // be decided before the `active == 0` case below, which belongs to timelines that
        // have a duration and are only just starting.
        if (durationNanos == 0L) return endValue()
        if (active == 0L) return 0f

        if (!isInfinite && active >= durationNanos * (repeatCount + 1L)) return endValue()

        val iteration = (active / durationNanos).toInt()
        val within = (active % durationNanos).toFloat() / durationNanos.toFloat()
        return if (reverseOnRepeat && iteration % 2 == 1) 1f - within else within
    }

    /** Whether the timeline has run out at [elapsedNanos]. Always false when [isInfinite]. */
    fun isFinishedAt(elapsedNanos: Long): Boolean {
        if (isInfinite) return false
        return elapsedNanos >= totalNanos
    }

    /** Value held after the last iteration completes. */
    private fun endValue(): Float =
        if (reverseOnRepeat && repeatCount % 2 == 1) 0f else 1f

    companion object {
        /** Passed as `repeatCount` to run forever. */
        const val REPEAT_INFINITE: Int = -1

        /** A timeline of [millis] milliseconds, played once. */
        @JvmStatic
        fun ofMillis(millis: Long): Timeline =
            Timeline(durationNanos = AuroraClock.millisToNanos(millis))
    }
}
