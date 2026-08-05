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

import aurora.sdk.event.Disposable
import aurora.sdk.service.VolumeStream

/**
 * A volume source held entirely in memory.
 *
 * The point of ADR-010 seen from the test side: every branch of [DefaultVolumeService] —
 * normalisation, clamping, rounding, step counting, listener dispatch — runs on a host JVM against
 * this, with no Android and no device.
 *
 * Every stream starts at [defaultMax] with a range of `0..defaultMax` unless [configure] says
 * otherwise, so a test only has to describe the stream it cares about.
 */
class FakeVolumeSource(
    private val defaultMax: Int = 15,
    override var activeStream: VolumeStream = VolumeStream.MEDIA,
) : VolumeSource {

    private val states = LinkedHashMap<VolumeStream, VolumeStreamState>()
    private val listeners = ArrayList<(VolumeStream) -> Unit>()

    /** How many listeners are attached. Lets a test assert that a subscription was released. */
    val listenerCount: Int
        get() = listeners.size

    /** Replaces [stream]'s whole state, without notifying. Use for arranging a test. */
    fun configure(
        stream: VolumeStream,
        level: Int,
        minLevel: Int = 0,
        maxLevel: Int = defaultMax,
        muted: Boolean = false,
    ) {
        states[stream] = VolumeStreamState(level, minLevel, maxLevel, muted)
    }

    /**
     * Changes [stream] the way the hardware keys would, and notifies.
     *
     * The distinction from [configure] is the notification: this is what an external origin looks
     * like, and it is how the listener path is exercised.
     */
    fun externalChange(stream: VolumeStream, level: Int) {
        val current = stateOf(stream)
        states[stream] = current.copy(level = level)
        notify(stream)
    }

    /** What [DefaultVolumeService] last wrote, for asserting the denormalised value. */
    fun rawLevelOf(stream: VolumeStream): Int = stateOf(stream).level

    override fun stateOf(stream: VolumeStream): VolumeStreamState =
        states.getOrPut(stream) { VolumeStreamState(defaultMax, 0, defaultMax, false) }

    override fun setLevel(stream: VolumeStream, level: Int) {
        states[stream] = stateOf(stream).copy(level = level)
        notify(stream)
    }

    override fun setMuted(stream: VolumeStream, muted: Boolean) {
        states[stream] = stateOf(stream).copy(muted = muted)
        notify(stream)
    }

    override fun addListener(listener: (stream: VolumeStream) -> Unit): Disposable {
        listeners.add(listener)
        return object : Disposable {
            private var removed = false
            override val isDisposed: Boolean get() = removed
            override fun dispose() {
                if (removed) return
                removed = true
                listeners.removeAll { it === listener }
            }
        }
    }

    private fun notify(stream: VolumeStream) {
        // A copy, because a listener may unsubscribe from inside its own callback.
        ArrayList(listeners).forEach { it(stream) }
    }
}
