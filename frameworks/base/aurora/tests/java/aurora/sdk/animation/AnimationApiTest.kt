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

import aurora.sdk.design.MotionTokens
import aurora.sdk.event.Disposable
import aurora.sdk.time.AuroraClock
import aurora.sdk.time.FrameTime
import aurora.sdk.time.Timeline
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The pure SDK animation types, exercised with no engine and no frames.
 *
 * Everything here is data or a pure function, so every assertion is exact. Nothing in this
 * file constructs a controller, a registry or a driver — if a test here needs one, it belongs
 * in one of the runtime test classes instead.
 */
class AnimationApiTest {

    // --- AnimationState ------------------------------------------------------

    @Test
    fun everyStateIsClassifiedDeliberately() {
        // The growth canary. A state added in a later sprint fails HERE, on the row that needs
        // a decision, rather than on a count that can be satisfied by editing a number.
        //
        // "unclassified" is a legitimate answer - IDLE and PAUSED are both - but it has to be
        // written down per state rather than defaulted into by omission.
        val expected = mapOf(
            AnimationState.IDLE to "unclassified",
            AnimationState.SCHEDULED to "active",
            AnimationState.RUNNING to "active",
            AnimationState.PAUSED to "unclassified",
            AnimationState.COMPLETED to "resting",
            AnimationState.CANCELLED to "resting",
            AnimationState.DISPOSED to "terminal",
        )
        assertEquals(
            "a state added later must be classified in this table, not omitted from it",
            AnimationState.values().toSet(),
            expected.keys,
        )
        AnimationState.values().forEach { state ->
            val actual = when {
                state.isActive -> "active"
                state.isResting -> "resting"
                state.isTerminal -> "terminal"
                else -> "unclassified"
            }
            assertEquals("classification of $state", expected[state], actual)
        }
    }

    @Test
    fun onlyScheduledAndRunningAreActive() {
        val active = AnimationState.values().filter { it.isActive }.toSet()
        assertEquals(setOf(AnimationState.SCHEDULED, AnimationState.RUNNING), active)
    }

    @Test
    fun onlyCompletedAndCancelledAreResting() {
        val resting = AnimationState.values().filter { it.isResting }.toSet()
        assertEquals(setOf(AnimationState.COMPLETED, AnimationState.CANCELLED), resting)
    }

    @Test
    fun onlyDisposedIsTerminal() {
        val terminal = AnimationState.values().filter { it.isTerminal }.toSet()
        assertEquals(setOf(AnimationState.DISPOSED), terminal)
    }

    @Test
    fun theThreePredicatesNeverOverlap() {
        // A state is at most one of active / resting / terminal. If two were ever true at
        // once, callers branching on them would take two paths for one state.
        AnimationState.values().forEach { state ->
            val count = listOf(state.isActive, state.isResting, state.isTerminal).count { it }
            assertTrue("$state matches more than one predicate", count <= 1)
        }
    }

    @Test
    fun idleIsNoneOfThem() {
        assertFalse(AnimationState.IDLE.isActive)
        assertFalse(AnimationState.IDLE.isResting)
        assertFalse(AnimationState.IDLE.isTerminal)
    }

    // --- Interpolator --------------------------------------------------------

    @Test
    fun linearIsTheIdentity() {
        // Not "approximately linear" — the identity. LINEAR computes nothing, which is why
        // it is allowed to live in the SDK alongside the tokens (RULE-004, RULE-010).
        listOf(0f, 0.25f, 0.5f, 0.75f, 1f, -0.5f, 1.5f).forEach {
            assertEquals(it, Interpolator.LINEAR.transform(it), 0f)
        }
    }

    @Test
    fun anInterpolatorIsWritableAsALambda() {
        val easeOut = Interpolator { p -> 1f - (1f - p) * (1f - p) }
        assertEquals(0f, easeOut.transform(0f), 1e-6f)
        assertEquals(0.75f, easeOut.transform(0.5f), 1e-6f)
        assertEquals(1f, easeOut.transform(1f), 1e-6f)
    }

    // --- AnimationSpec -------------------------------------------------------

    private val ms = AuroraClock.NANOS_PER_MILLI

    @Test
    fun timedSpecDefaultsToLinear() {
        val spec = TimedSpec(Timeline.ofMillis(300))
        assertEquals(Interpolator.LINEAR, spec.interpolator)
    }

