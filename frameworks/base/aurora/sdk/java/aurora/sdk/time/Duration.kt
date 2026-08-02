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
 * A length of time, stored in nanoseconds.
 *
 * ## Why a type instead of a `Long`
 *
 * Raw numbers do not carry their unit. `animate(300)` is milliseconds in one API and
 * nanoseconds in the next, and the bug it causes is a factor of a million — either an
 * animation that appears instant or one that never finishes. Neither looks like a unit error
 * when you read the call site.
 *
 * Nanoseconds internally because that is what the frame timestamps are in, so no conversion
 * happens on the hot path. Construct with [ofMillis] and read back with [inMillis] where
 * milliseconds are the natural unit for a human.
 *
 * Immutable and comparable. Negative durations are allowed: subtracting two instants can
 * legitimately give one, and rejecting it here would only push the check outward.
 */
data class Duration(val nanos: Long) : Comparable<Duration> {

    /** Whole milliseconds, truncated. */
    val inMillis: Long get() = nanos / AuroraClock.NANOS_PER_MILLI

    /** Fractional milliseconds. */
    val inMillisDouble: Double get() = nanos.toDouble() / AuroraClock.NANOS_PER_MILLI

    /** Fractional seconds. The form physics integration wants. */
    val inSeconds: Float get() = (nanos.toDouble() / AuroraClock.NANOS_PER_SECOND).toFloat()

    /** Whether this duration is zero or negative. */
    val isNotPositive: Boolean get() = nanos <= 0L

    operator fun plus(other: Duration): Duration = Duration(nanos + other.nanos)

    operator fun minus(other: Duration): Duration = Duration(nanos - other.nanos)

    operator fun times(factor: Int): Duration = Duration(nanos * factor)

    operator fun times(factor: Float): Duration = Duration((nanos * factor).toLong())

    override fun compareTo(other: Duration): Int = nanos.compareTo(other.nanos)

    override fun toString(): String = when {
        nanos == 0L -> "0ms"
        kotlin.math.abs(nanos) < AuroraClock.NANOS_PER_MILLI -> "${nanos}ns"
        else -> "${inMillisDouble}ms"
    }

    companion object {
        /** No time at all. */
        @JvmField
        val ZERO = Duration(0L)

        /** From nanoseconds. */
        @JvmStatic
        fun ofNanos(nanos: Long): Duration = Duration(nanos)

        /** From milliseconds. */
        @JvmStatic
        fun ofMillis(millis: Long): Duration = Duration(millis * AuroraClock.NANOS_PER_MILLI)

        /** From seconds. */
        @JvmStatic
        fun ofSeconds(seconds: Double): Duration =
            Duration((seconds * AuroraClock.NANOS_PER_SECOND).toLong())

        /** The gap between two readings on the same timebase. */
        @JvmStatic
        fun between(startNanos: Long, endNanos: Long): Duration = Duration(endNanos - startNanos)
    }
}
