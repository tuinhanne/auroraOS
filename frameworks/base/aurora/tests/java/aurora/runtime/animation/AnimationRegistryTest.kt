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

import aurora.runtime.time.QueuedFrameScheduler
import aurora.runtime.time.TestClock
import aurora.sdk.animation.Animation
import aurora.sdk.animation.AnimationState
import aurora.sdk.animation.TimedSpec
import aurora.sdk.time.FrameTime
import aurora.sdk.time.Timeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * RULE-013, proven against a fake tickable.
 *
 * No handle, no strategy, no driver: the rules about *when* something advances are worth
 * being able to check without anything that could also be wrong.
 */
class AnimationRegistryTest {

    private lateinit var registry: AnimationRegistry
    private val log = mutableListOf<String>()

    /** Records every tick it receives, and can act on the registry while being ticked. */
    private inner class Fake(
        val name: String,
        var tickable: Boolean = true,
        val onTick: (Fake) -> Unit = {},
    ) : AnimationRegistry.Tickable {

        var ticks = 0
            private set

        override val isTickable: Boolean get() = tickable

        override fun tick(frameTime: FrameTime) {
            ticks++
            log += "$name@${frameTime.frameIndex}"
            onTick(this)
        }
    }

    private fun frame(index: Long) =
        FrameTime(frameTimeNanos = index * 16_000_000L, deltaNanos = 16_000_000L, frameIndex = index)

    @org.junit.Before
    fun setUp() {
        registry = AnimationRegistry()
        log.clear()
    }

    // --- ordering ------------------------------------------------------------

    @Test
    fun tickOrderIsInsertionOrder() {
        // Never a hash container: iteration order is observable behaviour here, so a hash
        // would make two runs of the same program tick in different orders.
        listOf("a", "b", "c", "d").forEach { registry.add(Fake(it)) }
        registry.tick(frame(0))
        assertEquals(listOf("a@0", "b@0", "c@0", "d@0"), log)
    }

    @Test
    fun insertionOrderSurvivesRemovalOfAMiddleEntry() {
        val a = Fake("a"); val b = Fake("b"); val c = Fake("c")
        registry.add(a); registry.add(b); registry.add(c)
        registry.remove(b)
        registry.tick(frame(0))
        assertEquals(listOf("a@0", "c@0"), log)
    }

    @Test
    fun addingTheSameTickableTwiceDoesNotTickItTwice() {
        val a = Fake("a")
        registry.add(a)
        registry.add(a)
        registry.tick(frame(0))
        assertEquals(1, a.ticks)
        assertEquals(1, registry.size)
    }

    // --- RULE-013: deferred mutation ----------------------------------------

    @Test
    fun somethingAddedDuringAFrameFirstTicksOnTheNextOne() {
        // If B could join the frame already in progress, whether it advanced on frame N or
        // N+1 would depend on where in the listener order it was added - and listener order
        // is not something a caller controls.
        val b = Fake("b")
        registry.add(Fake("a", onTick = { registry.add(b) }))

        registry.tick(frame(0))
        assertEquals(listOf("a@0"), log)
        assertEquals(0, b.ticks)

        registry.tick(frame(1))
        assertEquals(listOf("a@0", "a@1", "b@1"), log)
    }

    @Test
    fun somethingRemovedDuringAFrameIsNotTickedLaterInThatFrame() {
        // The dispose contract: after removal, never ticked again - including in the frame
        // the removal happened in, where a naive snapshot would still hold a reference.
        val c = Fake("c")
        registry.add(Fake("a", onTick = { registry.remove(c) }))
        registry.add(Fake("b"))
        registry.add(c)

        registry.tick(frame(0))
        assertEquals(listOf("a@0", "b@0"), log)
        assertEquals(0, c.ticks)
    }

    @Test
    fun aTickableThatStopsBeingTickableMidFrameIsSkipped() {
        // Cancelling animation C from animation A callback. C is still in the collection this
        // frame; it must not advance.
        val c = Fake("c")
        registry.add(Fake("a", onTick = { c.tickable = false }))
        registry.add(c)

        registry.tick(frame(0))
        assertEquals(listOf("a@0"), log)
    }

