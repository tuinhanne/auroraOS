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
import aurora.sdk.animation.AnimationState
import aurora.sdk.animation.Interpolator
import aurora.sdk.animation.SpringSpec
import aurora.sdk.animation.TimedSpec
import aurora.sdk.time.AuroraClock
import aurora.sdk.time.FrameTime
import aurora.sdk.time.Timeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The lifecycle of one animation, driven frame by frame with no clock and no device.
 *
 * Every timestamp here is a number the test chose. Nothing sleeps, nothing samples a clock,
 * and every assertion is exact -- which is the whole reason RULE-008 hands frame time to the
 * engine rather than letting it fetch its own.
 */
class AnimationLifecycleTest {

    private val ms = AuroraClock.NANOS_PER_MILLI

    // --- ExecutionTimeline ---------------------------------------------------

    @Test
    fun elapsedIsMeasuredFromTheFirstFrameNotFromZero() {
        // Frame timestamps come from a monotonic source with an arbitrary origin, often a
        // large number. An execution that treated the raw timestamp as elapsed would finish
        // instantly.
        val t = ExecutionTimeline()
        assertEquals(0L, t.advanceTo(9_000_000_000L))
        assertEquals(16 * ms, t.advanceTo(9_000_000_000L + 16 * ms))
    }

    @Test
    fun elapsedTracksTheFrameTimestampsRatherThanCountingFrames()
    {
        // A dropped frame makes the real gap a multiple of the nominal interval. Counting
        // frames would drift; measuring from timestamps cannot.
        val t = ExecutionTimeline()
        t.advanceTo(0L)
        t.advanceTo(16 * ms)
        assertEquals(116 * ms, t.advanceTo(116 * ms))   // 100ms hitch, one frame delivered
    }

    @Test
    fun aPauseCostsNoElapsedTime() {
        // The heart of pause. Time passes in the world while an animation is held; none of it
        // belongs to the animation.
        val t = ExecutionTimeline()
        t.advanceTo(0L)
        assertEquals(50 * ms, t.advanceTo(50 * ms))

        t.pause()
        // 500ms of wall time goes by. The handle is not ticked while paused.
        t.resume()

        // The first frame after resuming reports the elapsed time it was paused at.
        assertEquals(50 * ms, t.advanceTo(550 * ms))
        // And then time advances normally again.
        assertEquals(66 * ms, t.advanceTo(566 * ms))
    }

    @Test
    fun pausingBeforeTheFirstFrameLosesNothing() {
        // play() then pause() before a frame arrives is legal (see the state machine). There
        // is no elapsed time to preserve, so the first frame after resuming is the first frame.
        val t = ExecutionTimeline()
        t.pause()
        t.resume()
        assertEquals(0L, t.advanceTo(1_000 * ms))
        assertEquals(16 * ms, t.advanceTo(1_016 * ms))
    }

    @Test
    fun repeatedPausesAndResumesEachCostNothing() {
        val t = ExecutionTimeline()
        t.advanceTo(0L)
        t.advanceTo(10 * ms)

        t.pause(); t.resume()
        assertEquals(10 * ms, t.advanceTo(100 * ms))

        t.pause(); t.resume()
        assertEquals(10 * ms, t.advanceTo(500 * ms))

        assertEquals(26 * ms, t.advanceTo(516 * ms))
    }

    @Test
    fun seekingBeforeTheFirstFrameStartsThere() {
        val t = ExecutionTimeline()
        t.seekTo(80 * ms)
        assertEquals(80 * ms, t.elapsedNanos)
        assertEquals(80 * ms, t.advanceTo(5_000 * ms))
        assertEquals(96 * ms, t.advanceTo(5_016 * ms))
    }

    @Test
    fun seekingAfterStartingMovesTheOrigin() {
        val t = ExecutionTimeline()
        t.advanceTo(0L)
        t.advanceTo(30 * ms)
        t.seekTo(200 * ms)
        assertEquals(200 * ms, t.elapsedNanos)
        assertEquals(216 * ms, t.advanceTo(46 * ms))
    }

