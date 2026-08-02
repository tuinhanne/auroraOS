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

package aurora.sdk.animation

/**
 * What feature code calls to animate something.
 *
 * Deliberately small. Everything about *running* an animation lives on the
 * [AnimationHandle] it returns; this interface only makes them.
 *
 * ## One value per handle
 *
 * A handle animates a single `Float`. A component animating several things at once — a shape
 * morph with a width, a height and a corner radius — runs one handle per value. They cannot
 * drift apart, because RULE-011 gives every animation in a frame the same `FrameTime`, but
 * keeping their lifecycles in step is the caller's job. If that becomes a burden, the answer is
 * a composite handle layered on top of this interface, in the shape of
 * `aurora.sdk.event.CompositeDisposable` — not a wider value type here.
 */
interface Animator {

    /**
     * Makes a handle without starting it.
     *
     * The reason this exists alongside [play]: listeners attached before the first frame are
     * guaranteed to observe every update of the first execution. Attaching after `play()` is
     * a race with the frame source on device, and a race is not something a caller should have
     * to reason about.
     */
    fun create(animation: Animation): AnimationHandle

    /** [create] followed by [AnimationHandle.play]. */
    fun play(animation: Animation): AnimationHandle

    /**
     * Cancels every animation this animator is currently driving.
     *
     * Cancels, not disposes: the handles stay usable, so a caller holding one can restart it.
     */
    fun cancelAll()

    /** How many animations are scheduled or running. For diagnostics and tests. */
    val activeCount: Int
}
