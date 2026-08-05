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

package aurora.runtime.volume

import aurora.sdk.event.AuroraDispatcher
import aurora.sdk.service.VolumeService
import aurora.sdk.service.VolumeStream
import kotlin.math.roundToInt

/**
 * The first implementation of an Aurora service, and it contains no Android.
 *
 * Everything here is the part of `VolumeService` that is *not* device state: normalisation,
 * clamping, step arithmetic, the listener list and where listeners run. Device state arrives
 * through [VolumeSource], which `aurora.platform` will satisfy once Sprint 03 lands (ADR-010).
 *
 * That split is the whole point. A volume overlay, a volume shortcut and a volume gesture can each
 * be tested against a fake of one small interface, rather than against a fake of Android.
 *
 * ## Threading
 *
 * [VolumeSource.addListener] may fire on any thread, so the listener array is copy-on-write behind
 * a lock for mutation and a volatile read for dispatch. Where listeners actually *run* is the
 * [AuroraDispatcher]'s decision — `IMMEDIATE` on a host, a main-thread poster on device — because
 * `runtime.contract` forbids `Looper`, `Handler(` and `Thread.sleep` outright and this class must
 * not know what a main thread is.
 */
class DefaultVolumeService(
    private val source: VolumeSource,
    private val dispatcher: AuroraDispatcher = AuroraDispatcher.IMMEDIATE,
) : VolumeService {

    override val serviceName: String = "aurora.volume"

    private val lock = Any()

    /**
     * Copy-on-write, so notification allocates nothing and cannot see a half-finished mutation.
     * The same shape `AnimationHandleImpl` uses, and for the same reason.
     */
    @Volatile
    private var listeners: Array<(VolumeStream, Float) -> Unit> = emptyArray()

    /**
     * Subscribed once, for the life of the service.
     *
     * Not subscribed lazily on the first listener. That would be tidier when nobody is listening
     * and would buy a re-entrancy question — what a listener added *during* a dispatch should see —
     * in exchange for one object. If a real source ever makes an idle subscription expensive, this
     * is where that changes.
     */
    private val subscription = source.addListener(::onSourceChanged)

    // --- reading -------------------------------------------------------------

    override fun levelOf(stream: VolumeStream): Float {
        val state = source.stateOf(stream)
        return (state.level - state.minLevel).toFloat() / span(state, stream)
    }

    override fun stepCountOf(stream: VolumeStream): Int {
        val state = source.stateOf(stream)
        // Inclusive of both ends: min = 0, max = 7 is eight positions, not seven. A UI snapping to
        // steps draws one per position, and silence is a position.
        return state.maxLevel - state.minLevel + 1
    }

    override fun isMuted(stream: VolumeStream): Boolean = source.stateOf(stream).muted

    override val activeStream: VolumeStream
        get() = source.activeStream

    // --- writing -------------------------------------------------------------

    override fun setLevel(stream: VolumeStream, level: Float) {
        val state = source.stateOf(stream)
        val span = span(state, stream)
        val clamped = if (level < 0f) 0f else if (level > 1f) 1f else level
        // Rounded, not truncated. Truncation makes 0.99 land one step below the top, so a finger
        // dragging a slider to the end could never reach maximum volume - a bug that looks like a
        // hardware limit and is arithmetic.
        source.setLevel(stream, state.minLevel + (clamped * span).roundToInt())
    }

    override fun setMuted(stream: VolumeStream, muted: Boolean) = source.setMuted(stream, muted)

    // --- observing -----------------------------------------------------------

    override fun addOnVolumeChangedListener(listener: (stream: VolumeStream, level: Float) -> Unit) {
        synchronized(lock) { listeners += listener }
    }

    /**
     * Removes one registration of [listener], by identity.
     *
     * By identity and not by equality, and one occurrence rather than all of them: the same
     * function may legitimately be registered twice and unsubscribed once. `AnimationHandleImpl`'s
     * subscription does the same.
     *
     * A caller therefore has to keep the reference it registered. That is a real cost, and it is
     * why every other observable surface in the SDK hands back a `Disposable` instead — see the
     * note on [VolumeSource.addListener]. The pair is implementable exactly as declared, so this
     * implementation does not change the contract; whether the contract should change is an SDK
     * question and needs its own ADR.
     */
    override fun removeOnVolumeChangedListener(
        listener: (stream: VolumeStream, level: Float) -> Unit,
    ) {
        synchronized(lock) {
            val current = listeners
            val at = current.indexOfFirst { it === listener }
            if (at < 0) return
            listeners = Array(current.size - 1) { i -> if (i < at) current[i] else current[i + 1] }
        }
    }

    /** Releases the subscription to the source. Idempotent. */
    fun dispose() = subscription.dispose()

    // --- internals -----------------------------------------------------------

    private fun onSourceChanged(stream: VolumeStream) {
        val snapshot = listeners
        if (snapshot.isEmpty()) return
        // Read before dispatching, not inside the block. With a queuing dispatcher the block runs
        // later, and a listener is entitled to the level at the moment of the change rather than
        // whatever it happens to be when the queue drains.
        val level = levelOf(stream)
        dispatcher.dispatch {
            var i = 0
            while (i < snapshot.size) {
                snapshot[i](stream, level)
                i++
            }
        }
    }

    /**
     * Distance between the ends, as a float, refusing a degenerate range.
     *
     * RULE-003: a stream whose min equals its max cannot be normalised, and dividing anyway would
     * hand every caller a `NaN` or an infinity that reaches a slider as a view that stops drawing.
     * A source reporting it is broken, so it is said loudly rather than papered over with a zero.
     */
    private fun span(state: VolumeStreamState, stream: VolumeStream): Float {
        check(state.maxLevel > state.minLevel) {
            "volume source reports an empty range for $stream: " +
                "min=${state.minLevel}, max=${state.maxLevel}. There is no level to normalise."
        }
        return (state.maxLevel - state.minLevel).toFloat()
    }
}
