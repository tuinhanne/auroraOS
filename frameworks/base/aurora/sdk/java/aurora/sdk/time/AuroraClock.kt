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
 * Anything that can report a time on a nanosecond timebase.
 *
 * The root of the time model. Kept separate from [AuroraClock] because not every source of
 * time is a clock: a frame source hands out the instant a frame is composed for, which is a
 * legitimate time but is not something you can poll.
 */
interface TimeSource {

    /**
     * Nanoseconds since an arbitrary, fixed origin.
     *
     * Only differences are meaningful. The absolute value must never be persisted or compared
     * across processes.
     */
    fun nowNanos(): Long
}

/**
 * The only source of time anything in Aurora may read.
 *
 * ## RULE-006: monotonic, always
 *
 * Readings never decrease: `t0 <= t1 <= t2`. Every implementation must preserve this,
 * including the ones used only in tests.
 *
 * The reason is not purity. A test seam that can produce states production cannot produce
 * makes every test written against it a test of a world that does not exist. If `TestClock`
 * could rewind, code could be written — and proven correct — against behaviour the real clock
 * will never exhibit. So `rewind()` and `setTime()` do not exist anywhere. A test that needs a
 * different origin constructs a new clock.
 *
 * ## Monotonic, never wall-clock
 *
 * This interface deliberately cannot tell you the time of day. Wall-clock time moves
 * backwards when NTP corrects it or the user edits the date, and an animation measuring
 * elapsed time against it would jump or freeze. Anything that needs a calendar time gets it
 * elsewhere, and having to go elsewhere is the point: the two are not interchangeable and
 * mixing them is invisible in review.
 *
 * ## Why an interface
 *
 * So time can be driven. Testing that an animation is halfway through after 150ms otherwise
 * means sleeping 150ms and hoping the machine was not busy. Here a test advances by exactly
 * 150ms and asserts: no sleeps, no flakes, a suite in milliseconds.
 *
 * Implementations live in `aurora.runtime.time`; this layer only states the contract.
 */
interface AuroraClock : TimeSource {

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
