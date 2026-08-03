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
 * Observes one animation.
 *
 * ## Why every callback carries an execution id
 *
 * A handle outlives its executions (RULE-012). A listener registered while execution 3 was
 * running is still attached when `restart()` begins execution 4, and without the id there is
 * nothing in the callback to say which run it belongs to. That is a bug class which is
 * extremely hard to find, because the callback looks correct in isolation and only misbehaves
 * when a component holds state across runs.
 *
 * ## Why both methods are defaulted
 *
 * So the interface can grow. Sprint 06B and 06C will want more callbacks, and an implementor
 * that overrode only [onUpdate] must not stop compiling when they arrive.
 *
 * ## Threading
 *
 * Callbacks arrive on whichever thread called `AnimationController.tick`, which on device is
 * the frame thread. Do no work here that a frame cannot afford, and expect re-entrancy: it is
 * legal to start, cancel or dispose animations from inside a callback, and the engine defers
 * the structural effect to the end of the frame (RULE-013).
 */
interface AnimationListener {

    /**
     * The animation moved between lifecycle states.
     *
     * Not called when a state transition is a no-op, such as cancelling an already cancelled
     * animation, so a subscriber counting transitions counts real ones.
     */
    fun onStateChanged(
        handle: AnimationHandle,
        executionId: Long,
        from: AnimationState,
        to: AnimationState,
    ) {
    }

    /**
     * The animation advanced.
     *
     * @param elapsedNanos time since this execution began
     * @param value the animated value: `handle.animation.valueAt(sample.value)`
     */
    fun onUpdate(
        handle: AnimationHandle,
        executionId: Long,
        elapsedNanos: Long,
        value: Float,
    ) {
    }
}
