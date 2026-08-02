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
 * The only source of time anything in Aurora may read.
 *
 * ## Monotonic, never wall-clock
 *
 * [nowNanos] counts from an arbitrary origin and never goes backwards. Wall-clock time does:
 * an NTP correction, a timezone change or the user editing the date can move it by hours, in
 * either direction. An animation that measured its elapsed time against wall-clock would jump
 * or freeze when that happened, and the bug would be unreproducible because it depends on
 * something outside the program.
 *
 * So this interface deliberately cannot tell you what time of day it is. Anything that needs
 * that — a notification timestamp, a clock widget — must get it elsewhere, and having to go
 * elsewhere is the point: the two kinds of time are not interchangeable and mixing them is a
 * class of bug that is very hard to see in review.
 *
 * ## Why an interface at all
 *
 * So that time can be *driven* in a test. With a real clock, testing that an animation is
 * halfway through after 150ms means sleeping for 150ms and hoping the machine was not busy.
 * With this, a test advances the clock by exactly 150ms and asserts. No sleeps, no flakes, and
 * a suite that runs in milliseconds instead of minutes.
 *
 * This is the same inversion as `AuroraDispatcher`: the runtime depends on a seam, and only
 * the platform knows what the real thing is.
 */
interface AuroraClock {

    /**
     * Nanoseconds since an arbitrary, fixed origin.
     *
     * Only differences between two readings are meaningful. The absolute value means nothing
     * and must never be persisted or compared across processes.
     */
    fun nowNanos(): Long

    /** [nowNanos] truncated to milliseconds. Convenience only; prefer nanoseconds internally. */
    fun nowMillis(): Long = nowNanos() / NANOS_PER_MILLI

    companion object {
        /** Nanoseconds in a millisecond. */
        const val NANOS_PER_MILLI: Long = 1_000_000L

        /** Nanoseconds in a second. */
        const val NANOS_PER_SECOND: Long = 1_000_000_000L

        /** Converts milliseconds to nanoseconds. */
        @JvmStatic
        fun millisToNanos(millis: Long): Long = millis * NANOS_PER_MILLI

        /** Converts nanoseconds to milliseconds, truncating. */
        @JvmStatic
        fun nanosToMillis(nanos: Long): Long = nanos / NANOS_PER_MILLI
    }
}

/**
 * The real monotonic clock, backed by [System.nanoTime].
 *
 * `System.nanoTime` rather than `currentTimeMillis` for the reason given on [AuroraClock]: it
 * is monotonic and unaffected by the user or by NTP.
 */
object RealtimeClock : AuroraClock {
    override fun nowNanos(): Long = System.nanoTime()
}
