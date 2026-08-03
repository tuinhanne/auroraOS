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
import aurora.sdk.animation.Interpolator
import aurora.sdk.animation.TimedSpec
import aurora.sdk.time.AuroraClock
import aurora.sdk.time.FrameTime
import aurora.sdk.time.Timeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RULE-009: the same sequence of frames always produces the same result.
 *
 * Independent of wall clock, thread and frame rate. Everything here compares floats with a
 * tolerance of exactly zero, because "nearly the same" is not what determinism means — a
 * replay that is nearly the same cannot be used to chase a bug.
 */
class AnimationDeterminismTest {

    private val ms = AuroraClock.NANOS_PER_MILLI

    /** A curve with some shape to it, so a bug cannot hide behind a straight line. */
    private val easeOut = Interpolator { p -> 1f - (1f - p) * (1f - p) }

    private fun animation(name: String, durationMs: Long = 300) =
        Animation(name, TimedSpec(Timeline.ofMillis(durationMs), easeOut), from = 10f, to = 90f)

    /** Records every value an animation reports, in order. */
    private class Trace : AnimationListener {
        val values = mutableListOf<Float>()
        override fun onUpdate(
            handle: AnimationHandle,
            executionId: Long,
            elapsedNanos: Long,
            value: Float,
        ) {
            values += value
        }
    }

    /** A fresh engine, so no test can inherit state from another. */
    private fun engine(): DefaultAnimationController =
        DefaultAnimationController().also { it.start() }

    private fun frameAt(index: Long, nanos: Long, deltaNanos: Long) =
        FrameTime(frameTimeNanos = nanos, deltaNanos = deltaNanos, frameIndex = index)

    /** Drives [frames] evenly spaced by [stepNanos], returning the values reported. */
    private fun run(stepNanos: Long, frames: Int, durationMs: Long = 300): List<Float> {
        val c = engine()
        val trace = Trace()
        val h = c.animator.create(animation("a", durationMs))
        h.addListener(trace)
        h.play()
        var t = 0L
        for (i in 0 until frames) {
            c.tick(frameAt(i.toLong(), t, if (i == 0) 0L else stepNanos))
            t += stepNanos
        }
        return trace.values
    }

    // --- 1. Replay ------------------------------------------------------------

    @Test
    fun twoEnginesFedTheSameFramesProduceIdenticalValues() {
        val first = run(stepNanos = 16 * ms, frames = 25)
        val second = run(stepNanos = 16 * ms, frames = 25)
        assertEquals(first.size, second.size)
        first.indices.forEach {
            assertEquals("frame $it", first[it], second[it], 0f)
        }
    }

    @Test
    fun replayingIsStableAcrossManyRuns() {
        val reference = run(stepNanos = 16 * ms, frames = 25)
        repeat(20) { attempt ->
            assertEquals("run $attempt differs", reference, run(stepNanos = 16 * ms, frames = 25))
        }
    }

    // --- 2. Frame rate independence -------------------------------------------

    @Test
    fun theValueAtAGivenElapsedTimeDoesNotDependOnTheFrameRate() {
        // 60Hz, 120Hz and 250Hz all land on 96ms exactly. The value there must be one number,
        // not three. This is what stops an animation looking different on a 120Hz panel.
        fun valueAt96ms(stepNanos: Long): Float {
            val c = engine()
            val h = c.animator.play(animation("a"))
            var t = 0L
            var i = 0L
            while (t <= 96 * ms) {
                c.tick(frameAt(i, t, if (i == 0L) 0L else stepNanos))
                if (t == 96 * ms) return h.value
                t += stepNanos
                i++
            }
            throw AssertionError("never landed on 96ms with step $stepNanos")
        }

        val at60 = valueAt96ms(16 * ms)     // 96 = 16 * 6
        val at120 = valueAt96ms(8 * ms)     // 96 = 8 * 12
        val at250 = valueAt96ms(4 * ms)     // 96 = 4 * 24

        assertEquals(at60, at120, 0f)
        assertEquals(at60, at250, 0f)
    }

