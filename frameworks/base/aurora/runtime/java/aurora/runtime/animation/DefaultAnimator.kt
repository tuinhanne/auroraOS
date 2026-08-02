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

import aurora.sdk.animation.Animation
import aurora.sdk.animation.AnimationHandle
import aurora.sdk.animation.Animator

/**
 * Makes handles, and nothing else.
 *
 * Every question about a running animation is answered by the handle, so this stays a
 * factory. It holds no list of its own: the registry already knows what is running, and a
 * second list would be a second truth to keep in step.
 */
class DefaultAnimator(private val registry: AnimationRegistry) : Animator {

    override fun create(animation: Animation): AnimationHandle =
        AnimationHandleImpl(animation, registry)

    override fun play(animation: Animation): AnimationHandle =
        create(animation).also { it.play() }

    /**
     * Cancels every animation being advanced.
     *
     * Over a snapshot, because cancelling removes from the registry. Cancels rather than
     * disposes: a caller holding one of these handles can still restart it. The snapshot holds
     * only committed entries, so an animation played by an earlier listener in the same frame is
     * still queued and survives this cancel.
     */
    override fun cancelAll() {
        registry.snapshot().forEach { if (it is AnimationHandle) it.cancel() }
    }

    override val activeCount: Int
        get() = registry.size
}