    @Test
    fun elapsedForProgressIsTheInverseOfProgressAt() {
        // THE invariant. Both directions of the elapsed-progress mapping live on TimedSpec,
        // and this is what keeps them honest: whatever elapsed time a progress maps to must
        // map back to the same progress.
        //
        // An earlier draft defined progress as spanning the whole repeated sequence, which
        // looked reasonable and was wrong: Timeline.progressAt counts per iteration and resets
        // to 0 each time round. The two were not inverses, and seek(0.25) on a three-times
        // timeline landed on progress 0.75. This test is why that is not still true.
        val specs = listOf(
            TimedSpec(Timeline.ofMillis(200)),
            TimedSpec(Timeline(durationNanos = 300 * ms, delayNanos = 100 * ms)),
            TimedSpec(Timeline(durationNanos = 300 * ms, delayNanos = 100 * ms, repeatCount = 2)),
            TimedSpec(Timeline(durationNanos = 200 * ms, repeatCount = 3, reverseOnRepeat = true)),
            TimedSpec(Timeline(durationNanos = 200 * ms, repeatCount = Timeline.REPEAT_INFINITE)),
        )
        specs.forEach { spec ->
            (0..100).map { it / 100f }.forEach { p ->
                val elapsed = spec.elapsedForProgress(p)
                assertEquals(
                    "round trip failed for $p on ${spec.timeline}",
                    p,
                    spec.timeline.progressAt(elapsed),
                    1e-5f
                )
            }
        }
    }

    @Test
    fun progressOneMeansTheEndOfTheFirstIterationNotTheEndOfTheSequence() {
        // Positions are per iteration, matching progressAt. Seeking a repeating animation
        // therefore lands at the end of its FIRST iteration and the remaining repeats play out
        // from there.
        val once = TimedSpec(Timeline(durationNanos = 300 * ms, delayNanos = 100 * ms))
        assertEquals(100 * ms, once.elapsedForProgress(0f))
        assertEquals(250 * ms, once.elapsedForProgress(0.5f))
        assertEquals(400 * ms, once.elapsedForProgress(1f))

        val thrice = TimedSpec(
            Timeline(durationNanos = 300 * ms, delayNanos = 100 * ms, repeatCount = 2)
        )
        assertEquals("same position, regardless of how many repeats follow",
            250 * ms, thrice.elapsedForProgress(0.5f))

        // The boundary case. Landing exactly on 400ms would be read by progressAt as the START
        // of iteration 1, so seeking to the far end would snap the animation back to its
        // beginning. One nanosecond inside is what makes seek(1f) mean what it says.
        assertEquals(399 * ms + 999_999L, thrice.elapsedForProgress(1f))
        assertEquals(1f, thrice.timeline.progressAt(thrice.elapsedForProgress(1f)), 1e-5f)
    }

    @Test
    fun elapsedForProgressClampsOutOfRangeInput() {
        val spec = TimedSpec(Timeline.ofMillis(200))
        assertEquals(0L, spec.elapsedForProgress(-1f))
        assertEquals(200 * ms, spec.elapsedForProgress(2f))
    }

    @Test
    fun everyPhysicsSpecCarriesVelocityAndRestThresholds() {
        // The three fields are what a solver needs and a Timeline cannot express. Declared
        // now so that 06B adds solvers without changing this file.
        val specs: List<PhysicsSpec> = listOf(
            SpringSpec(),
            DecaySpec(),
            SnapSpec(targets = listOf(0f, 1f)),
        )
        // javaClass.simpleName, not ::class.simpleName: KClass pulls in Kotlin reflection,
        // which is not on the core_current classpath.
        specs.forEach {
            assertTrue("${it.javaClass.simpleName} restVelocity", it.restVelocity > 0f)
            assertTrue("${it.javaClass.simpleName} restDelta", it.restDelta > 0f)
        }
    }

    @Test
    fun springSpecWrapsADesignTokenRatherThanReplacingIt() {
        // The token says which spring the design chose; the spec says how to run it. Two
        // decisions made by two different people, so two types.
        val spec = SpringSpec(spring = MotionTokens.SPRING_BOUNCY, initialVelocity = 2f)
        assertEquals(MotionTokens.SPRING_BOUNCY, spec.spring)
        assertEquals(2f, spec.initialVelocity, 0f)
    }

