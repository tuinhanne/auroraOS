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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import aurora.runtime.volume.VolumeSource
import aurora.runtime.volume.VolumeStreamState
import aurora.sdk.event.Disposable
import aurora.sdk.service.VolumeStream

/**
 * `AudioManager`, as a [VolumeSource].
 *
 * ## Deliberately the thinnest thing that can work
 *
 * Every rule about volume — normalising a level, clamping a write, rounding onto a step, counting
 * steps inclusively, holding the listener list, deciding where listeners run — is in
 * `DefaultVolumeService`, on the other side of the seam ADR-010 introduced, covered by seventeen
 * host tests. What is left here is translation: an enum to an `int`, and a broadcast to a
 * notification.
 *
 * That matters more than it usually would, because **this is the first Aurora production code no
 * test covers.** It cannot run on a host, there is no `AudioManager` on a JVM, and nothing in this
 * repository can assert its behaviour. The defence is not a test; it is that the untestable part
 * was made as small as the design allows, and that everything with a decision in it lives
 * somewhere a test can reach. What is unverified here is exactly: the six enum mappings, the
 * broadcast registration, and the one field read from an `Intent`.
 *
 * ## Why a broadcast, and not a callback
 *
 * `AudioManager` has no listener for stream volume. `registerVolumeGroupCallback` is about volume
 * *groups*, which is a different concept from a stream. The mechanism the platform actually
 * provides is `VOLUME_CHANGED_ACTION`, and the system's own volume UI uses it —
 * `SystemUI/.../volume/VolumeDialogControllerImpl` adds exactly this action to exactly this filter.
 * There is no second option to weigh; a survey found one mechanism and the closest production
 * analogue already using it.
 */
class AndroidVolumeSource(private val context: Context) : VolumeSource {

    private val audio: AudioManager =
        context.getSystemService(AudioManager::class.java)
            ?: error("no AudioManager; AndroidVolumeSource cannot be built without one")

    private val listeners = ArrayList<(VolumeStream) -> Unit>()
    private var receiver: BroadcastReceiver? = null

    /**
     * The stream the last observed change was on, defaulting to media.
     *
     * Derived rather than asked for: the platform does not expose which stream the hardware keys
     * currently drive through any public API, and the ones that come close are `@hide` — which the
     * contract treats as a rebase liability wherever it can be avoided. The broadcast carries
     * `EXTRA_VOLUME_STREAM_TYPE`, so the last stream to change is a real answer obtained from a
     * stable API, and it is right in the case that matters: a user pressing the keys is looking at
     * the stream those presses just moved.
     *
     * It is wrong before anything has changed, where it guesses media. A better answer needs the
     * key event itself, which Aurora does not yet receive.
     */
    @Volatile
    override var activeStream: VolumeStream = VolumeStream.MEDIA
        private set

    override fun stateOf(stream: VolumeStream): VolumeStreamState {
        val type = androidStream(stream)
        return VolumeStreamState(
            level = audio.getStreamVolume(type),
            minLevel = audio.getStreamMinVolume(type),
            maxLevel = audio.getStreamMaxVolume(type),
            muted = audio.isStreamMute(type),
        )
    }

    override fun setLevel(stream: VolumeStream, level: Int) {
        audio.setStreamVolume(androidStream(stream), level, 0)
    }

    override fun setMuted(stream: VolumeStream, muted: Boolean) {
        val direction = if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
        audio.adjustStreamVolume(androidStream(stream), direction, 0)
    }

    override fun addListener(listener: (stream: VolumeStream) -> Unit): Disposable {
        listeners.add(listener)
        if (receiver == null) register()
        return object : Disposable {
            private var removed = false
            override val isDisposed: Boolean get() = removed
            override fun dispose() {
                if (removed) return
                removed = true
                listeners.removeAll { it === listener }
                if (listeners.isEmpty()) unregister()
            }
        }
    }

    // --- the platform side ----------------------------------------------------

    private fun register() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val type = intent.getIntExtra(EXTRA_STREAM_TYPE, -1)
                val stream = auroraStream(type) ?: return
                activeStream = stream
                // A copy, because a listener may unsubscribe from inside its own callback.
                ArrayList(listeners).forEach { it(stream) }
            }
        }
        // A null handler means the main looper. `runtime.contract` forbids `Handler(` in the
        // layers below; this one may name it and still does not, because there is nothing here
        // that needs a thread of its own.
        context.registerReceiver(r, IntentFilter(AudioManager.VOLUME_CHANGED_ACTION), null, null)
        receiver = r
    }

    private fun unregister() {
        receiver?.let { context.unregisterReceiver(it) }
        receiver = null
    }

    private companion object {

        /**
         * `AudioManager.EXTRA_VOLUME_STREAM_TYPE` by value rather than by name.
         *
         * The constant is `@hide`. Its *value* is part of a broadcast contract every app on the
         * device already depends on, so it is stable in the way that matters, and naming the
         * string keeps this file free of hidden API. Spelled out rather than borrowed so that
         * nothing here has to be revisited when the annotation changes.
         */
        const val EXTRA_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"

        fun androidStream(stream: VolumeStream): Int = when (stream) {
            VolumeStream.MEDIA -> AudioManager.STREAM_MUSIC
            VolumeStream.RING -> AudioManager.STREAM_RING
            VolumeStream.NOTIFICATION -> AudioManager.STREAM_NOTIFICATION
            VolumeStream.ALARM -> AudioManager.STREAM_ALARM
            VolumeStream.CALL -> AudioManager.STREAM_VOICE_CALL
            VolumeStream.SYSTEM -> AudioManager.STREAM_SYSTEM
        }

        /** The inverse, or null for a stream Aurora does not model. */
        fun auroraStream(type: Int): VolumeStream? = when (type) {
            AudioManager.STREAM_MUSIC -> VolumeStream.MEDIA
            AudioManager.STREAM_RING -> VolumeStream.RING
            AudioManager.STREAM_NOTIFICATION -> VolumeStream.NOTIFICATION
            AudioManager.STREAM_ALARM -> VolumeStream.ALARM
            AudioManager.STREAM_VOICE_CALL -> VolumeStream.CALL
            AudioManager.STREAM_SYSTEM -> VolumeStream.SYSTEM
            else -> null
        }
    }
}
