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

package aurora.sdk.service

/** An independently adjustable audio stream. */
enum class VolumeStream { MEDIA, RING, NOTIFICATION, ALARM, CALL, SYSTEM }

/**
 * Reads and changes audio volume.
 *
 * ## Levels are normalised, not raw steps
 *
 * [levelOf] returns 0.0..1.0 rather than the platform's step index. Step counts differ per
 * stream and per device, so a UI built on raw steps has to special-case both. A normalised
 * level maps directly onto a slider and onto an animation.
 *
 * [stepCountOf] is still exposed, because a volume UI that snaps to real steps feels correct
 * while one that moves continuously and then jumps on release does not.
 */
interface VolumeService : AuroraService {

    /** Current level of [stream], 0.0..1.0. */
    fun levelOf(stream: VolumeStream): Float

    /**
     * Sets the level of [stream].
     *
     * @param level clamped to 0.0..1.0
     */
    fun setLevel(stream: VolumeStream, level: Float)

    /** Number of discrete steps [stream] has, for snapping. */
    fun stepCountOf(stream: VolumeStream): Int

    /** Whether [stream] is muted. Muting preserves the level, so unmuting restores it. */
    fun isMuted(stream: VolumeStream): Boolean

    /** Mutes or unmutes [stream]. */
    fun setMuted(stream: VolumeStream, muted: Boolean)

    /** The stream the hardware keys currently control. */
    val activeStream: VolumeStream

    /** Observes changes from any source, including the hardware keys. */
    fun addOnVolumeChangedListener(listener: (stream: VolumeStream, level: Float) -> Unit)

    /** Removes a previously registered listener. */
    fun removeOnVolumeChangedListener(listener: (stream: VolumeStream, level: Float) -> Unit)
}
