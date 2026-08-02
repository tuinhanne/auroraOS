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

import aurora.sdk.time.FrameTime

/**
 * The animation engine, from below.
 *
 * Where [Animator] is what feature code calls, this is what the *frame source* calls. Two
 * audiences, two interfaces, so neither has to see the other methods.
 *
 * ## Why tick is public contract rather than a runtime detail
 *
 * Both drivers must enter through the same door:
 *
 * ```
 *   Host test  -->  AnimationController.tick(FrameTime)  <--  Android Choreographer
 * ```
 *
 * That is what turns RULE-009 from a convention into something a test can assert. A host test
 * hands out frames at any spacing it likes, including pathological ones, and Sprint 08
 * `ChoreographerAnimationDriver` calls exactly the same method.
 */
interface AnimationController {

    /** The animator backed by this engine. */
    val animator: Animator

    /** Whether the engine accepts frames. */
    val isRunning: Boolean

    /** Begins accepting frames. */
    fun start()

    /**
     * Stops accepting frames.
     *
     * Running animations are left where they are rather than cancelled, so a display turning
     * off does not visibly reset the interface when it comes back.
     *
     * In-flight handles keep reporting [AnimationState.RUNNING] while stopped. That is
     * deliberate — they have not ended and have not been held — but it means a caller holding
     * only a handle cannot tell a stopped engine from a slow animation. [isRunning] on this
     * interface is the answer for anyone who needs to.
     */
    fun stop()

    /**
     * The only legal entry point of time into the animation engine.
     *
     * Exactly one [FrameTime] is built per frame and handed to every animation (RULE-011), so
     * animations cannot drift apart. The instance must not be mutated (RULE-014).
     *
     * @throws IllegalArgumentException if [FrameTime.frameIndex] does not increase --
     *     RULE-006 monotonicity, applied to frames
     * @throws IllegalStateException if the engine was not started
     */
    fun tick(frameTime: FrameTime)
}
