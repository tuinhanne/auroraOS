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

import aurora.sdk.event.Disposable

/**
 * A running animation, from the caller point of view.
 *
 * ## Three rules cover the whole surface
 *
 * 1. **Queries never throw.** [state], [isRunning], [progress], [value], [executionId] and
 *    [animation] are readable in every state including [AnimationState.DISPOSED]. They read
 *    volatile fields, take no lock and trigger no lazy computation, so reading one from a
 *    listener during a frame costs nothing.
 * 2. **Repeating a call that has already taken effect is not an error.** [dispose] on a disposed
 *    handle, [cancel] on a resting one and [pause] on a paused one all do nothing rather than
 *    throwing. Teardown paths run more than once far more often than anyone expects, and a
 *    throwing second call turns a harmless duplicate into a crash. This is the same reasoning
 *    [Disposable] already records.
 * 3. **[play], [pause], [resume], [restart] and [seek] throw [IllegalStateException]** when the
 *    current state does not permit them (RULE-003). [cancel] throws only on a disposed handle.
 *    After [dispose], everything throws except the queries and [dispose] itself.
 *
 * Rule 2 applies only while the handle is alive: `dispose(); cancel()` throws, because the
 * handle is gone, not because cancelling twice is wrong.
 *
 * ## Threading
 *
 * Not thread safe. Every mutating call — [play], [pause], [resume], [cancel], [restart], [seek],
 * [dispose], [addListener] — must happen on the same thread that drives
 * `AnimationController.tick`, which on device is the frame thread. The queries are volatile
 * reads and are safe from anywhere.
 *
 * This is a contract, not an oversight. Making the engine thread safe would mean locking on the
 * path that runs for every animation on every frame, to serve callers who almost always are on
 * the frame thread already. A caller genuinely elsewhere should post to the frame thread rather
 * than have every animation pay for it. RULE-013 is about re-entrancy from a listener, which is
 * a different problem and is handled.
 *
 * ## Reuse
 *
 * A completed or cancelled handle is *resting*, not dead: [restart] begins a new execution on
 * the same object. Volume, Dynamic Island and Control Center re-run the same animation
 * constantly, and allocating a fresh handle each time would put garbage on a gesture path.
 * Only [dispose] is final.
 */
interface AnimationHandle : Disposable {

    /** What this handle plays. Immutable, so it survives any number of executions. */
    val animation: Animation

    /** Where the handle is in its lifecycle. */
    val state: AnimationState

    /**
     * Which run this is. Starts at 0 and increments on every [restart].
     *
     * RULE-012. Compare it against the id a callback carries before acting on state held
     * across runs.
     */
    val executionId: Long

    /** Unshaped progress of the current execution, 0..1. */
    val progress: Float

    /** The animated value: `animation.valueAt(easedProgress)`. */
    val value: Float

    /**
     * Whether this animation is mid-execution.
     *
     * True from the first tick until the animation ends or is held. It does not mean frames are
     * currently arriving — see [AnimationState.RUNNING]. A caller that needs to know whether the
     * engine itself is ticking asks [AnimationController.isRunning].
     */
    val isRunning: Boolean
        get() = state == AnimationState.RUNNING

    /** Derived from [state], never stored separately: two sources of truth would drift. */
    override val isDisposed: Boolean
        get() = state == AnimationState.DISPOSED

    /** Schedules the first execution. Legal only from [AnimationState.IDLE]. */
    fun play()

    /**
     * Holds the animation where it is.
     *
     * Legal from [AnimationState.SCHEDULED] as well as [AnimationState.RUNNING]: forbidding
     * the former would make the outcome depend on whether a frame happened to arrive first,
     * which is API behaviour varying with machine load.
     *
     * Throws from [AnimationState.IDLE] and from either resting state: there is no execution to
     * hold. Calling it on an already paused handle does nothing.
     */
    fun pause()

    /**
     * Continues the paused execution, losing no elapsed time.
     *
     * Legal only from [AnimationState.PAUSED]. Resuming a completed or cancelled animation
     * throws -- that is what [restart] is for, and collapsing the two would make the API
     * unable to say which the caller meant.
     */
    fun resume()

    /**
     * Stops the execution where it stands.
     *
     * Does not jump to the end value. An animation cancelled mid-flight should stay where the
     * user last saw it, or the interface appears to teleport.
     */
    fun cancel()

    /** Begins a new execution with a fresh [executionId]. Legal from every state but disposed. */
    fun restart()

    /**
     * Moves the current execution to [progress].
     *
     * Legal from [AnimationState.SCHEDULED], [AnimationState.RUNNING] and
     * [AnimationState.PAUSED] only: seeking positions a live execution, and a finished one has
     * no position to move. Scrubbing a finished animation is `restart(); pause(); seek(p)`.
     *
     * Throws `UnsupportedOperationException` when the animation spec is a [PhysicsSpec]; see
     * [AnimationStrategy.seekTo].
     */
    fun seek(progress: Float)

    /**
     * Observes this handle.
     *
     * @return a handle that unsubscribes. Registering during a callback is safe; the listener
     *     starts receiving from the next dispatch.
     *
     * Listeners are released when the handle is disposed, so a component that disposes its
     * handle does not also have to dispose each subscription to avoid retaining itself.
     */
    fun addListener(listener: AnimationListener): Disposable
}
