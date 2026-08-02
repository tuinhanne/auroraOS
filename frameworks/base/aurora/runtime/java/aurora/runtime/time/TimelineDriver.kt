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

package aurora.runtime.time

import aurora.sdk.event.Disposable
import aurora.sdk.time.AuroraClock
import aurora.sdk.time.FrameCallback
import aurora.sdk.time.FrameScheduler
import aurora.sdk.time.Timeline
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs a [Timeline] against a clock and a frame scheduler.
 *
 * This is the whole of Sprint 05.5's time infrastructure wired together, and it is the proof
 * that the seams work: given a `TestClock` and a fake scheduler, a test drives a timeline
 * frame by frame on a host JVM and asserts the exact progress at each step, with no sleeping
 * and no device.
 *
 * Still no physics. The driver reports linear progress; shaping it is Sprint 06's job.
 *
 * ## Elapsed time comes from the frame, not from the clock
 *
 * Each callback carries the instant the frame is being composed for. Reading the clock inside
 * the callback instead would give every animation a slightly different idea of "now", and they
 * would drift apart over a long transition. The clock is read exactly once, to mark the start.
 */
class TimelineDriver(
    private val clock: AuroraClock,
    private val scheduler: FrameScheduler,
) {

    private val running = AtomicInteger(0)

    /** How many timelines are being driven. */
    val activeCount: Int
        get() = running.get()

    /**
     * Starts [timeline].
     *
     * @param onUpdate receives linear progress, 0..1, once per frame including the final one
     * @param onFinished true when the timeline ran to its end, false when it was disposed
     *     early. Called exactly once.
     * @return a handle; disposing it stops the timeline where it is
     */
    @JvmOverloads
    fun start(
        timeline: Timeline,
        onUpdate: (progress: Float) -> Unit,
        onFinished: (completed: Boolean) -> Unit = {},
    ): Disposable {
        val run = Run(timeline, onUpdate, onFinished)
        run.begin()
        return run
    }

    private inner class Run(
        private val timeline: Timeline,
        private val onUpdate: (Float) -> Unit,
        private val onFinished: (Boolean) -> Unit,
    ) : Disposable, FrameCallback {

        private val startNanos = clock.nowNanos()

        @Volatile
        private var pending: Disposable? = null

        @Volatile
        private var done = false

        override val isDisposed: Boolean
            get() = done

        fun begin() {
            running.incrementAndGet()
            pending = scheduler.postFrame(this)
        }

        override fun doFrame(frameTimeNanos: Long) {
            if (done) return
            val elapsed = frameTimeNanos - startNanos
            onUpdate(timeline.progressAt(elapsed))

            if (timeline.isFinishedAt(elapsed)) {
                finish(completed = true)
            } else {
                // Re-posted from inside the callback, one frame at a time. An animation that
                // ends simply stops asking, so there is no registration left behind to leak.
                pending = scheduler.postFrame(this)
            }
        }

        override fun dispose() {
            if (done) return
            finish(completed = false)
        }

        private fun finish(completed: Boolean) {
            if (done) return
            done = true
            pending?.dispose()
            pending = null
            running.decrementAndGet()
            onFinished(completed)
        }
    }
}
