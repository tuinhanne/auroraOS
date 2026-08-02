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

package aurora.runtime.time

import aurora.sdk.event.Disposable
import aurora.sdk.time.AuroraClock
import aurora.sdk.time.FrameCallback
import aurora.sdk.time.FrameScheduler
import aurora.sdk.time.FrameTime

/**
 * A clock the caller moves by hand.
 *
 * Not a mock. It is a portable implementation of [AuroraClock] whose time is supplied rather
 * than sampled, which is what makes deterministic replay and benchmarking possible as well as
 * testing. That is why it sits in `aurora.runtime.time` beside [RealtimeClock] rather than in
 * a test-only artifact.
 *
 * ## RULE-006 applies here too
 *
 * There is no `rewind()` and no `setTime()`. [AuroraClock] promises monotonicity, and an
 * implementation able to break it would let code be written — and proven correct — against
 * behaviour the real clock can never produce. A caller wanting a different origin constructs
 * another [TestClock].
 *
 * [pause] does not violate the rule: a paused clock stops advancing, it does not go back.
 */
class TestClock(startNanos: Long = 0L) : AuroraClock {

    @Volatile
    private var nanos: Long = startNanos

    @Volatile
    private var paused: Boolean = false

    override fun nowNanos(): Long = nanos

    /** Whether [advanceNanos] is currently ignored. */
    val isPaused: Boolean get() = paused

    /**
     * Moves time forward.
     *
     * Does nothing while paused, which lets a caller assert that an animation makes no
     * progress across a pause without having to stop driving frames.
     *
     * @throws IllegalArgumentException if [delta] is negative; see RULE-006
     */
    fun advanceNanos(delta: Long) {
        require(delta >= 0) { "a monotonic clock cannot move backwards (RULE-006)" }
        if (!paused) nanos += delta
    }

    /** Moves time forward by [millis] milliseconds. */
    fun advanceMillis(millis: Long) = advanceNanos(AuroraClock.millisToNanos(millis))

    /** Freezes time. Further calls to [advanceNanos] do nothing until [resume]. */
    fun pause() {
        paused = true
    }

    /** Lets time move again. */
    fun resume() {
        paused = false
    }
}

/**
 * A frame scheduler the caller pumps by hand.
 *
 * Delivers exactly the callbacks pending when a frame begins. Anything posted *during* that
 * frame waits for the next one, which is what a real scheduler does and what stops a
 * self-reposting animation from recursing until the stack runs out.
 */
class QueuedFrameScheduler(
    private val clock: TestClock,
    override val frameIntervalNanos: Long = 16_666_666L,
) : FrameScheduler {

    private class Pending(val callback: FrameCallback) : Disposable {
        @Volatile
        var cancelled = false

        override val isDisposed: Boolean get() = cancelled
        override fun dispose() {
            cancelled = true
        }
    }

    private val lock = Any()
    private val queue = mutableListOf<Pending>()
    private var lastFrameNanos: Long = -1L

    /** Frames delivered since construction. */
    var framesDelivered: Long = 0L
        private set

    /** The [FrameTime] describing the most recent frame, or null before the first. */
    @Volatile
    var lastFrameTime: FrameTime? = null
        private set

    override fun postFrame(callback: FrameCallback): Disposable {
        val pending = Pending(callback)
        synchronized(lock) { queue.add(pending) }
        return pending
    }

    /** Callbacks waiting for the next frame. */
    val pendingCount: Int
        get() = synchronized(lock) { queue.count { !it.cancelled } }

    /**
     * Advances the clock by one frame interval and delivers the callbacks pending before it.
     *
     * @return how many callbacks ran
     */
    fun advanceOneFrame(): Int {
        clock.advanceNanos(frameIntervalNanos)
        val batch: List<Pending>
        synchronized(lock) {
            batch = queue.toList()
            queue.clear()
        }
        val now = clock.nowNanos()
        lastFrameTime = if (lastFrameNanos < 0L) {
            FrameTime.first(now)
        } else {
            FrameTime(now, now - lastFrameNanos, framesDelivered)
        }
        lastFrameNanos = now

        var ran = 0
        batch.forEach {
            if (!it.cancelled) {
                it.callback.doFrame(now)
                ran++
            }
        }
        framesDelivered++
        return ran
    }

    /** Runs [count] frames. */
    fun advanceFrames(count: Int) = repeat(count) { advanceOneFrame() }

    /**
     * Runs frames until nothing is pending, or until [maxFrames] have run.
     *
     * The cap is a guard: an animation that never finishes should fail a test rather than hang
     * it.
     *
     * @return how many frames ran
     */
    fun runToIdle(maxFrames: Int = 1000): Int {
        var frames = 0
        while (pendingCount > 0 && frames < maxFrames) {
            advanceOneFrame()
            frames++
        }
        return frames
    }
}

/**
 * Runs each callback the moment it is posted.
 *
 * For code that needs a [FrameScheduler] but is not being exercised for frame behaviour. Never
 * drive a real animation with it: with no gap between frames every delta is zero and nothing
 * moves.
 */
class ImmediateFrameScheduler(
    private val clock: AuroraClock,
    override val frameIntervalNanos: Long = 0L,
) : FrameScheduler {

    override fun postFrame(callback: FrameCallback): Disposable {
        callback.doFrame(clock.nowNanos())
        return AlreadyRun
    }

    private object AlreadyRun : Disposable {
        override val isDisposed: Boolean get() = true
        override fun dispose() = Unit
    }
}