    @Test
    fun removingSomethingAlreadyTickedThisFrameStillTakesEffectNextFrame() {
        val a = Fake("a")
        val b = Fake("b", onTick = { registry.remove(a) })
        registry.add(a); registry.add(b)

        registry.tick(frame(0))
        assertEquals(listOf("a@0", "b@0"), log)   // a had already run when b removed it

        registry.tick(frame(1))
        assertEquals(listOf("a@0", "b@0", "b@1"), log)
    }

    @Test
    fun addsAndRemovesDuringAFrameCommitInAStableOrder() {
        // Two animations each starting one from their callback. B before C, on replay too.
        val b = Fake("b"); val c = Fake("c")
        registry.add(Fake("a1", onTick = { registry.add(b) }))
        registry.add(Fake("a2", onTick = { registry.add(c) }))

        registry.tick(frame(0))
        registry.tick(frame(1))

        assertEquals(listOf("a1@0", "a2@0", "a1@1", "a2@1", "b@1", "c@1"), log)
    }

    @Test
    fun anAddCancelledLaterInTheSameFrameNeverTicks() {
        val b = Fake("b")
        registry.add(Fake("a1", onTick = { registry.add(b) }))
        registry.add(Fake("a2", onTick = { registry.remove(b) }))

        registry.tick(frame(0))
        registry.tick(frame(1))

        assertEquals(0, b.ticks)
    }

    // --- frame position ------------------------------------------------------

    @Test
    fun theRegistryReportsWhichFrameItIsProcessing() {
        // A handle uses this to record which frame its execution was scheduled in, so it can
        // refuse to advance in that same frame.
        var seen = -99L
        registry.add(Fake("a", onTick = { seen = registry.tickingFrameIndex }))
        assertEquals(AnimationRegistry.NOT_TICKING, registry.tickingFrameIndex)
        registry.tick(frame(7))
        assertEquals(7L, seen)
        assertEquals(AnimationRegistry.NOT_TICKING, registry.tickingFrameIndex)
    }

    @Test
    fun theFrameIndexIsResetEvenIfATickableThrows() {
        registry.add(Fake("boom", onTick = { throw RuntimeException("from a listener") }))
        try {
            registry.tick(frame(3))
        } catch (expected: RuntimeException) {
            // expected
        }
        assertEquals(AnimationRegistry.NOT_TICKING, registry.tickingFrameIndex)
        assertFalse(registry.isTicking)
    }

    // --- wake ----------------------------------------------------------------

    @Test
    fun theRegistryAnnouncesWhenItStopsBeingEmpty() {
        // The driver stops posting frames when nothing is running, so it has to be told when
        // something starts. Otherwise an idle engine never wakes up again.
        var wakes = 0
        registry.onWake = { wakes++ }

        registry.add(Fake("a"))
        assertEquals(1, wakes)
        registry.add(Fake("b"))
        assertEquals("only the transition out of empty wakes the driver", 1, wakes)

        registry.clear()
        registry.add(Fake("c"))
        assertEquals(2, wakes)
    }

    // --- snapshot ------------------------------------------------------------

    @Test
    fun aSnapshotIsSafeToMutateTheRegistryFrom() {
        // cancelAll() walks this and cancels each entry, which removes it.
        registry.add(Fake("a")); registry.add(Fake("b"))
        val seen = mutableListOf<String>()
        registry.snapshot().forEach {
            seen += (it as Fake).name
            registry.remove(it)
        }
        assertEquals(listOf("a", "b"), seen)
        assertEquals(0, registry.size)
    }

    @Test
    fun clearEmptiesEverythingIncludingPendingWork() {
        registry.add(Fake("a", onTick = { registry.add(Fake("late")) }))
        registry.tick(frame(0))
        registry.clear()
        registry.tick(frame(1))
        assertEquals(listOf("a@0"), log)
        assertTrue(registry.size == 0)
    }