    @Test
    fun irregularFrameSpacingReachesTheSamePlaceAsRegularSpacing() {
        // A busy device delivers frames at uneven intervals. Two animations that arrive at
        // 200ms by different routes must agree, or motion visibly changes under load.
        val c = engine()
        val even = c.animator.play(animation("even"))
        val uneven = c.animator.play(animation("uneven"))

        // Both are in the same engine, so they see identical frames; drive an uneven sequence
        // and check that arriving at 200ms is all that matters.
        val stamps = listOf(0L, 3 * ms, 40 * ms, 41 * ms, 137 * ms, 200 * ms)
        stamps.forEachIndexed { i, t ->
            c.tick(frameAt(i.toLong(), t, if (i == 0) 0L else t - stamps[i - 1]))
        }
        val unevenValue = even.value

        val c2 = engine()
        val steady = c2.animator.play(animation("steady"))
        listOf(0L, 50 * ms, 100 * ms, 150 * ms, 200 * ms).forEachIndexed { i, t ->
            c2.tick(frameAt(i.toLong(), t, if (i == 0) 0L else 50 * ms))
        }

        assertEquals(unevenValue, steady.value, 0f)
        assertEquals(unevenValue, uneven.value, 0f)
    }

    // --- 3. Dropped frames ----------------------------------------------------

    @Test
    fun aDroppedFrameCausesNoDrift() {
        // A 100ms hitch. Progress comes from elapsed time, so the animation is exactly where
        // it should be on the next frame rather than 100ms behind forever.
        val smooth = engine()
        val hitched = engine()
        val a = smooth.animator.play(animation("a"))
        val b = hitched.animator.play(animation("b"))

        var t = 0L
        var i = 0L
        while (t <= 160 * ms) {
            smooth.tick(frameAt(i, t, if (i == 0L) 0L else 16 * ms))
            t += 16 * ms
            i++
        }

        // The same animation, but frames 1..9 never arrived.
        hitched.tick(frameAt(0, 0L, 0L))
        hitched.tick(frameAt(1, 160 * ms, 160 * ms))

        assertEquals(a.value, b.value, 0f)
    }

    // --- 4. Coherence ---------------------------------------------------------

    @Test
    fun animationsStartedOnDifferentFramesShareOneTimestamp() {
        // RULE-011. If each animation built its own FrameTime, animations started a frame apart
        // would disagree about how much time one frame is worth and would slowly separate.
        //
        // Compared as elapsedNanos, not as progress or value, and that distinction is the whole
        // point. Coherence is a statement about TIME: every animation in a frame is handed the
        // same frameTimeNanos, so each gains the same elapsed time, and the ones that started
        // later are behind by exactly the frames they missed. elapsedNanos says that directly
        // and exactly - no recorded history, and no float subtraction to cancel. An earlier
        // version of this test compared recorded progress values instead, where two deltas equal
        // in arithmetic are not always equal in float: 0.048f - 0.032f is 0.015999999 while
        // 0.016f - 0f is 0.016. That cancellation was the test's own subtraction, not the engine
        // drifting, and it had to be fixed twice in Sprint 06A. Comparing elapsedNanos removes
        // the subtraction, and with it the whole class of bug.
        val c = engine()

        val first = c.animator.play(animation("first", durationMs = 1000))
        c.tick(frameAt(0, 0L, 0L))

        val second = c.animator.play(animation("second", durationMs = 1000))
        c.tick(frameAt(1, 16 * ms, 16 * ms))

        val third = c.animator.play(animation("third", durationMs = 1000))
        c.tick(frameAt(2, 32 * ms, 16 * ms))

        repeat(10) {
            val k = 3 + it
            c.tick(frameAt(k.toLong(), (48 + it * 16) * ms, 16 * ms))
            // Coherence is a claim about time. All three were handed the same frameTimeNanos, so
            // each gained the same elapsed, and the ones that started later are behind by exactly
            // the frames they missed. Compared as elapsed, this is exact - no float subtraction.
            assertEquals(second.elapsedNanos + 16 * ms, first.elapsedNanos)
            assertEquals(third.elapsedNanos + 32 * ms, first.elapsedNanos)
        }
    }

    // --- 5. Seeking is repeatable ---------------------------------------------

