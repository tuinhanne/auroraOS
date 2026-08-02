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
import aurora.sdk.animation.Animator
import aurora.sdk.time.FrameTime

/**
 * The engine, seen from the frame source.
 *
 * Thin on purpose. It owns the registry, hands out an [Animator] backed by it, and guards the
 * one door time comes through.
 *
 * ## No clock, by construction
 *
 * There is no `AuroraClock` parameter here or anywhere below it. RULE-008 is therefore not a
 * discipline the engine has to keep — there is simply nothing to read. That is a stronger
 * guarantee than any test could give, and it is why the animation packages take a `FrameTime`
 * and never a clock.
 */
class DefaultAnimationController(
    val registry: AnimationRegistry = AnimationRegistry(),
) : AnimationController {

    override val animator: Animator = DefaultAnimator(registry)

    @Volatile
    private var running: Boolean = false

    private var lastFrameIndex: Long = NO_FRAME_YET

    override val isRunning: Boolean
        get() = running

    /**
     * Begins accepting frames.
     *
     * Frame numbering is reset, because a frame source restarted after a [stop] legitimately
     * begins counting again and would otherwise be rejected forever.
     */
    override fun start() {
        running = true
        lastFrameIndex = NO_FRAME_YET
    }

    /**
     * Stops accepting frames.
     *
     * Running animations are left exactly where they are rather than cancelled: a display
     * turning off must not visibly reset the interface when it comes back.
     *
     * ## What that guarantee does not yet cover
     *
     * It holds for the stopped interval itself — nothing advances, and every handle keeps the
     * progress it had. It says nothing about the first frame *after* [start]. Frame timestamps
     * come from a source outside this class, and if that source kept running while the engine
     * was stopped, the first frame back carries a timestamp far beyond the last one seen. Every
     * still-running handle would then be handed a single enormous elapsed step and could finish
     * in one tick instead of resuming.
     *
     * Nothing in Sprint 06A can settle this, because what a stopped frame source does to its own
     * timestamps is Sprint 08's decision, and there is no real one yet. Written down rather than
     * left to be discovered: a `ChoreographerFrameScheduler` that pauses with the display makes
     * the question moot, and one that does not will need [stop] to pause every live execution.
     */
    override fun stop() {
        running = false
    }

    override fun tick(frameTime: FrameTime) {
        check(running) {
            "tick() before start(); a stopped engine ignoring frames silently would look " +
                "exactly like an engine that had stopped animating for some other reason"
        }
        require(frameTime.frameIndex > lastFrameIndex) {
            "frame index must increase: got ${frameTime.frameIndex} after $lastFrameIndex. " +
                "A repeated frame would advance every animation twice for one instant."
        }
        lastFrameIndex = frameTime.frameIndex
        registry.tick(frameTime)
    }

    private companion object {
        /** Lower than any real frame index, so the first frame always passes. */
        const val NO_FRAME_YET: Long = -1L
    }
}
