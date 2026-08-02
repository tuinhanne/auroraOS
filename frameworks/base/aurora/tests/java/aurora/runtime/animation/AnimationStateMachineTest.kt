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

import aurora.sdk.animation.AnimationState
import aurora.sdk.animation.AnimationState.CANCELLED
import aurora.sdk.animation.AnimationState.COMPLETED
import aurora.sdk.animation.AnimationState.DISPOSED
import aurora.sdk.animation.AnimationState.IDLE
import aurora.sdk.animation.AnimationState.PAUSED
import aurora.sdk.animation.AnimationState.RUNNING
import aurora.sdk.animation.AnimationState.SCHEDULED
import aurora.runtime.animation.AnimationEvent.CANCEL
import aurora.runtime.animation.AnimationEvent.DISPOSE
import aurora.runtime.animation.AnimationEvent.FINISH
import aurora.runtime.animation.AnimationEvent.PAUSE
import aurora.runtime.animation.AnimationEvent.PLAY
import aurora.runtime.animation.AnimationEvent.RESTART
import aurora.runtime.animation.AnimationEvent.RESUME
import aurora.runtime.animation.AnimationEvent.TICK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Every cell of the transition table, legal and illegal alike.
 *
 * Seven states times eight events is fifty-six outcomes, and all fifty-six are named below
 * rather than sampled. A state machine tested by example is tested only where someone
 * happened to look, and the cells nobody looks at are exactly where an interface ends up
 * wedged in a state it cannot leave.
 */
class AnimationStateMachineTest {

    /**
     * The whole table. `null` means the event is illegal in that state.
     *
     * Read it against the spec: docs/specs/2026-08-02-sprint-06a-animation-architecture-design.md
     */
    private val table: Map<Pair<AnimationState, AnimationEvent>, AnimationState?> = mapOf(
        //                       PLAY        TICK      PAUSE     RESUME    CANCEL      RESTART     FINISH      DISPOSE
        (IDLE to PLAY) to SCHEDULED,
        (IDLE to TICK) to null,
        (IDLE to PAUSE) to null,
        (IDLE to RESUME) to null,
        (IDLE to CANCEL) to CANCELLED,
        (IDLE to RESTART) to SCHEDULED,
        (IDLE to FINISH) to null,
        (IDLE to DISPOSE) to DISPOSED,

        (SCHEDULED to PLAY) to null,
        (SCHEDULED to TICK) to RUNNING,
        (SCHEDULED to PAUSE) to PAUSED,
        (SCHEDULED to RESUME) to null,
        (SCHEDULED to CANCEL) to CANCELLED,
        (SCHEDULED to RESTART) to SCHEDULED,
        (SCHEDULED to FINISH) to null,
        (SCHEDULED to DISPOSE) to DISPOSED,

        (RUNNING to PLAY) to null,
        (RUNNING to TICK) to RUNNING,
        (RUNNING to PAUSE) to PAUSED,
        (RUNNING to RESUME) to null,
        (RUNNING to CANCEL) to CANCELLED,
        (RUNNING to RESTART) to SCHEDULED,
        (RUNNING to FINISH) to COMPLETED,
        (RUNNING to DISPOSE) to DISPOSED,

        (PAUSED to PLAY) to null,
        (PAUSED to TICK) to null,
        (PAUSED to PAUSE) to PAUSED,
        (PAUSED to RESUME) to RUNNING,      // with the default pausedFrom
        (PAUSED to CANCEL) to CANCELLED,
        (PAUSED to RESTART) to SCHEDULED,
        (PAUSED to FINISH) to null,
        (PAUSED to DISPOSE) to DISPOSED,

        (COMPLETED to PLAY) to null,
        (COMPLETED to TICK) to null,
        (COMPLETED to PAUSE) to null,
        (COMPLETED to RESUME) to null,
        (COMPLETED to CANCEL) to COMPLETED, // idempotent no-op
        (COMPLETED to RESTART) to SCHEDULED,
        (COMPLETED to FINISH) to null,
        (COMPLETED to DISPOSE) to DISPOSED,

        (CANCELLED to PLAY) to null,
        (CANCELLED to TICK) to null,
        (CANCELLED to PAUSE) to null,
        (CANCELLED to RESUME) to null,
        (CANCELLED to CANCEL) to CANCELLED, // idempotent no-op
        (CANCELLED to RESTART) to SCHEDULED,
        (CANCELLED to FINISH) to null,
        (CANCELLED to DISPOSE) to DISPOSED,

        (DISPOSED to PLAY) to null,
        (DISPOSED to TICK) to null,
        (DISPOSED to PAUSE) to null,
        (DISPOSED to RESUME) to null,
        (DISPOSED to CANCEL) to null,
        (DISPOSED to RESTART) to null,
        (DISPOSED to FINISH) to null,
        (DISPOSED to DISPOSE) to DISPOSED,  // idempotent
    )