    @Test
    fun seekingToTheSameProgressAlwaysGivesTheSameValue() {
        val c = engine()
        val h = c.animator.play(animation("a"))
        val spec = h.animation.spec as TimedSpec
        c.tick(frameAt(0, 0L, 0L))

        h.seekToElapsed(spec.elapsedForProgress(0.31f))
        val reference = h.value
        repeat(50) {
            h.seekToElapsed(spec.elapsedForProgress(it / 50f))
            h.seekToElapsed(spec.elapsedForProgress(0.31f))
            assertEquals(reference, h.value, 0f)
        }
    }

    // --- 6. Listener order and reentrancy -------------------------------------

    @Test
    fun animationsStartedFromCallbacksAreOrderedIdenticallyOnReplay() {
        // The direct test of RULE-013. A callback starting B and another starting C must
        // queue them in the same order every time, and neither may advance until the next
        // frame - otherwise where they started would depend on listener order.
        fun once(): List<String> {
            val c = engine()
            val log = mutableListOf<String>()

            fun spawner(name: String, spawn: String) {
                val h = c.animator.play(animation(name, durationMs = 1000))
                var spawned = false
                h.addListener(object : AnimationListener {
                    override fun onUpdate(
                        handle: AnimationHandle,
                        executionId: Long,
                        elapsedNanos: Long,
                        value: Float,
                    ) {
                        log += name
                        if (!spawned) {
                            spawned = true
                            val child = c.animator.play(animation(spawn, durationMs = 1000))
                            child.addListener(object : AnimationListener {
                                override fun onUpdate(
                                    handle: AnimationHandle,
                                    executionId: Long,
                                    elapsedNanos: Long,
                                    value: Float,
                                ) {
                                    log += spawn
                                }
                            })
                        }
                    }
                })
            }

            spawner("a1", "b")
            spawner("a2", "c")

            c.tick(frameAt(0, 0L, 0L))
            c.tick(frameAt(1, 16 * ms, 16 * ms))
            return log
        }

        val reference = once()
        // b and c are started during frame 0 and must first advance in frame 1, in the order
        // they were queued.
        assertEquals(listOf("a1", "a2", "a1", "a2", "b", "c"), reference)
        repeat(20) { assertEquals(reference, once()) }
    }

    @Test
    fun restartingFromACallbackDoesNotAdvanceTwiceInOneFrame() {
        // The one-running-execution invariant: no frame ever produces two updates for one
        // handle, even across a mid-frame restart.
        val c = engine()
        var updates = 0
        var restarted = false
        val h = c.animator.create(animation("a", durationMs = 1000))
        h.addListener(object : AnimationListener {
            override fun onUpdate(
                handle: AnimationHandle,
                executionId: Long,
                elapsedNanos: Long,
                value: Float,
            ) {
                updates++
                if (!restarted) {
                    restarted = true
                    handle.restart()
                }
            }
        })
        h.play()

        c.tick(frameAt(0, 0L, 0L))
        assertEquals("one frame, one update", 1, updates)

        c.tick(frameAt(1, 16 * ms, 16 * ms))
        assertEquals(2, updates)
    }

    // --- 7. The engine holds no clock -----------------------------------------

    @Test
    fun noAnimationClassTakesAClock() {
        // RULE-008 by construction, checked so it stays that way. If a constructor ever
        // accepts an AuroraClock, the engine can read time behind the frame contract and
        // every guarantee above becomes a matter of discipline instead of structure.
        val engineClasses = listOf(
            AnimationDriver::class.java,
            DefaultAnimationController::class.java,
            DefaultAnimator::class.java,
            AnimationHandleImpl::class.java,
            AnimationRegistry::class.java,
            ExecutionTimeline::class.java,
            TimedSampler::class.java,
            AnimationStateMachine::class.java,
        )
        engineClasses.forEach { type ->
            type.declaredConstructors.forEach { constructor ->
                constructor.parameterTypes.forEach { parameter ->
                    assertTrue(
                        "${type.simpleName} takes ${parameter.simpleName}; the animation " +
                            "engine must not be able to read a clock (RULE-008)",
                        !AuroraClock::class.java.isAssignableFrom(parameter)
                    )
                }
            }
            type.declaredFields.forEach { field ->
                assertTrue(
                    "${type.simpleName}.${field.name} is a clock (RULE-008)",
                    !AuroraClock::class.java.isAssignableFrom(field.type)
                )
            }
        }
    }
}