    @Test
    fun aSnapSpecWithoutTargetsIsRejected() {
        try {
            SnapSpec(targets = emptyList())
            fail("a snap with nowhere to snap to must not be constructible")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun aDecayWithoutFrictionIsRejected() {
        try {
            DecaySpec(friction = 0f)
            fail("frictionless decay never settles")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun aPhysicsSpecWithoutRestThresholdsIsRejected() {
        // A solver with a zero threshold never reports settling, so the animation runs forever.
        // As fatal as frictionless decay, and rejected as loudly.
        listOf<Pair<String, () -> PhysicsSpec>>(
            "spring restVelocity" to { SpringSpec(restVelocity = 0f) },
            "spring restDelta" to { SpringSpec(restDelta = 0f) },
            "decay restVelocity" to { DecaySpec(restVelocity = -1f) },
            "snap restDelta" to { SnapSpec(targets = listOf(0f), restDelta = 0f) },
        ).forEach { (name, construct) ->
            try {
                construct()
                fail("$name must be rejected")
            } catch (expected: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun physicsThresholdsAreNormalisedSoTheDefaultsAreSaneAtAnyScale() {
        // The defaults are only meaningful in normalised progress. If they were value units,
        // restDelta = 0.001f would mean a thousandth of a pixel on a full-screen slide, which no
        // solver would ever reach, and the animation would never report settling.
        //
        // This test cannot check units - nothing can - so it checks the consequence: the
        // thresholds are small fractions of the unit interval, which is the only reading under
        // which they work for both an alpha fade over 0..1 and a 1000px slide.
        val spec = SpringSpec()
        assertTrue("restDelta must be a fraction of the unit interval", spec.restDelta < 0.01f)
        assertTrue("restVelocity must be a fraction of the unit interval", spec.restVelocity < 0.1f)
    }

    @Test
    fun physicsSpecsAreAnimationSpecs() {
        val spec: AnimationSpec = SpringSpec()
        assertTrue(spec is PhysicsSpec)
    }

    // --- Animation -----------------------------------------------------------

    @Test
    fun valueAtInterpolatesBetweenFromAndTo() {
        val a = Animation("fade", TimedSpec(Timeline.ofMillis(200)), from = 0f, to = 1f)
        assertEquals(0f, a.valueAt(0f), 0f)
        assertEquals(0.5f, a.valueAt(0.5f), 0f)
        assertEquals(1f, a.valueAt(1f), 0f)
    }

    @Test
    fun valueAtHandlesADescendingRange() {
        // Dismissing a sheet animates 1 -> 0. Nothing may assume from < to.
        val a = Animation("dismiss", TimedSpec(Timeline.ofMillis(200)), from = 1f, to = 0f)
        assertEquals(1f, a.valueAt(0f), 0f)
        assertEquals(0.25f, a.valueAt(0.75f), 1e-6f)
        assertEquals(0f, a.valueAt(1f), 0f)
    }

    @Test
    fun valueAtHandlesARangeThatIsNotZeroToOne() {
        val a = Animation("slide", TimedSpec(Timeline.ofMillis(200)), from = 100f, to = 340f)
        assertEquals(220f, a.valueAt(0.5f), 1e-4f)
    }

    @Test
    fun valueAtLandsExactlyOnTheEndpoints() {
        // Not a tolerance check - a bit-exactness check, with delta 0f. An animation that
        // finishes must come to rest on `to` itself, because that is the value callers compare
        // against.
        //
        // The bounds are un-representable in binary, so `from + (to - from) * 1f` lands an ULP
        // away on some of them and this test fails under that formula. Not on all four: -5..-1.9
        // and -0.7..0.35 diverge, while 0.1..0.3 and 1.1..2.7 happen to round back. One
        // diverging pair is enough to make the test load-bearing, and the four are kept because
        // the exactness claim is about every pair, not about the ones that expose the old bug.
        //
        // Every other test in this file uses 0f or integer bounds, for which both formulas
        // agree - which is exactly why the original suite could not tell them apart.
        listOf(
            -5f to -1.9f,
            0.1f to 0.3f,
            1.1f to 2.7f,
            -0.7f to 0.35f,
        ).forEach { (from, to) ->
            val a = Animation("edge", TimedSpec(Timeline.ofMillis(200)), from = from, to = to)
            assertEquals("start of $from..$to", from, a.valueAt(0f), 0f)
            assertEquals("end of $from..$to", to, a.valueAt(1f), 0f)
        }
    }

    @Test
    fun valueAtIsPureSoTheSameProgressAlwaysGivesTheSameValue() {
        // RULE-009 at its smallest scale. If this were not exact, no amount of care in the
        // engine above it could make a replay reproduce.
        val a = Animation("fade", TimedSpec(Timeline.ofMillis(200)), from = 3f, to = 17f)
        repeat(100) { assertEquals(a.valueAt(0.37f), a.valueAt(0.37f), 0f) }
    }

    @Test
    fun valueAtDoesNotClampSoAnOvershootSurvives() {
        // A bouncy spring in 06B produces eased progress above 1. Clamping here would flatten
        // the overshoot and the bounce would silently disappear.
        val a = Animation("bounce", TimedSpec(Timeline.ofMillis(200)), from = 0f, to = 100f)
        assertEquals(110f, a.valueAt(1.1f), 1e-4f)
    }

    @Test
    fun anAnimationWithoutANameIsRejected() {
        try {
            Animation("  ", TimedSpec(Timeline.ofMillis(200)))
            fail("an unnamed animation is undiagnosable in a log")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun nonFiniteBoundsAreRejected() {
        // A NaN bound would produce NaN at every frame, forever, with nothing to point at.
        listOf(
            "NaN from" to { Animation("x", TimedSpec(Timeline.ofMillis(200)), from = Float.NaN) },
            "NaN to" to { Animation("x", TimedSpec(Timeline.ofMillis(200)), to = Float.NaN) },
            "infinite from" to
                { Animation("x", TimedSpec(Timeline.ofMillis(200)), from = Float.POSITIVE_INFINITY) },
            "infinite to" to
                { Animation("x", TimedSpec(Timeline.ofMillis(200)), to = Float.NEGATIVE_INFINITY) },
        ).forEach { (name, construct) ->
            try {
                construct()
                fail("$name must be rejected")
            } catch (expected: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun fromAndToDefaultToTheUnitRange() {
        val a = Animation("fade", TimedSpec(Timeline.ofMillis(200)))
        assertEquals(0f, a.from, 0f)
        assertEquals(1f, a.to, 0f)
    }

    // --- AnimationStrategy ---------------------------------------------------

    /** A strategy with no physics, proving the interface is implementable as declared. */
    private class HalfWayStrategy : AnimationStrategy {
        override var progress: Float = 0f
            private set
        override var easedProgress: Float = 0f
            private set
        override var isFinished: Boolean = false
            private set

        override fun advance(elapsedNanos: Long, deltaNanos: Long) {
            progress = 0.5f
            easedProgress = 0.5f
            isFinished = elapsedNanos > 0L
        }

        override fun reset() {
            progress = 0f
            easedProgress = 0f
            isFinished = false
        }

        override fun seekTo(progress: Float) = Unit
    }

    @Test
    fun aStrategyReportsBothRawAndShapedProgress() {
        // Two values, not one. The engine reads easedProgress for the value and progress for
        // diagnostics; conflating them is the contradiction this design was corrected for.
        val s = HalfWayStrategy()
        s.advance(elapsedNanos = 10L, deltaNanos = 10L)
        assertEquals(0.5f, s.progress, 0f)
        assertEquals(0.5f, s.easedProgress, 0f)
        assertTrue(s.isFinished)
    }

    @Test
    fun resetReturnsAStrategyToItsStartingState() {
        val s = HalfWayStrategy()
        s.advance(10L, 10L)
        s.reset()
        assertEquals(0f, s.progress, 0f)
        assertFalse(s.isFinished)
    }

    @Test
    fun aStrategyMayRejectSeeking() {
        // Optional operation, by design and not by omission: a spring position comes from
        // integrating its previous state, so there is no elapsed time to jump to.
        val physics = object : AnimationStrategy {
            override val progress = 0f
            override val easedProgress = 0f
            override val isFinished = false
            override fun advance(elapsedNanos: Long, deltaNanos: Long) = Unit
            override fun reset() = Unit
            override fun seekTo(progress: Float): Unit =
                throw UnsupportedOperationException("a spring cannot be seeked")
        }
        try {
            physics.seekTo(0.5f)
            fail("a physics strategy must be allowed to reject seeking")
        } catch (expected: UnsupportedOperationException) {
            // expected
        }
    }

    // --- RULE-011 and RULE-014: FrameTime is an immutable value ---------------

    @Test
    fun everyFrameTimeFieldIsFinal() {
        // RULE-014. Every animation in a frame reads the same FrameTime instance, so one callback
        // mutating it would corrupt the whole frame. FrameTime is a data class of vals today;
        // this fails the day someone adds a var.
        //
        // What it does NOT catch: a `val` holding a mutable object. `val tags: MutableList<String>`
        // is final and has no setter, yet a callback could still add to it. Reflection cannot see
        // that, so the rule that every FrameTime field must itself be of an immutable type is
        // review's job, not this test's. Said here rather than left implied, because a test that
        // is believed to prove more than it does is worse than one nobody trusts.
        FrameTime::class.java.declaredFields
            .filterNot { it.isSynthetic }
            .forEach {
                assertTrue(
                    "FrameTime.${it.name} is not final; RULE-014 requires an immutable frame",
                    Modifier.isFinal(it.modifiers)
                )
            }
    }

    @Test
    fun frameTimeExposesNoSetters() {
        val setters = FrameTime::class.java.methods.filter { it.name.startsWith("set") }
        assertTrue("FrameTime must expose no setters, found $setters", setters.isEmpty())
    }

    @Test
    fun nextKeepsDeltaAndIndexConsistentWithTheTimestamps() {
        // RULE-011 leans on this: one FrameTime is built per frame and handed out, so the
        // delta it reports must be the real gap rather than a nominal interval.
        val first = FrameTime.first(1_000L)
        val second = first.next(1_016L)
        assertEquals(1_016L, second.frameTimeNanos)
        assertEquals(16L, second.deltaNanos)
        assertEquals(1L, second.frameIndex)
    }

    // --- The interfaces ------------------------------------------------------

    /** A handle with no engine behind it, used to prove the defaults behave. */
    private class StubHandle(
        override val animation: Animation,
        override var state: AnimationState,
    ) : AnimationHandle {
        override val executionId = 1L
        override val progress = 0f
        override val value = 0f
        override fun play() = Unit
        override fun pause() = Unit
        override fun resume() = Unit
        override fun cancel() = Unit
        override fun restart() = Unit
        override fun seek(progress: Float) = Unit
        override fun addListener(listener: AnimationListener): Disposable =
            object : Disposable {
                override val isDisposed = false
                override fun dispose() = Unit
            }
        override fun dispose() { state = AnimationState.DISPOSED }
    }

    private fun stub(state: AnimationState) =
        StubHandle(Animation("stub", TimedSpec(Timeline.ofMillis(100))), state)

    @Test
    fun isRunningIsTrueInExactlyOneState() {
        AnimationState.values().forEach { s ->
            assertEquals("isRunning for $s", s == AnimationState.RUNNING, stub(s).isRunning)
        }
    }

    @Test
    fun isDisposedTracksTheStateRatherThanASeparateFlag() {
        // A handle is a Disposable, and two sources of truth for "is it dead" would
        // eventually disagree. isDisposed is derived, not stored.
        AnimationState.values().forEach { s ->
            assertEquals("isDisposed for $s", s == AnimationState.DISPOSED, stub(s).isDisposed)
        }
    }

    @Test
    fun aListenerNeedOverrideNothing() {
        // Both callbacks are defaulted, so 06B and 06C can add more without breaking any
        // implementor. This compiles only while that stays true.
        val silent = object : AnimationListener {}
        val h = stub(AnimationState.RUNNING)
        silent.onStateChanged(h, 1L, AnimationState.SCHEDULED, AnimationState.RUNNING)
        silent.onUpdate(h, 1L, 0.5f, 0.5f)
    }

    @Test
    fun aListenerMayOverrideJustOneCallback() {
        var updates = 0
        val listener = object : AnimationListener {
            override fun onUpdate(
                handle: AnimationHandle,
                executionId: Long,
                progress: Float,
                value: Float,
            ) {
                updates++
            }
        }
        val h = stub(AnimationState.RUNNING)
        listener.onStateChanged(h, 1L, AnimationState.SCHEDULED, AnimationState.RUNNING)
        listener.onUpdate(h, 1L, 0.5f, 0.5f)
        assertEquals(1, updates)
    }
}
