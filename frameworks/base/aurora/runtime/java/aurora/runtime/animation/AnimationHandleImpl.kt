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
import aurora.sdk.animation.AnimationListener
import aurora.sdk.animation.AnimationSpec
import aurora.sdk.animation.AnimationState
import aurora.sdk.animation.DecaySpec
import aurora.sdk.animation.MotionSample
import aurora.sdk.animation.MotionSampler
import aurora.sdk.animation.PhysicsSpec
import aurora.sdk.animation.SpringSpec
import aurora.sdk.animation.TimedSpec
import aurora.sdk.event.Disposable
import aurora.sdk.time.FrameTime

/**
 * One animation, bound to the engine.
 *
 * Holds four things and coordinates them: the pure [AnimationStateMachine], an
 * [ExecutionTimeline] for elapsed time, a [MotionSampler] for position, and the listener
 * list. Nothing here decides *what* is legal or *how far through* anything is; both of those
 * questions belong to objects that can be tested without a handle.
 *
 * Public rather than `internal` on purpose: the host tests are a separate Soong module, and
 * Kotlin `internal` is module-scoped, so an internal handle would be untestable.
 */
class AnimationHandleImpl(
    override val animation: Animation,
    private val registry: AnimationRegistry,
) : AnimationHandle, AnimationRegistry.Tickable {

    private var sampler: MotionSampler = samplerFor(animation.spec)
    private val execution = ExecutionTimeline()

    @Volatile
    override var state: AnimationState = AnimationState.IDLE
        private set

    @Volatile
    override var executionId: Long = 0L
        private set

    @Volatile
    override var elapsedNanos: Long = 0L
        private set

    @Volatile
    override var value: Float = animation.valueAt(samplerFor(animation.spec).sampleAt(0L).value)
        private set

    @Volatile
    override var velocity: Float = 0f
        private set

    @Volatile
    override var normalizedPosition: Float = Float.NaN
        private set

    override val hasNormalizedPosition: Boolean
        get() = animation.spec is TimedSpec

    /** Which state a pause came from, so resuming returns there. */
    private var pausedFrom: AnimationState = AnimationState.RUNNING

    /**
     * The frame this execution entered the registry on, or [AnimationRegistry.NOT_TICKING].
     *
     * RULE-013: an execution scheduled from inside frame N must not also advance in frame N,
     * or where it started would depend on listener order.
     */
    private var scheduledOnFrame: Long = AnimationRegistry.NOT_TICKING

    /**
     * Copy-on-write, so dispatch allocates nothing.
     *
     * A frame walks this array for every running animation. Copying on subscribe instead --
     * which is rare -- keeps the per-frame path free of garbage, and makes a listener added or
     * removed during a dispatch take effect on the next one rather than corrupting this one.
     */
    @Volatile
    private var listeners: Array<AnimationListener> = emptyArray()

    override val isTickable: Boolean
        get() = state.isActive

    // --- lifecycle -----------------------------------------------------------

    override fun play() = dispatch(AnimationEvent.PLAY)

    override fun pause() = dispatch(AnimationEvent.PAUSE)

    override fun resume() = dispatch(AnimationEvent.RESUME)

    override fun cancel() = dispatch(AnimationEvent.CANCEL)

    override fun restart() = dispatch(AnimationEvent.RESTART)

    override fun dispose() = dispatch(AnimationEvent.DISPOSE)

    override fun seekToElapsed(nanos: Long) {
        check(state == AnimationState.SCHEDULED ||
              state == AnimationState.RUNNING ||
              state == AnimationState.PAUSED) {
            "seekToElapsed is not legal in state $state: seeking positions a live execution, and " +
                "a finished one has no position to move. Use restart() first."
        }
        require(nanos >= 0) { "cannot seek before the execution began: $nanos" }

        execution.seekTo(nanos)
        publishUpdate(sampler.sampleAt(nanos), nanos)
    }

    override fun addListener(listener: AnimationListener): Disposable {
        check(state != AnimationState.DISPOSED) { "cannot observe a disposed handle" }
        listeners += listener
        return Subscription(listener)
    }

    // --- being driven --------------------------------------------------------

    override fun tick(frameTime: FrameTime) {
        if (!state.isActive) return
        // RULE-013. Also covers a restart from inside the frame currently being processed.
        if (frameTime.frameIndex <= scheduledOnFrame) return

        if (state == AnimationState.SCHEDULED) dispatch(AnimationEvent.TICK)

        val elapsed = execution.advanceTo(frameTime.frameTimeNanos)
        val sample = sampler.sampleAt(elapsed)
        publishUpdate(sample, elapsed)

        if (animation.spec.isFinished(elapsed, sample)) dispatch(AnimationEvent.FINISH)
    }

    // --- the one place state changes -----------------------------------------

    private fun dispatch(event: AnimationEvent) {
        val from = state
        if (from == AnimationState.DISPOSED && event != AnimationEvent.DISPOSE) {
            throw IllegalStateException(
                "$event is not legal on a disposed handle (${animation.name})"
            )
        }

        // Throws when the event is illegal, before anything has been mutated.
        val to = AnimationStateMachine.next(from, event, pausedFrom)

        when (event) {
            AnimationEvent.PLAY -> enterRegistry()

            AnimationEvent.RESTART -> {
                executionId++
                // A fresh sampler rather than a reset one. A stepped sampler's internal state is
                // its own business, and there is nothing for the engine to remember to clear.
                sampler = samplerFor(animation.spec)
                execution.reset()
                pausedFrom = AnimationState.RUNNING
                elapsedNanos = 0L
                val fresh = sampler.sampleAt(0L)
                value = animation.valueAt(fresh.value)
                velocity = 0f
                normalizedPosition = if (hasNormalizedPosition) fresh.value else Float.NaN
                enterRegistry()
            }

            AnimationEvent.PAUSE -> if (from != AnimationState.PAUSED) {
                pausedFrom = from
                execution.pause()
                // A paused animation is not advanced, so it leaves the registry: an engine
                // whose animations are all paused should stop asking for frames.
                registry.remove(this)
            }

            AnimationEvent.RESUME -> {
                execution.resume()
                enterRegistry()
            }

            AnimationEvent.CANCEL -> if (!from.isResting) registry.remove(this)

            AnimationEvent.FINISH -> registry.remove(this)

            AnimationEvent.DISPOSE -> registry.remove(this)

            AnimationEvent.TICK -> Unit
        }

        if (to != from) {
            state = to
            publishStateChange(from, to)
        }

        // After the notification, so a listener still hears about the disposal it caused.
        if (event == AnimationEvent.DISPOSE) listeners = emptyArray()
    }

    private fun enterRegistry() {
        scheduledOnFrame = registry.tickingFrameIndex
        registry.add(this)
    }

    // --- notification --------------------------------------------------------

    private fun publishUpdate(sample: MotionSample, elapsed: Long) {
        // Everything a listener is handed is captured before the loop, never read from the
        // fields inside it. A listener may restart or seek this very handle from its callback,
        // which rewrites these fields and the executionId - and a later listener in the same
        // dispatch would then be handed one execution's id beside another execution's numbers.
        val v = animation.valueAt(sample.value)
        val range = animation.to - animation.from
        elapsedNanos = elapsed
        value = v
        velocity = sample.velocity * range
        normalizedPosition =
            if (hasNormalizedPosition) sample.value else Float.NaN

        val snapshot = listeners
        val id = executionId
        var i = 0
        while (i < snapshot.size) {
            snapshot[i].onUpdate(this, id, elapsed, v)
            i++
        }
    }

    private fun publishStateChange(from: AnimationState, to: AnimationState) {
        // from and to are already parameters rather than fields, so the same discipline
        // publishUpdate needs applies here too, and must keep applying if this ever changes.
        val snapshot = listeners
        val id = executionId
        var i = 0
        while (i < snapshot.size) {
            snapshot[i].onStateChanged(this, id, from, to)
            i++
        }
    }

    private inner class Subscription(private val listener: AnimationListener) : Disposable {

        @Volatile
        private var removed = false

        override val isDisposed: Boolean
            get() = removed

        override fun dispose() {
            if (removed) return
            removed = true
            val current = listeners
            val at = current.indexOfFirst { it === listener }
            if (at < 0) return
            // Removes one occurrence, not every equal one: the same listener may legitimately
            // be registered twice and unsubscribed once.
            listeners = Array(current.size - 1) { i -> if (i < at) current[i] else current[i + 1] }
        }
    }

    private companion object {

        fun samplerFor(spec: AnimationSpec): MotionSampler = when (spec) {
            is TimedSpec -> TimedSampler(spec)
            is SpringSpec -> SpringSampler(spec)
            is DecaySpec -> DecaySampler(spec)
            is PhysicsSpec -> throw UnsupportedOperationException(
                "${spec.javaClass.simpleName} has no sampler yet; snap arrives in Sprint 06B.3"
            )
        }
    }
}