    @Test
    fun seekingWhilePausedSurvivesTheResume() {
        // Scrubbing a paused animation. If the resume shift and the seek both applied, the
        // position would jump by the length of the pause the moment the finger lifted.
        val t = ExecutionTimeline()
        t.advanceTo(0L)
        t.advanceTo(30 * ms)
        t.pause()
        t.seekTo(500 * ms)
        t.resume()
        assertEquals(500 * ms, t.advanceTo(9_000 * ms))
    }

    @Test
    fun seekingBackwardsIsAllowedBecauseTheClockIsNotMoving() {
        // RULE-006 forbids a clock going backwards. An execution timeline is not a clock: it
        // is a position, and moving a position back is what scrubbing means.
        val t = ExecutionTimeline()
        t.advanceTo(0L)
        t.advanceTo(200 * ms)
        t.seekTo(50 * ms)
        assertEquals(50 * ms, t.elapsedNanos)
    }

    @Test
    fun seekingToANegativeElapsedIsRejected() {
        try {
            ExecutionTimeline().seekTo(-1L)
            fail("an execution cannot be positioned before it began")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun tickingAPausedExecutionIsRejected() {
        // Without this the paused interval would be counted as elapsed - a plausible-looking
        // wrong number rather than a failure. The state machine makes it unreachable; the class
        // does not rely on that.
        val t = ExecutionTimeline()
        t.advanceTo(0L)
        t.pause()
        try {
            t.advanceTo(500 * ms)
            fail("a paused execution must not advance")
        } catch (expected: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun pausingBeforeTheFirstFrameAlsoRefusesToBeTicked() {
        // The pause-before-start case, which cannot be inferred from the frame bookkeeping and
        // is why the flag is explicit.
        val t = ExecutionTimeline()
        t.pause()
        try {
            t.advanceTo(0L)
            fail("a paused execution must not advance, started or not")
        } catch (expected: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun aFrameTimeThatGoesBackwardsIsRejected() {
        val t = ExecutionTimeline()
        t.advanceTo(100 * ms)
        try {
            t.advanceTo(50 * ms)
            fail("frame time is monotonic (RULE-006)")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun theSameFrameTimeTwiceIsAllowed() {
        // Two animations advanced to one frame is normal; only going backwards is not. Repeating
        // a timestamp must leave elapsed where it was, because no time has passed.
        //
        // The second frame is the one repeated, not the first. Repeating the first would compare
        // 0 against 0 and pass no matter what the class did, since the opening frame is what
        // establishes the origin.
        val t = ExecutionTimeline()
        t.advanceTo(100 * ms)
        assertEquals(16 * ms, t.advanceTo(116 * ms))
        assertEquals(16 * ms, t.advanceTo(116 * ms))
    }

    @Test
    fun resumingClearsThePauseSoTickingWorksAgain() {
        val t = ExecutionTimeline()
        t.advanceTo(0L)
        t.pause()
        t.resume()
        assertEquals(0L, t.advanceTo(500 * ms))
    }

    @Test
    fun resetClearsAPauseSoTheNextExecutionCanRun() {
        // restart() while paused must not leave the new execution unable to advance.
        val t = ExecutionTimeline()
        t.advanceTo(0L)
        t.pause()
        t.reset()
        assertEquals(0L, t.advanceTo(9_000 * ms))
    }

    @Test
    fun resetReturnsToAFreshExecution() {
        // restart() reuses the handle, so this has to leave nothing behind. A leftover origin
        // would make execution 2 start part-way through.
        val t = ExecutionTimeline()
        t.advanceTo(0L)
        t.advanceTo(200 * ms)
        t.pause()
        t.seekTo(90 * ms)

        t.reset()

        assertFalse(t.hasStarted)
        assertEquals(0L, t.elapsedNanos)
        assertEquals(0L, t.advanceTo(7_000 * ms))
        assertEquals(16 * ms, t.advanceTo(7_016 * ms))
    }

    @Test
    fun hasStartedIsFalseUntilTheFirstFrame() {
        val t = ExecutionTimeline()
        assertFalse(t.hasStarted)
        t.advanceTo(0L)
        assertTrue(t.hasStarted)
    }

    // --- TimedSampler -------------------------------------------------------

    @Test
    fun timedProgressIsLinearInElapsedTime() {
        val s = TimedSampler(TimedSpec(Timeline.ofMillis(200)))
        assertEquals(0f, s.sampleAt(0L).value, 1e-6f)
        assertEquals(0.5f, s.sampleAt(100 * ms).value, 1e-6f)
        assertEquals(1f, s.sampleAt(200 * ms).value, 1e-6f)
    }

    @Test
    fun easedProgressAppliesTheInterpolatorAndProgressDoesNot() {
        // The two must not be conflated. progress is what the timeline says; easedProgress is
        // what the curve makes of it, and the value a caller sees comes from the latter.
        val square = Interpolator { it * it }
        val s = TimedSampler(TimedSpec(Timeline.ofMillis(200), square))
        assertEquals(0.25f, s.sampleAt(100 * ms).value, 1e-6f)
    }

    @Test
    fun theSameElapsedByDifferentRoutesAgrees() {
        // Progress comes from elapsed time, never from accumulating deltas. Two runs reaching
        // the same elapsed time by different routes must agree, or a dropped frame drifts.
        val spec = TimedSpec(Timeline.ofMillis(200))
        val smooth = TimedSampler(spec)
        val hitched = TimedSampler(spec)

        assertEquals(smooth.sampleAt(100 * ms).value, hitched.sampleAt(100 * ms).value, 0f)
    }

    @Test
    fun isFinishedFollowsTheTimeline() {
        val spec = TimedSpec(Timeline.ofMillis(200))
        val s = TimedSampler(spec)
        assertFalse(spec.isFinished(199 * ms, s.sampleAt(199 * ms)))
        assertTrue(spec.isFinished(200 * ms, s.sampleAt(200 * ms)))
    }

    @Test
    fun aDelayHoldsProgressAtZero() {
        val spec = TimedSpec(Timeline(durationNanos = 200 * ms, delayNanos = 100 * ms))
        val s = TimedSampler(spec)
        assertEquals(0f, s.sampleAt(50 * ms).value, 1e-6f)
        assertFalse(spec.isFinished(50 * ms, s.sampleAt(50 * ms)))
        assertEquals(0.5f, s.sampleAt(200 * ms).value, 1e-6f)
    }

    @Test
    fun anInfiniteTimelineNeverFinishes() {
        val spec = TimedSpec(
            Timeline(durationNanos = 100 * ms, repeatCount = Timeline.REPEAT_INFINITE)
        )
        val s = TimedSampler(spec)
        assertFalse(spec.isFinished(10_000 * ms, s.sampleAt(10_000 * ms)))
    }

    @Test
    fun aFreshSamplerStartsAtTheCurveAtZero() {
        // reset() is gone: a sampler is built per execution, so "fresh" is the only state a new
        // one can be in. This is what that test was really asserting.
        //
        // Also pins the initialisation contract: value is computed from the interpolator on
        // every call, and a sampler queried before any other call must already report the
        // shaped value, not a bare 0f.
        val offset = Interpolator { p -> 0.25f + 0.5f * p }
        val spec = TimedSpec(Timeline.ofMillis(200), offset)
        val s = TimedSampler(spec)
        val fresh = s.sampleAt(0L)
        assertEquals(0.25f, fresh.value, 1e-6f)
        assertFalse(spec.isFinished(0L, fresh))
    }

    @Test
    fun anOvershootingCurveSurvivesUnclamped() {
        // MotionSample documents that value may legitimately leave 0..1, and sampleAt() applies
        // no clamp. Nothing else enforces that, so a clamp added later would flatten every
        // bouncy spring in Sprint 06B while still looking correct.
        val overshoot = Interpolator { p -> p * 1.4f }
        val s = TimedSampler(TimedSpec(Timeline.ofMillis(200), overshoot))
        assertEquals(1.4f, s.sampleAt(200 * ms).value, 1e-6f)
    }

    @Test
    fun aPingPongEndingOnAReverseIterationFinishesAtZero() {
        // Surprising and correct. A timeline that reverses on repeat with an odd repeatCount runs
        // its last iteration backwards, so it ends where it started. Written down because it
        // reads like a bug the first time someone sees a finished animation reporting 0.
        val spec = TimedSpec(
            Timeline(durationNanos = 100 * ms, repeatCount = 1, reverseOnRepeat = true)
        )
        val s = TimedSampler(spec)
        val sample = s.sampleAt(200 * ms)
        assertTrue(spec.isFinished(200 * ms, sample))
        assertEquals(0f, sample.value, 1e-6f)
    }

    @Test
    fun velocityIsTheRateOfChangeOfValue() {
        // A linear 200ms animation covers the unit interval in 0.2s, so it moves at 5 per second
        // throughout.
        //
        // The tolerance is loose on purpose. The contract says velocity is the rate of change of
        // value; central finite difference is how this sprint produces it, and the epsilon it
        // uses is an implementation detail. A test tight enough to notice the epsilon changing
        // would be testing the method rather than the quantity, and would fail a sampler that
        // was still perfectly correct.
        val s = TimedSampler(TimedSpec(Timeline.ofMillis(200)))
        assertEquals(5f, s.sampleAt(100 * ms).velocity, 0.25f)
    }

    @Test
    fun velocityIsNearZeroWhereTheCurveIsFlat() {
        // Past the end of the timeline the value holds, so nothing is moving. Same reasoning
        // about tolerance: the claim is "not moving", not a particular numerical zero.
        val s = TimedSampler(TimedSpec(Timeline.ofMillis(200)))
        assertEquals(0f, s.sampleAt(400 * ms).velocity, 0.25f)
    }

    @Test
    fun velocityIsPositiveWhileAdvancingAndTracksTheCurve() {
        // The property, stated without depending on any particular number: an ease-out is fast
        // early and slow late, so its velocity must decrease across the animation. This holds
        // for any correct derivative by any method.
        val easeOut = Interpolator { p -> 1f - (1f - p) * (1f - p) }
        val s = TimedSampler(TimedSpec(Timeline.ofMillis(200), easeOut))
        val early = s.sampleAt(20 * ms).velocity
        val late = s.sampleAt(180 * ms).velocity
        assertTrue("an ease-out starts fast, got $early", early > 0f)
        assertTrue("and slows down, got $early then $late", late < early)
    }

    // --- handle lifecycle ----------------------------------------------------

    private fun registry() = AnimationRegistry()

    private fun handle(
        registry: AnimationRegistry,
        durationMs: Long = 100,
    ): AnimationHandle = DefaultAnimator(registry)
        .create(Animation("test", TimedSpec(Timeline.ofMillis(durationMs))))

    private fun frame(index: Long) = FrameTime(
        frameTimeNanos = index * 16 * ms,
        deltaNanos = 16 * ms,
        frameIndex = index,
    )

    /** Records every callback, so ordering and execution ids can be asserted. */
    private class Recorder : AnimationListener {
        val events = mutableListOf<String>()
        override fun onStateChanged(
            handle: AnimationHandle,
            executionId: Long,
            from: AnimationState,
            to: AnimationState,
        ) {
            events += "state#$executionId:$from->$to"
        }

        override fun onUpdate(
            handle: AnimationHandle,
            executionId: Long,
            elapsedNanos: Long,
            value: Float,
        ) {
            events += "update#$executionId:$elapsedNanos"
        }
    }

    @Test
    fun aFreshHandleIsIdle() {
        assertEquals(AnimationState.IDLE, handle(registry()).state)
    }

    @Test
    fun playSchedulesAndTheFirstFrameStartsIt() {
        val r = registry()
        val h = handle(r)
        h.play()
        assertEquals(AnimationState.SCHEDULED, h.state)
        r.tick(frame(0))
        assertEquals(AnimationState.RUNNING, h.state)
    }

    @Test
    fun theWholeContractSequenceWorks() {
        // play -> pause -> resume -> cancel -> restart -> dispose, exactly as the sprint
        // contract names it.
        val r = registry()
        val h = handle(r, durationMs = 1000)

        h.play();                assertEquals(AnimationState.SCHEDULED, h.state)
        r.tick(frame(0));        assertEquals(AnimationState.RUNNING, h.state)
        h.pause();               assertEquals(AnimationState.PAUSED, h.state)
        h.resume();              assertEquals(AnimationState.RUNNING, h.state)
        h.cancel();              assertEquals(AnimationState.CANCELLED, h.state)
        h.restart();             assertEquals(AnimationState.SCHEDULED, h.state)
        h.dispose();             assertEquals(AnimationState.DISPOSED, h.state)
        assertTrue(h.isDisposed)
    }

    @Test
    fun cancelThenResumeThrows() {
        // The case the sprint contract calls out. Named here as well as in the state machine
        // test, because a handle could still get it wrong on its own.
        val r = registry()
        val h = handle(r)
        h.play()
        r.tick(frame(0))
        h.cancel()
        try {
            h.resume()
            fail("resuming a cancelled animation must fail")
        } catch (expected: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun aCancelledAnimationStaysWhereItWasRatherThanJumpingToTheEnd() {
        // Cancelling mid-flight must not teleport the interface to the end value.
        val r = registry()
        val h = handle(r, durationMs = 100)
        h.play()
        r.tick(frame(0))
        r.tick(frame(3))       // 48ms of a 100ms animation
        val whereItWas = h.value
        h.cancel()
        assertEquals(whereItWas, h.value, 0f)
        assertTrue("expected mid-flight, got $whereItWas", whereItWas > 0f && whereItWas < 1f)
    }

    @Test
    fun aPausedAnimationDoesNotAdvanceWhileTheEngineKeepsTicking() {
        val r = registry()
        val h = handle(r, durationMs = 1000)
        h.play()
        r.tick(frame(0))
        r.tick(frame(1))
        val held = h.normalizedPosition
        h.pause()

        repeat(30) { r.tick(frame(2L + it)) }
        assertEquals(held, h.normalizedPosition, 0f)

        h.resume()
        r.tick(frame(40))
        assertEquals("resuming loses no elapsed time", held, h.normalizedPosition, 1e-6f)
        r.tick(frame(41))
        assertTrue(h.normalizedPosition > held)
    }

    @Test
    fun aCompletedAnimationLeavesTheRegistry() {
        val r = registry()
        val h = handle(r, durationMs = 32)
        h.play()
        r.tick(frame(0))
        r.tick(frame(1))
        r.tick(frame(2))
        assertEquals(AnimationState.COMPLETED, h.state)
        assertEquals(0, r.size)
    }

    // --- RULE-012: execution identity -----------------------------------------

    @Test
    fun restartIncrementsTheExecutionIdButKeepsTheHandle() {
        val r = registry()
        val h = handle(r)
        h.play()
        assertEquals(0L, h.executionId)
        h.restart()
        assertEquals(1L, h.executionId)
        h.restart()
        assertEquals(2L, h.executionId)
    }

    @Test
    fun aListenerCanTellWhichExecutionAnEventBelongsTo() {
        // The bug this prevents: a component holding state across runs acts on a callback
        // from a run it already abandoned. Without the id there is nothing in the callback
        // to say which run it is.
        val r = registry()
        val h = handle(r, durationMs = 1000)
        val rec = Recorder()
        h.addListener(rec)

        h.play()
        r.tick(frame(0))
        h.restart()
        r.tick(frame(1))
        r.tick(frame(2))

        assertTrue(rec.events.any { it.startsWith("update#0") })
        assertTrue(rec.events.any { it.startsWith("update#1") })
        val firstOfExecutionOne = rec.events.indexOfFirst { it.startsWith("update#1") }
        val lastOfExecutionZero = rec.events.indexOfLast { it.startsWith("update#0") }
        assertTrue(
            "execution 0 events must all precede execution 1 events",
            lastOfExecutionZero < firstOfExecutionOne
        )
    }

    @Test
    fun restartStartsTheNewExecutionFromTheBeginning() {
        val r = registry()
        val h = handle(r, durationMs = 100)
        h.play()
        r.tick(frame(0))
        r.tick(frame(4))
        assertTrue(h.normalizedPosition > 0.5f)

        h.restart()
        r.tick(frame(5))
        assertEquals("a new execution starts at zero", 0f, h.normalizedPosition, 1e-6f)
    }

    @Test
    fun aCompletedHandleIsRestartable() {
        // RULE-012: the volume slider re-runs one handle instead of allocating per key press.
        val r = registry()
        val h = handle(r, durationMs = 16)
        h.play()
        r.tick(frame(0))
        r.tick(frame(1))
        r.tick(frame(2))
        assertEquals(AnimationState.COMPLETED, h.state)

        h.restart()
        assertEquals(AnimationState.SCHEDULED, h.state)
        r.tick(frame(3))
        assertEquals(AnimationState.RUNNING, h.state)
    }

    // --- dispose --------------------------------------------------------------

    @Test
    fun everyMutatingCallAfterDisposeThrows() {
        val r = registry()
        val h = handle(r)
        h.dispose()

        listOf<Pair<String, () -> Unit>>(
            "play" to { h.play() },
            "pause" to { h.pause() },
            "resume" to { h.resume() },
            "cancel" to { h.cancel() },
            "restart" to { h.restart() },
            "seekToElapsed" to { h.seekToElapsed(0L) },
        ).forEach { (name, call) ->
            try {
                call()
                fail("$name after dispose must throw")
            } catch (expected: IllegalStateException) {
                // expected
            }
        }
    }

    @Test
    fun everyQueryAfterDisposeStillAnswers() {
        // Queries never throw, in any state. A teardown path reading normalizedPosition to log
        // it must not become the thing that crashes teardown.
        val r = registry()
        val h = handle(r)
        h.play()
        r.tick(frame(0))
        h.dispose()

        assertEquals(AnimationState.DISPOSED, h.state)
        assertTrue(h.isDisposed)
        assertFalse(h.isRunning)
        assertEquals(0L, h.executionId)
        h.normalizedPosition
        h.value
        h.animation
    }

    @Test
    fun disposeIsIdempotent() {
        val h = handle(registry())
        h.dispose()
        h.dispose()
        assertEquals(AnimationState.DISPOSED, h.state)
    }

    @Test
    fun cancelIsIdempotent() {
        val h = handle(registry())
        h.play()
        h.cancel()
        h.cancel()
        assertEquals(AnimationState.CANCELLED, h.state)
    }

    @Test
    fun aDisposedHandleIsNeverTickedAgain() {
        // The frozen contract, asserted directly: after dispose() returns, no further tick.
        //
        // Counting updates rather than all events, because disposal itself IS announced. The
        // state change is published before the listener array is cleared, deliberately, so a
        // component hears about the teardown it caused. That is a state change, not a tick, and
        // conflating the two would make this test forbid a behaviour the design chose.
        val r = registry()
        val h = handle(r, durationMs = 1000)
        val rec = Recorder()
        h.addListener(rec)
        h.play()
        r.tick(frame(0))
        val updatesBeforeDispose = rec.events.count { it.startsWith("update") }

        h.dispose()
        assertTrue(
            "a listener hears about the disposal, got ${rec.events.last()}",
            rec.events.last().endsWith("->DISPOSED")
        )

        repeat(10) { r.tick(frame(1L + it)) }

        assertEquals(
            "a disposed handle receives no further ticks",
            updatesBeforeDispose,
            rec.events.count { it.startsWith("update") }
        )
        assertEquals(0, r.size)
    }

    // --- seek -----------------------------------------------------------------

    @Test
    fun seekingMovesTheExecutionAndPublishesImmediately() {
        val r = registry()
        val spec = TimedSpec(Timeline.ofMillis(100))
        val h = DefaultAnimator(r).create(Animation("test", spec))
        h.play()
        r.tick(frame(0))
        h.seekToElapsed(spec.elapsedForProgress(0.25f))
        assertEquals(0.25f, h.normalizedPosition, 1e-6f)
        assertEquals(0.25f, h.value, 1e-6f)
    }

    @Test
    fun seekingTwiceToTheSameProgressGivesTheSameValue() {
        // RULE-009 at the API surface. Timeline is stateless, so this holds by construction;
        // the test is here to notice if it stops.
        val r = registry()
        val spec = TimedSpec(Timeline.ofMillis(100))
        val h = DefaultAnimator(r).create(Animation("test", spec))
        h.play()
        r.tick(frame(0))
        h.seekToElapsed(spec.elapsedForProgress(0.4f))
        val first = h.value
        h.seekToElapsed(spec.elapsedForProgress(0.9f))
        h.seekToElapsed(spec.elapsedForProgress(0.4f))
        assertEquals(first, h.value, 0f)
    }

    @Test
    fun seekIsLegalWhileScheduledAndWhilePaused() {
        val r = registry()
        val spec = TimedSpec(Timeline.ofMillis(100))
        val h = DefaultAnimator(r).create(Animation("test", spec))
        h.play()
        h.seekToElapsed(spec.elapsedForProgress(0.5f))                       // SCHEDULED
        assertEquals(0.5f, h.normalizedPosition, 1e-6f)

        r.tick(frame(0))
        h.pause()
        h.seekToElapsed(spec.elapsedForProgress(0.75f))                      // PAUSED
        assertEquals(0.75f, h.normalizedPosition, 1e-6f)
    }

    @Test
    fun seekIsIllegalOnceTheExecutionHasEnded() {
        val r = registry()
        val h = handle(r, durationMs = 16)
        h.play()
        r.tick(frame(0))
        r.tick(frame(1))
        r.tick(frame(2))
        assertEquals(AnimationState.COMPLETED, h.state)
        try {
            h.seekToElapsed(0L)
            fail("a finished execution has no position to move")
        } catch (expected: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun seekingBeforeTheExecutionBeganIsRejected() {
        // The unit-range check went with seek(progress). Elapsed has one bound, not two.
        val r = registry()
        val h = handle(r)
        h.play()
        try {
            h.seekToElapsed(-1L)
            fail("an execution cannot be positioned before it began")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    // --- physics is declared but not yet solved -------------------------------

    @Test
    fun aPhysicsAnimationIsRejectedWithAMessageNamingTheSprint() {
        val animator = DefaultAnimator(registry())
        try {
            animator.create(Animation("spring", SpringSpec()))
            fail("06A has no solver; the failure must be loud")
        } catch (expected: UnsupportedOperationException) {
            assertTrue(
                "the message must say where the solver is coming from, got: ${expected.message}",
                expected.message!!.contains("06B")
            )
        }
    }

    // --- animator -------------------------------------------------------------

    @Test
    fun playIsCreateFollowedByPlay() {
        val r = registry()
        val h = DefaultAnimator(r).play(Animation("x", TimedSpec(Timeline.ofMillis(100))))
        assertEquals(AnimationState.SCHEDULED, h.state)
    }

    @Test
    fun activeCountCountsScheduledAndRunningOnly() {
        val r = registry()
        val animator = DefaultAnimator(r)
        val a = animator.play(Animation("a", TimedSpec(Timeline.ofMillis(1000))))
        animator.play(Animation("b", TimedSpec(Timeline.ofMillis(1000))))
        assertEquals(2, animator.activeCount)

        a.pause()
        assertEquals("a paused animation is not being advanced", 1, animator.activeCount)

        a.resume()
        assertEquals(2, animator.activeCount)
    }

    @Test
    fun cancelAllCancelsButLeavesTheHandlesUsable() {
        val r = registry()
        val animator = DefaultAnimator(r)
        val a = animator.play(Animation("a", TimedSpec(Timeline.ofMillis(1000))))
        val b = animator.play(Animation("b", TimedSpec(Timeline.ofMillis(1000))))

        animator.cancelAll()

        assertEquals(AnimationState.CANCELLED, a.state)
        assertEquals(AnimationState.CANCELLED, b.state)
        assertEquals(0, animator.activeCount)

        a.restart()
        assertEquals(AnimationState.SCHEDULED, a.state)
    }

    @Test
    fun aListenerCanBeRemoved() {
        val r = registry()
        val h = handle(r, durationMs = 1000)
        val rec = Recorder()
        val subscription = h.addListener(rec)
        h.play()
        r.tick(frame(0))
        val before = rec.events.size

        subscription.dispose()
        r.tick(frame(1))
        assertEquals(before, rec.events.size)
        assertTrue(subscription.isDisposed)
    }

    // --- re-entrancy: a listener acting on the handle it observes -------------

    @Test
    fun everyCallbackTripleDescribesOneRealExecution() {
        // The regression test for the bug this pattern hides: a listener that restarts the
        // handle from inside onUpdate must not leave a LATER listener holding the previous
        // execution's id beside the new execution's numbers.
        //
        // Ticking to a frame where elapsedNanos is already non-zero before the restart fires is
        // what makes this test able to fail: at elapsed 0 the pre- and post-restart elapsed are
        // both 0L by coincidence, so a version built on that one frame alone cannot tell
        // captured values from live ones that happen to match. Advancing once first, then
        // attaching the listeners and restarting from the following tick, gives the two paths
        // different numbers.
        val r = registry()
        val h = handle(r, durationMs = 32)
        h.play()
        r.tick(frame(0))

        val seen = mutableListOf<Triple<Long, Long, Float>>()
        var restarted = false
        h.addListener(object : AnimationListener {
            override fun onUpdate(
                handle: AnimationHandle,
                executionId: Long,
                elapsedNanos: Long,
                value: Float,
            ) {
                if (!restarted) {
                    restarted = true
                    handle.restart()
                }
            }
        })
        h.addListener(object : AnimationListener {
            override fun onUpdate(
                handle: AnimationHandle,
                executionId: Long,
                elapsedNanos: Long,
                value: Float,
            ) {
                seen += Triple(executionId, elapsedNanos, value)
            }
        })

        r.tick(frame(1))       // 16ms of 32ms: elapsed 16ms before the first listener restarts it

        // The second listener is called during the dispatch the first one restarted from.
        // Whatever id it is given, the elapsed and value must be the ones that id's execution
        // actually reached this frame - not the zero the restart just reset the live fields to.
        assertEquals(1, seen.size)
        val (id, elapsedNanos, value) = seen.single()
        assertEquals("the restart must not rewrite the numbers this id was paired with", 0L, id)
        assertEquals(16 * ms, elapsedNanos)
        assertEquals(0.5f, value, 1e-6f)
    }

    @Test
    fun aListenerMayRestartTheHandleItObserves() {
        // Re-entrancy at the handle layer, which nothing exercised before. The restart takes
        // effect, and RULE-013 keeps the new execution off this frame.
        val r = registry()
        val h = handle(r, durationMs = 1000)
        var restarts = 0
        h.addListener(object : AnimationListener {
            override fun onUpdate(
                handle: AnimationHandle,
                executionId: Long,
                elapsedNanos: Long,
                value: Float,
            ) {
                if (restarts == 0) {
                    restarts++
                    handle.restart()
                }
            }
        })
        h.play()
        r.tick(frame(0))
        assertEquals(1L, h.executionId)
        assertEquals(AnimationState.SCHEDULED, h.state)

        r.tick(frame(1))
        assertEquals(AnimationState.RUNNING, h.state)
    }

    @Test
    fun aListenerMayDisposeTheHandleItObserves() {
        val r = registry()
        val h = handle(r, durationMs = 1000)
        h.addListener(object : AnimationListener {
            override fun onUpdate(
                handle: AnimationHandle,
                executionId: Long,
                elapsedNanos: Long,
                value: Float,
            ) {
                handle.dispose()
            }
        })
        h.play()
        r.tick(frame(0))
        assertTrue(h.isDisposed)
        assertEquals(0, r.size)
        // And the frame that disposed it completes without the registry throwing.
        r.tick(frame(1))
    }
}
