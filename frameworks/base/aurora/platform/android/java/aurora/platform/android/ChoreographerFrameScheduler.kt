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

package aurora.platform.android

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Looper
import android.view.Choreographer
import android.view.Display
import aurora.sdk.event.Disposable
import aurora.sdk.time.FrameCallback
import aurora.sdk.time.FrameScheduler

/**
 * `Choreographer`, as a [FrameScheduler].
 *
 * ## The whole adapter, and it really is one
 *
 * `README.md` has claimed since Sprint 02 that this would be "an adapter rather than a rework", and
 * Sprint 08 Task 1 checked rather than inherited the claim — three of the four things that same
 * README entry said about Sprint 03 turned out to be wrong. This one held. `AnimationDriver`
 * computes `deltaNanos`, numbers its own frames, handles the first frame and stops posting when
 * nothing is running, so a frame source has one job: hand over the timestamp the platform gives it.
 *
 * The `ChoreographerAnimationDriver` the README also named is **not needed and is not written**.
 * There was nothing left for it to do.
 *
 * ## Bound to the thread it is built on
 *
 * `Choreographer.getInstance()` is per-`Looper` and creates one on demand. Called from a second
 * thread it would succeed, deliver callbacks there, and quietly break the engine's single-thread
 * contract — `AnimationHandle` documents that every mutating call must come from the thread driving
 * `tick`. So the instance and its looper are captured once at construction, and [postFrame] refuses
 * a caller from anywhere else rather than silently starting a second frame stream (RULE-003).
 *
 * Task 1 measured which thread that is on device: `system_server`'s main thread, which is the main
 * `Looper`.
 *
 * ## Timestamps
 *
 * Measured, not assumed. `frameTimeNanos` arrives on the same timebase as `System.nanoTime()` —
 * which is what `RealtimeClock` reads — trailing it by the compose lag, 11.06 ms at the first frame
 * and 1.68 ms once warm. Always behind, never ahead. `FrameScheduler`'s KDoc claim about the
 * timebase survived contact with a device.
 *
 * What is **not** established is what happens across a suspend, because the emulator never
 * suspends: its `elapsedRealtimeNanos − uptimeNanos` grew by 13 microseconds across a 41-second
 * screen-off window, where a real suspend would have shown 41 seconds. See
 * `DefaultAnimationController.stop()`.
 */
class ChoreographerFrameScheduler(context: Context) : FrameScheduler {

    private val looper: Looper = requireNotNull(Looper.myLooper()) {
        "ChoreographerFrameScheduler must be built on a thread with a Looper; Choreographer has " +
            "no meaning without one, and constructing this off the frame thread would bind the " +
            "engine to whichever thread happened to get here first"
    }

    private val choreographer: Choreographer = Choreographer.getInstance()

    /**
     * Read from the default display rather than assumed.
     *
     * The interface calls this advisory and forbids accumulating it, so a wrong value cannot break
     * an animation — which is exactly why it is worth getting right rather than writing 16_666_666
     * and a hopeful comment. A display that cannot be reached falls back to 60Hz instead of
     * throwing: refusing to build a frame source over a number nothing is allowed to depend on
     * would trade a working engine for a tidy failure.
     */
    override val frameIntervalNanos: Long = run {
        val hz = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
            ?.refreshRate
            ?: 0f
        if (hz > 0f) (1_000_000_000.0 / hz).toLong() else NOMINAL_60HZ
    }

    override fun postFrame(callback: FrameCallback): Disposable {
        check(Looper.myLooper() === looper) {
            "postFrame from ${Thread.currentThread().name}, but this scheduler is bound to " +
                "${looper.thread.name}. Callbacks would arrive on the wrong thread and every " +
                "AnimationHandle touched from them would be off its frame thread."
        }
        val posted = Choreographer.FrameCallback { frameTimeNanos -> callback.doFrame(frameTimeNanos) }
        choreographer.postFrameCallback(posted)
        return Cancellation(posted)
    }

    /**
     * Cancels one pending callback.
     *
     * `removeFrameCallback` is safe to call for a callback that has already run — it simply finds
     * nothing — so this needs no bookkeeping beyond idempotence, which [Disposable] requires
     * anyway.
     */
    private inner class Cancellation(private val posted: Choreographer.FrameCallback) : Disposable {

        @Volatile
        private var removed = false

        override val isDisposed: Boolean
            get() = removed

        override fun dispose() {
            if (removed) return
            removed = true
            choreographer.removeFrameCallback(posted)
        }
    }

    private companion object {
        /** 60Hz, used only when the display cannot be asked. */
        const val NOMINAL_60HZ: Long = 16_666_666L
    }
}
