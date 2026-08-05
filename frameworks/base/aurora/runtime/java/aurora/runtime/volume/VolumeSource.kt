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
 * One stream's volume, as the platform reports it.
 *
 * Every field is in the platform's own units. [level] is a step index, not a fraction, and
 * [minLevel] and [maxLevel] are the ends of the range it moves in — inclusive, so a stream with
 * `min = 0, max = 7` has eight positions.
 *
 * ## Why the three numbers arrive together
 *
 * `VolumeService.levelOf` normalises, which needs a level and a range from the *same instant*.
 * Read through separate calls they can straddle a change and produce a fraction that was never
 * true of anything. One value carries them consistently, and a source has nothing to remember in
 * order to make that so.
 *
 * @param level current step index, between [minLevel] and [maxLevel] inclusive
 * @param minLevel lowest step this stream can be set to; usually but not necessarily zero
 * @param maxLevel highest step this stream can be set to
 * @param muted whether the stream is muted. Muting preserves [level], so unmuting restores it.
 */
data class VolumeStreamState(
    val level: Int,
    val minLevel: Int,
    val maxLevel: Int,
    val muted: Boolean,
)

/**
 * Where audio state actually lives.
 *
 * ## Why this exists (ADR-010)
 *
 * `VolumeService` declares seven members and every one of them needs device state, which nothing
 * in `aurora.sdk` or `aurora.runtime` can supply — the count is zero of seven, so the service
 * cannot be implemented without something like this. Four smaller options were weighed and each
 * fails: reaching through `AuroraContext.hostContext()` needs an `android.` import this layer
 * forbids permanently, an event bus has no query path for `levelOf`, seven injected callbacks split
 * one piece of state into seven, and putting the whole service in `aurora.platform` makes clamping,
 * normalisation and listener management untestable without a device.
 *
 * ## Raw units, and where the conversion is not
 *
 * This interface performs no normalisation. `VolumeService.stepCountOf` has to be answerable, and
 * normalisation has already discarded what that needs, so the seam carries what the platform has
 * and [DefaultVolumeService] converts above it. That is the same unit-boundary discipline the
 * animation packages keep, one subsystem over: one place converts, and it is not this one.
 *
 * ## Implemented in `aurora.platform`, and not yet
 *
 * RULE-007: the interface is declared in the layer that needs it, and satisfied by the layer that
 * can. The Android-backed implementation belongs in `aurora.platform` and cannot be written until
 * Sprint 03 replaces `forbid-import: android.` in `platform.contract` with a narrow allow list.
 * Everything above this line runs on a host today with a fake.
 */
interface VolumeSource {

    /** Everything known about [stream], read as one consistent value. */
    fun stateOf(stream: VolumeStream): VolumeStreamState

    /**
     * Moves [stream] to step [level].
     *
     * Out-of-range values are the caller's to avoid; [DefaultVolumeService] clamps before calling.
     */
    fun setLevel(stream: VolumeStream, level: Int)

    /** Mutes or unmutes [stream], preserving its level. */
    fun setMuted(stream: VolumeStream, muted: Boolean)

    /** The stream the hardware keys currently drive. */
    val activeStream: VolumeStream

    /**
     * Observes changes from any origin, the hardware keys included.
     *
     * The callback carries only *which* stream changed. It is an invalidation signal rather than a
     * value: whoever cares reads [stateOf] afterwards, so a source never has to decide what a
     * listener wanted or convert anything on the way out.
     *
     * May be called on any thread. [DefaultVolumeService] is what marshals, through
     * `AuroraDispatcher`.
     *
     * @return a handle that stops the callbacks. Unlike `VolumeService`'s own add/remove pair, this
     *     follows the SDK's observer shape — `AnimationHandle.addListener`, `AuroraEventBus.subscribe`
     *     and `FrameScheduler.postFrame` all return one, and a caller that writes its listener inline
     *     can still unsubscribe.
     */
    fun addListener(listener: (stream: VolumeStream) -> Unit): Disposable
}
