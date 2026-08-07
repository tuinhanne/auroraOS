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
package aurora.feature.volume

import android.os.Looper
import android.view.Choreographer
import aurora.sdk.event.Disposable
import aurora.sdk.time.FrameCallback
import aurora.sdk.time.FrameScheduler

/**
 * `Choreographer` as a [FrameScheduler], on SystemUI's main thread.
 *
 * ## This is the second one, and that is recorded rather than hidden
 *
 * `aurora.platform.android.ChoreographerFrameScheduler` already does this, and it is nearly the same
 * file. It cannot be reused here: ADR-014 forbids this layer from importing
 * `aurora.platform.android.`, because that code runs in `system_server` and every reference across
 * that boundary is IPC by construction.
 *
 * **But the boundary is drawn through the wrong file.** A `Choreographer` adapter is
 * process-agnostic â€” it binds to whatever `Looper` it is constructed on, which is the whole of its
 * behaviour. ADR-014 put a neutral adapter inside a process-specific layer, and this duplicate is
 * what discovers it.
 *
 * Duplicated on purpose rather than moved, because moving a file across a contract boundary is a
 * change to two contracts and belongs in an ADR, not in the commit that draws the first pixel.
 * **ADR-017's question: does `ChoreographerFrameScheduler` belong in a process-neutral layer both
 * Android adapters can depend on?** Until that is answered, two copies with a comment beat one copy
 * in a place the contracts say it cannot be.
 */
internal class PluginFrameScheduler(
    private val frameIntervalNanosHint: Long = NOMINAL_60HZ,
) : FrameScheduler {

    /**
     * The thread this was built on, captured rather than assumed.
     *
     * `Choreographer.getInstance()` is per-`Looper` and would happily hand a *second* instance to a
     * second thread, which starts a second frame stream that nothing owns. The scheduler refuses
     * that instead of tolerating it.
     */
    private val looper: Looper = requireNotNull(Looper.myLooper()) {
        "PluginFrameScheduler must be built on a thread with a Looper; SystemUI's main thread has one"
    }

    private val choreographer: Choreographer = Choreographer.getInstance()

    /**
     * Advisory, and deliberately not read from `DisplayManager` here.
     *
     * The `aurora.platform.android` copy asks the display for its refresh rate because it is built
     * in `system_server`, which has no display association. A plugin has a view attached to a real
     * window and could ask that view â€” but nothing in this class's contract may accumulate this
     * value, so a nominal figure is honest and a wrong measured one would only look better.
     */
    override val frameIntervalNanos: Long = frameIntervalNanosHint

    override fun postFrame(callback: FrameCallback): Disposable {
        check(Looper.myLooper() === looper) {
            "postFrame from ${Looper.myLooper()} but this scheduler belongs to $looper"
        }
        val posted = Choreographer.FrameCallback { t -> callback.doFrame(t) }
        choreographer.postFrameCallback(posted)
        return Cancellation(posted)
    }

    /**
     * Cancels one pending callback.
     *
     * `removeFrameCallback` finds nothing for a callback that already ran, so idempotence is all
     * this needs â€” and [Disposable] requires that anyway.
     */
    private inner class Cancellation(
        private val posted: Choreographer.FrameCallback,
    ) : Disposable {

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

    internal companion object {
        /** 60Hz, used when nothing better is known. Advisory only. */
        const val NOMINAL_60HZ: Long = 16_666_666L
    }
}
