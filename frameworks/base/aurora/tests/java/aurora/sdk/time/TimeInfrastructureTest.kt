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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Time infrastructure, exercised entirely on a host JVM.
 *
 * Not one of these tests sleeps. That is the whole point of Sprint 05.5: with the clock and the
 * frame source behind seams, timing behaviour is asserted exactly rather than approximately,
 * and the suite runs in milliseconds instead of real time.
 */
class TimeInfrastructureTest {

    private val ms = AuroraClock.NANOS_PER_MILLI

    private lateinit var clock: TestClock
    private lateinit var scheduler: FakeFrameScheduler
    private lateinit var driver: TimelineDriver

    @Before
    fun setUp() {
        clock = TestClock()
        scheduler = FakeFrameScheduler(clock)
        driver = TimelineDriver(clock, scheduler)
    }

    // --- clock ---------------------------------------------------------------

    @Test
    fun testClockOnlyMovesWhenTold() {
        assertEquals(0L, clock.nowNanos())
        clock.advanceMillis(150)
        assertEquals(150 * ms, clock.nowNanos())
        assertEquals(150L, clock.nowMillis())
    }

    @Test
    fun testClockRefusesToGoBackwards() {
        try {
            clock.advanceNanos(-1)
            fail("a monotonic clock must not accept a negative step")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun realtimeClockIsMonotonic() {
        val first = RealtimeClock.nowNanos()
        val second = RealtimeClock.nowNanos()
        assertTrue("nanoTime must never go backwards", second >= first)
    }

    @Test
    fun conversionHelpersRoundTrip() {
        assertEquals(16_000_000L, AuroraClock.millisToNanos(16))
        assertEquals(16L, AuroraClock.nanosToMillis(16_999_999L))
    }

    // --- timeline: duration and delay ----------------------------------------

    @Test
    fun progressRunsZeroToOneOverTheDuration() {
        val t = Timeline.ofMillis(100)
        assertEquals(0f, t.progressAt(0), 0.0001f)
        assertEquals(0.25f, t.progressAt(25 * ms), 0.0001f)
        assertEquals(0.5f, t.progressAt(50 * ms), 0.0001f)
        assertEquals(1f, t.progressAt(100 * ms), 0.0001f)
    }

    @Test
    fun progressStaysAtZeroThroughTheDelay() {
        val t = Timeline(durationNanos = 100 * ms, delayNanos = 50 * ms)
        assertEquals(0f, t.progressAt(0), 0.0001f)
        assertEquals(0f, t.progressAt(49 * ms), 0.0001f)
        assertEquals(0f, t.progressAt(50 * ms), 0.0001f)
        assertEquals(0.5f, t.progressAt(100 * ms), 0.0001f)
        assertEquals(1f, t.progressAt(150 * ms), 0.0001f)
    }

    @Test
    fun progressIsClampedAfterTheEnd() {
        val t = Timeline.ofMillis(100)
        assertEquals(1f, t.progressAt(1000 * ms), 0.0001f)
    }

    @Test
    fun zeroDurationCompletesImmediately() {
        val t = Timeline(durationNanos = 0L, delayNanos = 10 * ms)
        assertEquals(0f, t.progressAt(5 * ms), 0.0001f)
        assertEquals(1f, t.progressAt(10 * ms), 0.0001f)
        assertTrue(t.isFinishedAt(10 * ms))
    }

    @Test
    fun totalIncludesDelayAndRepeats() {
        val t = Timeline(durationNanos = 100 * ms, delayNanos = 50 * ms, repeatCount = 2)
        assertEquals(350 * ms, t.totalNanos)
    }

    // --- timeline: repeat and reverse -----------------------------------------

    @Test
    fun repeatRestartsFromZero() {
        val t = Timeline(durationNanos = 100 * ms, repeatCount = 1)
        assertEquals(0.5f, t.progressAt(50 * ms), 0.0001f)
        assertEquals(0.5f, t.progressAt(150 * ms), 0.0001f)
        assertEquals(0, t.iterationAt(50 * ms))
        assertEquals(1, t.iterationAt(150 * ms))
        assertTrue(t.isFinishedAt(200 * ms))
    }

    @Test
    fun reverseOnRepeatPingPongs() {
        val t = Timeline(durationNanos = 100 * ms, repeatCount = 1, reverseOnRepeat = true)
        assertEquals(0.25f, t.progressAt(25 * ms), 0.0001f)
        // Second iteration runs backwards.
        assertEquals(0.75f, t.progressAt(125 * ms), 0.0001f)
        // Having ended on a reversed iteration, it holds 0 rather than 1.
        assertEquals(0f, t.progressAt(500 * ms), 0.0001f)
    }

    @Test
    fun reverseWithEvenRepeatCountEndsAtOne() {
        val t = Timeline(durationNanos = 100 * ms, repeatCount = 2, reverseOnRepeat = true)
        assertEquals(1f, t.progressAt(500 * ms), 0.0001f)
    }

    @Test
    fun infiniteTimelineNeverFinishes() {
        val t = Timeline(durationNanos = 100 * ms, repeatCount = Timeline.REPEAT_INFINITE)
        assertTrue(t.isInfinite)
        assertFalse(t.isFinishedAt(1_000_000 * ms))
        assertEquals(0.5f, t.progressAt(1050 * ms), 0.0001f)
        assertEquals(10, t.iterationAt(1050 * ms))
    }

    @Test
    fun timelineIsStatelessSoSeekingIsFree() {
        val t = Timeline.ofMillis(100)
        // Queried out of order; each answer depends only on its argument.
        assertEquals(1f, t.progressAt(100 * ms), 0.0001f)
        assertEquals(0.1f, t.progressAt(10 * ms), 0.0001f)
        assertEquals(0.9f, t.progressAt(90 * ms), 0.0001f)
        assertEquals(0.1f, t.progressAt(10 * ms), 0.0001f)
    }

    @Test
    fun negativeParametersAreRejected() {
        try {
            Timeline(durationNanos = -1)
            fail("negative duration should be rejected")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            Timeline(durationNanos = 1, delayNanos = -1)
            fail("negative delay should be rejected")
        } catch (expected: IllegalArgumentException) {
        }
    }

    // --- frame scheduler ------------------------------------------------------

    @Test
    fun callbackRunsOnTheNextFrameOnly() {
        var frames = 0
        scheduler.postFrame { frames++ }

        assertEquals(1, scheduler.pendingCount)
        scheduler.advanceOneFrame()
        assertEquals(1, frames)

        // One callback means one frame; it is not a subscription.
        scheduler.advanceOneFrame()
        assertEquals(1, frames)
    }

    @Test
    fun disposingBeforeTheFrameCancelsTheCallback() {
        var frames = 0
        val token = scheduler.postFrame { frames++ }
        token.dispose()

        scheduler.advanceOneFrame()

        assertEquals(0, frames)
        assertTrue(token.isDisposed)
    }

    @Test
    fun frameTimeAdvancesByTheFrameInterval() {
        val times = mutableListOf<Long>()
        scheduler.postFrame { t -> times.add(t); scheduler.postFrame { t2 -> times.add(t2) } }

        scheduler.advanceOneFrame()
        scheduler.advanceOneFrame()

        assertEquals(2, times.size)
        assertEquals(scheduler.frameIntervalNanos, times[1] - times[0])
    }

    @Test
    fun aCallbackPostedDuringAFrameWaitsForTheNext() {
        var runs = 0
        scheduler.postFrame(object : FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                runs++
                scheduler.postFrame(this)
            }
        })

        // Without this rule a self-reposting animation would recurse until the stack ran out.
        scheduler.advanceOneFrame()
        assertEquals(1, runs)
        scheduler.advanceOneFrame()
        assertEquals(2, runs)
    }

