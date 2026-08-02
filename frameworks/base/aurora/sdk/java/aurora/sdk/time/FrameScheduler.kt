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

import aurora.sdk.event.Disposable

/** Receives one display frame. */
fun interface FrameCallback {

    /**
     * Called once per frame.
     *
     * @param frameTimeNanos the time the frame is being composed for, on the same timebase as
     *     [AuroraClock.nowNanos]. Use this rather than reading the clock inside the callback:
     *     every callback in a frame gets the same value, so animations stay in step instead of
     *     drifting apart by the microseconds between their own reads.
     */
    fun doFrame(frameTimeNanos: Long)
}

/**
 * Requests callbacks aligned to the display's refresh.
 *
 * ## Why animation must not use a loop and a sleep
 *
 * A `while` loop with a sleep produces frames at times unrelated to when the display actually
 * refreshes. The result is judder that no amount of tuning the animation curve will fix,
 * because the problem is not the curve — it is that the frames are computed for the wrong
 * instants. It also burns power waking a core between refreshes for nothing.
 *
 * On device this is `Choreographer`. That class lives in `android.view`, which `aurora.sdk` and
 * `aurora.runtime` may not import, so it appears here as a seam: the platform implements it
 * with `Choreographer`, and a test implements it by hand.
 *
 * ## What that buys
 *
 * The entire animation engine — timeline, spring solver, composition — can then run on a host
 * JVM, driven frame by frame, with no device and no Android. Interruption, cancellation and
 * composition are exactly the behaviours that are hardest to test on a device and easiest to
 * test when frames are handed out one at a time by the test itself.
 */
interface FrameScheduler {

    /**
     * Requests a single callback on the next frame.
     *
     * One frame, not a subscription: an animation that wants to keep running re-posts from
     * inside its own callback. That way an animation which finishes simply stops posting,
     * and there is no way to leave a callback registered forever by forgetting to remove it.
     *
     * @return a handle that cancels the pending callback if disposed before it runs
     */
    fun postFrame(callback: FrameCallback): Disposable

    /**
     * Nominal nanoseconds between frames, for example 16_666_666 at 60Hz.
     *
     * Advisory. Never assume the real gap equalled this: a dropped frame makes it a multiple,
     * and a variable-refresh display changes it. Always measure from the [FrameCallback]
     * timestamps instead of accumulating this value, or the animation will drift.
     */
    val frameIntervalNanos: Long
}