    @Test
    fun theTableCoversEveryCell() {
        // Guards the test itself. A missing entry would silently reduce coverage.
        assertEquals(
            AnimationState.values().size * AnimationEvent.values().size,
            table.size
        )
    }

    @Test
    fun everyLegalCellProducesTheExpectedState() {
        table.forEach { (key, expected) ->
            val (from, event) = key
            if (expected == null) return@forEach
            assertEquals("$from + $event", expected, AnimationStateMachine.next(from, event))
        }
    }

    @Test
    fun everyIllegalCellThrows() {
        table.forEach { (key, expected) ->
            val (from, event) = key
            if (expected != null) return@forEach
            try {
                val got = AnimationStateMachine.next(from, event)
                fail("$from + $event should be illegal but produced $got")
            } catch (expectedFailure: IllegalStateException) {
                assertNotNull(expectedFailure.message)
                assertTrue(
                    "the message must name the state and the event, got: ${expectedFailure.message}",
                    expectedFailure.message!!.contains(from.name) &&
                        expectedFailure.message!!.contains(event.name)
                )
            }
        }
    }

    @Test
    fun canTransitionAgreesWithNext() {
        table.forEach { (key, expected) ->
            val (from, event) = key
            assertEquals(
                "canTransition disagrees with next for $from + $event",
                expected != null,
                AnimationStateMachine.canTransition(from, event)
            )
        }
    }

    // --- The cases the sprint contract calls out by name ----------------------

    @Test
    fun cancelThenResumeFails() {
        // Named in the Sprint 06A contract. Cancelling ends the execution; resuming would
        // have to continue one that no longer exists.
        val cancelled = AnimationStateMachine.next(RUNNING, CANCEL)
        assertEquals(CANCELLED, cancelled)
        try {
            AnimationStateMachine.next(cancelled, RESUME)
            fail("resuming a cancelled animation must fail")
        } catch (expected: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun cancelThenRestartSucceeds() {
        // The most common gesture case: a swipe interrupted, then started again. RULE-012.
        val cancelled = AnimationStateMachine.next(RUNNING, CANCEL)
        assertEquals(SCHEDULED, AnimationStateMachine.next(cancelled, RESTART))
    }

    @Test
    fun resumeReturnsToWhicheverStateThePauseCameFrom() {
        // Pausing before the first frame is legal, so resuming has to be able to go back to
        // SCHEDULED rather than assuming RUNNING. Without this, an animation paused before it
        // started would be treated as already running and lose its first frame.
        assertEquals(RUNNING, AnimationStateMachine.next(PAUSED, RESUME, pausedFrom = RUNNING))
        assertEquals(SCHEDULED, AnimationStateMachine.next(PAUSED, RESUME, pausedFrom = SCHEDULED))
    }

    @Test
    fun resumeRejectsAPausedFromThatNoPauseCouldHaveProduced() {
        // The 56-cell table only ever supplies RUNNING or SCHEDULED, so this corner of the input
        // domain is otherwise unexercised. Without the guard, RESUME would hand back DISPOSED as
        // the next state without DISPOSE ever being dispatched.
        listOf(IDLE, PAUSED, COMPLETED, CANCELLED, DISPOSED).forEach { bogus ->
            assertFalse(
                "pausedFrom=$bogus must not be a legal resume target",
                AnimationStateMachine.canTransition(PAUSED, RESUME, pausedFrom = bogus)
            )
            try {
                AnimationStateMachine.next(PAUSED, RESUME, pausedFrom = bogus)
                fail("resume with pausedFrom=$bogus must fail")
            } catch (expected: IllegalStateException) {
                assertTrue(
                    "the message must name the offending pausedFrom, got: ${expected.message}",
                    expected.message!!.contains(bogus.name)
                )
            }
        }
    }

    @Test
    fun disposeIsLegalFromEveryStateAndAlwaysTerminal() {
        AnimationState.values().forEach {
            assertEquals("dispose from $it", DISPOSED, AnimationStateMachine.next(it, DISPOSE))
        }
    }

    @Test
    fun nothingButDisposeEscapesDisposed() {
        AnimationEvent.values().filter { it != DISPOSE }.forEach { event ->
            assertFalse(
                "$event must be illegal on a disposed handle",
                AnimationStateMachine.canTransition(DISPOSED, event)
            )
        }
    }

    @Test
    fun theMachineHoldsNoState() {
        // A pure function: calling it a thousand times in any order changes nothing. This is
        // what makes the rest of RULE-009 possible, so it is asserted rather than assumed.
        repeat(1000) {
            assertEquals(SCHEDULED, AnimationStateMachine.next(IDLE, PLAY))
            assertEquals(CANCELLED, AnimationStateMachine.next(RUNNING, CANCEL))
            assertEquals(COMPLETED, AnimationStateMachine.next(RUNNING, FINISH))
        }
    }
}