    // --- driver ---------------------------------------------------------------

    @Test
    fun driverReportsProgressEachFrameAndFinishes() {
        val seen = mutableListOf<Float>()
        var completed: Boolean? = null
        // Six frames at 16.67ms covers 100ms.
        driver.start(Timeline.ofMillis(100), onUpdate = { seen.add(it) }, onFinished = { completed = it })

        assertEquals(1, driver.activeCount)
        val frames = scheduler.runToIdle()

        assertTrue("should take about six frames, took $frames", frames in 5..7)
        assertTrue(seen.isNotEmpty())
        assertTrue("progress must not go backwards", seen.zipWithNext().all { it.second >= it.first })
        assertEquals(1f, seen.last(), 0.0001f)
        assertEquals(true, completed)
        assertEquals(0, driver.activeCount)
    }

    @Test
    fun driverStopsWhereItIsWhenDisposed() {
        val seen = mutableListOf<Float>()
        var completed: Boolean? = null
        val token = driver.start(
            Timeline.ofMillis(100),
            onUpdate = { seen.add(it) },
            onFinished = { completed = it },
        )

        scheduler.advanceOneFrame()
        val atCancel = seen.last()
        token.dispose()
        scheduler.advanceFrames(10)

        // Cancelling must not jump to the end value: the interface should stay where the user
        // last saw it.
        assertEquals(atCancel, seen.last(), 0.0001f)
        assertTrue(atCancel < 1f)
        assertEquals(false, completed)
        assertEquals(0, driver.activeCount)
        assertTrue(token.isDisposed)
    }

    @Test
    fun onFinishedFiresExactlyOnce() {
        var calls = 0
        val token = driver.start(Timeline.ofMillis(50), onUpdate = { }, onFinished = { calls++ })
        scheduler.runToIdle()
        token.dispose()
        token.dispose()

        assertEquals(1, calls)
    }

    @Test
    fun delayIsHonouredBeforeAnyProgress() {
        val seen = mutableListOf<Float>()
        driver.start(
            Timeline(durationNanos = 50 * ms, delayNanos = 50 * ms),
            onUpdate = { seen.add(it) },
        )

        scheduler.advanceFrames(2) // ~33ms, still inside the delay
        assertTrue("nothing should move during the delay", seen.all { it == 0f })

        scheduler.runToIdle()
        assertEquals(1f, seen.last(), 0.0001f)
    }

    @Test
    fun severalTimelinesRunIndependently() {
        var a = 0f
        var b = 0f
        driver.start(Timeline.ofMillis(50), onUpdate = { a = it })
        driver.start(Timeline.ofMillis(200), onUpdate = { b = it })
        assertEquals(2, driver.activeCount)

        scheduler.runToIdle()

        assertEquals(1f, a, 0.0001f)
        assertEquals(1f, b, 0.0001f)
        assertEquals(0, driver.activeCount)
    }
}
