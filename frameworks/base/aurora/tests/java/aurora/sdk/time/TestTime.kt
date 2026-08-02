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

package aurora.sdk.time

import aurora.sdk.event.Disposable

/**
 * A clock the test moves by hand.
 *
 * ## Why these doubles live in the test module
 *
 * Nothing outside a test needs them, and shipping a clock that can be set to any value inside
 * the platform jar invites someone to use it by accident. If platform code ever genuinely
 * needs them, the answer is a separate `aurora-sdk-testing` library rather than putting test
 * doubles in the production artifact.
 */
class TestClock(startNanos: Long = 0L) : AuroraClock {

    @Volatile
    private var nanos: Long = startNanos

    override fun nowNanos(): Long = nanos

    /** Moves time forward. Refuses to go backwards, because a monotonic clock cannot. */
    fun advanceNanos(delta: Long) {
        require(delta >= 0) { "a monotonic clock cannot move backwards" }
        nanos += delta
    }

    /** Moves time forward by [millis] milliseconds. */
    fun advanceMillis(millis: Long) = advanceNanos(AuroraClock.millisToNanos(millis))
}

/**
 * A frame scheduler the test pumps by hand.
 *
 * Delivers exactly the callbacks that were pending when a frame began. Anything posted *during*
 * that frame waits for the next one, which is what a real [FrameScheduler] does and what stops
 * a self-reposting animation from recursing until the stack runs out.
 */
class FakeFrameScheduler(
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

    /** Total frames delivered since construction. */
    var framesDelivered: Int = 0
        private set

    override fun postFrame(callback: FrameCallback): Disposable {
        val pending = Pending(callback)
        synchronized(lock) { queue.add(pending) }
        return pending
    }

    /** How many callbacks are waiting for the next frame. */
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
        val frameTime = clock.nowNanos()
        var ran = 0
        batch.forEach {
            if (!it.cancelled) {
                it.callback.doFrame(frameTime)
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
     * The cap is a guard: an animation that never finishes would otherwise hang the test
     * instead of failing it.
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