    @Test
    fun clearingDuringAFrameIsRejected() {
        // Without the guard this is an IndexOutOfBoundsException: tick() captures the list size
        // once, and clear() empties the list underneath it. Loud beats crashing, and both beat
        // emptying the registry behind the handles' backs.
        registry.add(Fake("a", onTick = { registry.clear() }))
        registry.add(Fake("b"))
        try {
            registry.tick(frame(0))
            fail("clear() during a frame must be rejected")
        } catch (expected: IllegalStateException) {
            // expected
        }
        // The guard must not leave the registry stuck mid-frame.
        assertEquals(AnimationRegistry.NOT_TICKING, registry.tickingFrameIndex)
        assertFalse(registry.isTicking)
    }

    @Test
    fun reEnteringTickIsRejected() {
        registry.add(Fake("a", onTick = { registry.tick(frame(1)) }))
        try {
            registry.tick(frame(0))
            fail("tick() must not be re-entrant")
        } catch (expected: IllegalStateException) {
            // expected
        }
    }

    // --- DefaultAnimationController ------------------------------------------

    private fun controller() = DefaultAnimationController().also { it.start() }

    @Test
    fun aControllerRefusesFramesBeforeItIsStarted() {
        val c = DefaultAnimationController()
        assertFalse(c.isRunning)
        try {
            c.tick(frame(0))
            fail("ticking a stopped engine must be loud, not silently ignored")
        } catch (expected: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun aFrameIndexThatDoesNotIncreaseIsRejected() {
        // RULE-006 monotonicity, applied to frames. A repeated or reordered frame would make
        // an animation advance twice for one instant, which no amount of care above could fix.
        val c = controller()
        c.tick(frame(0))
        c.tick(frame(1))
        listOf(1L, 0L).forEach { index ->
            try {
                c.tick(frame(index))
                fail("frame index $index after 1 must be rejected")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("frame"))
            }
        }
    }

    @Test
    fun aFrameIndexMayJumpForwardBecauseFramesGetDropped() {
        val c = controller()
        c.tick(frame(0))
        c.tick(frame(9))
    }

    @Test
    fun stoppingLeavesRunningAnimationsWhereTheyAre() {
        // A display turning off must not visibly reset the interface when it comes back.
        val c = controller()
        val h = c.animator.play(Animation("a", TimedSpec(Timeline.ofMillis(1000))))
        c.tick(frame(0))
        c.tick(frame(1))
        val whereItWas = h.progress

        c.stop()
        assertFalse(c.isRunning)
        assertEquals(AnimationState.RUNNING, h.state)
        assertEquals(whereItWas, h.progress, 0f)
    }

    @Test
    fun restartingTheEngineAcceptsFrameNumberingFromZeroAgain() {
        // A frame source restarted after a stop legitimately begins counting again.
        val c = controller()
        c.tick(frame(0))
        c.tick(frame(1))
        c.stop()
        c.start()
        c.tick(frame(0))
    }

    @Test
    fun theAnimatorAndTheEngineShareOneRegistry() {
        val c = controller()
        c.animator.play(Animation("a", TimedSpec(Timeline.ofMillis(1000))))
        assertEquals(1, c.animator.activeCount)
    }

    // --- RULE-011: coherence --------------------------------------------------

    @Test
    fun everyAnimationInAFrameAdvancesByTheSameElapsedTime() {
        // The point of one driver rather than one per animation. Three animations started on
        // three different frames must still agree about how much time a frame is worth. With
        // per-animation drivers each would build its own FrameTime and they would disagree.
        val c = controller()
        val a = c.animator.play(Animation("a", TimedSpec(Timeline.ofMillis(1000))))
        c.tick(frame(0))
        val b = c.animator.play(Animation("b", TimedSpec(Timeline.ofMillis(1000))))
        c.tick(frame(1))
        val d = c.animator.play(Animation("d", TimedSpec(Timeline.ofMillis(1000))))
        c.tick(frame(2))
        c.tick(frame(3))

        val beforeA = a.progress
        val beforeB = b.progress
        val beforeD = d.progress
        c.tick(frame(4))

        assertEquals(a.progress - beforeA, b.progress - beforeB, 1e-6f)
        assertEquals(a.progress - beforeA, d.progress - beforeD, 1e-6f)
    }

    // --- AnimationDriver ------------------------------------------------------

    private class Rig {
        val clock = TestClock()
        val scheduler = QueuedFrameScheduler(clock)
        val controller = DefaultAnimationController()
        val driver = AnimationDriver(scheduler, controller, controller.registry)
        val animator get() = controller.animator
    }

    @Test
    fun anIdleEngineAsksForNoFrames() {
        // The whole reason the driver has to be deliberate about this: with one callback for
        // everything, nothing stops posting on its own the way a per-animation driver did.
        val rig = Rig()
        rig.driver.start()
        assertEquals(0, rig.scheduler.pendingCount)
    }

    @Test
    fun startingAnAnimationWakesTheDriver() {
        val rig = Rig()
        rig.driver.start()
        rig.animator.play(Animation("a", TimedSpec(Timeline.ofMillis(100))))
        assertEquals(1, rig.scheduler.pendingCount)
    }

    @Test
    fun theDriverKeepsPostingWhileSomethingRuns() {
        val rig = Rig()
        rig.driver.start()
        rig.animator.play(Animation("a", TimedSpec(Timeline.ofMillis(1000))))
        repeat(5) {
            rig.scheduler.advanceOneFrame()
            assertEquals("still animating, so still asking for frames", 1, rig.scheduler.pendingCount)
        }
    }

    @Test
    fun theDriverStopsPostingWhenTheLastAnimationEnds() {
        // A running engine that never stops asking would wake a core every refresh forever.
        val rig = Rig()
        rig.driver.start()
        val h = rig.animator.play(Animation("a", TimedSpec(Timeline.ofMillis(32))))
        rig.scheduler.runToIdle(maxFrames = 50)
        assertEquals(AnimationState.COMPLETED, h.state)
        assertEquals(0, rig.scheduler.pendingCount)
    }

    @Test
    fun theDriverWakesAgainAfterGoingIdle() {
        val rig = Rig()
        rig.driver.start()
        val h = rig.animator.play(Animation("a", TimedSpec(Timeline.ofMillis(32))))
        rig.scheduler.runToIdle(maxFrames = 50)
        assertEquals(0, rig.scheduler.pendingCount)

        h.restart()
        assertEquals("a restarted animation must wake the driver", 1, rig.scheduler.pendingCount)
    }

    @Test
    fun theDriverBuildsAMonotonicFrameSequence() {
        // One FrameTime per frame, indices increasing, deltas measured from the timestamps.
        // The controller rejects anything else, so this passing is the proof.
        val rig = Rig()
        rig.driver.start()
        rig.animator.play(Animation("a", TimedSpec(Timeline.ofMillis(1000))))
        repeat(20) { rig.scheduler.advanceOneFrame() }
    }

    @Test
    fun stoppingTheDriverCancelsThePendingCallback() {
        val rig = Rig()
        rig.driver.start()
        rig.animator.play(Animation("a", TimedSpec(Timeline.ofMillis(1000))))
        assertEquals(1, rig.scheduler.pendingCount)
        rig.driver.stop()
        assertEquals(0, rig.scheduler.pendingCount)
        assertFalse(rig.controller.isRunning)
    }

    @Test
    fun oneFrameAdvancesEveryAnimationExactlyOnce() {
        // RULE-013 end to end, through the real driver.
        val rig = Rig()
        rig.driver.start()
        val counts = IntArray(3)
        listOf(0, 1, 2).forEach { i ->
            val h = rig.animator.play(Animation("a$i", TimedSpec(Timeline.ofMillis(1000))))
            h.addListener(object : aurora.sdk.animation.AnimationListener {
                override fun onUpdate(
                    handle: aurora.sdk.animation.AnimationHandle,
                    executionId: Long,
                    progress: Float,
                    value: Float,
                ) {
                    counts[i]++
                }
            })
        }
        rig.scheduler.advanceOneFrame()
        assertTrue(counts.all { it == 1 })
        rig.scheduler.advanceOneFrame()
        assertTrue(counts.all { it == 2 })
    }
}
