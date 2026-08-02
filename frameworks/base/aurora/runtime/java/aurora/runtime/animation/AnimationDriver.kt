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

import aurora.sdk.animation.AnimationController
import aurora.sdk.event.Disposable
import aurora.sdk.time.FrameCallback
import aurora.sdk.time.FrameScheduler
import aurora.sdk.time.FrameTime

/**
 * The single frame callback for the whole engine.
 *
 * ## RULE-011
 *
 * One [FrameCallback] is posted per frame. One [FrameTime] is built from it. That same
 * instance reaches every animation, so twenty animations in a Dynamic Island transition
 * cannot drift apart — not because they are careful, but because there is only one timestamp
 * in existence for the frame. See ADR-004.
 *
 * ## Waking and sleeping
 *
 * The driver stops posting when the registry empties, and the registry wakes it when
 * something is added. A per-animation driver got this for free, since each run simply stopped
 * re-posting for itself; a batched driver has to do it deliberately or an idle engine wakes a
 * core at every display refresh forever.
 *
 * ## What this class is not allowed to know
 *
 * Nothing about `Choreographer`, threads or loopers. It talks to a [FrameScheduler], which on
 * a host is driven by hand and on device will be implemented in `aurora.platform` in Sprint
 * 08. That seam is why the entire engine is testable with no device.
 */
class AnimationDriver(
    private val scheduler: FrameScheduler,
    private val controller: AnimationController,
    private val registry: AnimationRegistry,
) : FrameCallback {

    private var pending: Disposable? = null
    private var lastFrameNanos: Long = UNSET
    private var nextFrameIndex: Long = 0L
    private var started: Boolean = false

    /** Starts the engine and begins asking for frames when there is anything to advance. */
    fun start() {
        if (started) return
        started = true
        registry.onWake = { postIfNeeded() }
        controller.start()
        postIfNeeded()
    }

    /** Stops the engine and cancels any pending frame request. */
    fun stop() {
        if (!started) return
        started = false
        registry.onWake = null
        pending?.dispose()
        pending = null
        controller.stop()
    }

    override fun doFrame(frameTimeNanos: Long) {
        pending = null

        val frameTime =
            if (lastFrameNanos == UNSET) {
                FrameTime.first(frameTimeNanos)
            } else {
                FrameTime(
                    frameTimeNanos = frameTimeNanos,
                    deltaNanos = frameTimeNanos - lastFrameNanos,
                    frameIndex = nextFrameIndex,
                )
            }
        lastFrameNanos = frameTimeNanos
        nextFrameIndex = frameTime.frameIndex + 1

        controller.tick(frameTime)

        // After the frame, so animations started from a listener are already committed and
        // an engine that just emptied stops here rather than posting one wasted frame.
        postIfNeeded()
    }

    private fun postIfNeeded() {
        if (!started) return
        if (pending != null) return
        if (registry.size == 0) return
        pending = scheduler.postFrame(this)
    }

    private companion object {
        /** No frame delivered yet. Not zero, which is a legitimate frame timestamp. */
        const val UNSET: Long = Long.MIN_VALUE
    }
}
