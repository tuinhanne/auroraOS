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

package aurora.runtime.volume

import aurora.sdk.event.AuroraDispatcher
import aurora.sdk.event.QueuedDispatcher
import aurora.sdk.service.VolumeStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The first Aurora service implementation, exercised end to end with no Android.
 *
 * Sprint 04.1 Task 3. Every member of `VolumeService` is covered, and the two questions the spec
 * left for the implementation to answer are answered here rather than by review: whether the
 * add/remove listener pair works as declared, and where the unit conversion ends up living.
 */
class DefaultVolumeServiceTest {

    private fun service(
        source: FakeVolumeSource = FakeVolumeSource(),
        dispatcher: AuroraDispatcher = AuroraDispatcher.IMMEDIATE,
    ) = DefaultVolumeService(source, dispatcher)

    // --- reading -------------------------------------------------------------

    @Test
    fun levelIsNormalisedAcrossTheStreamsOwnRange() {
        val source = FakeVolumeSource()
        source.configure(VolumeStream.MEDIA, level = 5, minLevel = 0, maxLevel = 10)
        assertEquals(0.5f, service(source).levelOf(VolumeStream.MEDIA), 0f)
    }

    @Test
    fun aRangeThatDoesNotStartAtZeroStillNormalisesToZeroAtItsBottom() {
        // Android's call stream has a non-zero minimum. Assuming min == 0 would report a muted-looking
        // 0.6 for a call at its quietest, which is the kind of wrong that looks plausible on a slider.
        val source = FakeVolumeSource()
        source.configure(VolumeStream.CALL, level = 3, minLevel = 3, maxLevel = 8)
        assertEquals(0f, service(source).levelOf(VolumeStream.CALL), 0f)
    }

    @Test
    fun stepCountIncludesBothEnds() {
        val source = FakeVolumeSource()
        source.configure(VolumeStream.RING, level = 0, minLevel = 0, maxLevel = 7)
        // Eight positions, because silence is a position a UI has to be able to draw and snap to.
        assertEquals(8, service(source).stepCountOf(VolumeStream.RING))
    }

    @Test
    fun mutingPreservesTheLevelSoUnmutingRestoresIt() {
        val source = FakeVolumeSource()
        source.configure(VolumeStream.MEDIA, level = 6, maxLevel = 10)
        val s = service(source)

        s.setMuted(VolumeStream.MEDIA, true)
        assertTrue(s.isMuted(VolumeStream.MEDIA))
        assertEquals(0.6f, s.levelOf(VolumeStream.MEDIA), 1e-6f)

        s.setMuted(VolumeStream.MEDIA, false)
        assertFalse(s.isMuted(VolumeStream.MEDIA))
        assertEquals(0.6f, s.levelOf(VolumeStream.MEDIA), 1e-6f)
    }

    @Test
    fun theActiveStreamIsReadThroughRatherThanRemembered() {
        val source = FakeVolumeSource(activeStream = VolumeStream.RING)
        val s = service(source)
        assertEquals(VolumeStream.RING, s.activeStream)

        source.activeStream = VolumeStream.MEDIA
        assertEquals(VolumeStream.MEDIA, s.activeStream)
    }

    @Test
    fun streamsAreIndependent() {
        val source = FakeVolumeSource()
        source.configure(VolumeStream.MEDIA, level = 2, maxLevel = 10)
        source.configure(VolumeStream.ALARM, level = 9, maxLevel = 10)
        val s = service(source)

        s.setLevel(VolumeStream.MEDIA, 1f)
        assertEquals(1f, s.levelOf(VolumeStream.MEDIA), 0f)
        assertEquals(0.9f, s.levelOf(VolumeStream.ALARM), 1e-6f)
    }

    // --- writing -------------------------------------------------------------

    @Test
    fun setLevelDenormalisesOntoARealStep() {
        val source = FakeVolumeSource()
        source.configure(VolumeStream.MEDIA, level = 0, minLevel = 0, maxLevel = 10)
        service(source).setLevel(VolumeStream.MEDIA, 0.3f)
        assertEquals(3, source.rawLevelOf(VolumeStream.MEDIA))
    }

    @Test
    fun setLevelRoundsSoTheTopStepIsReachable() {
        // Truncation would put 0.99 one step below maximum, and a finger dragging a slider to the
        // end would never reach full volume - a bug that reads as a hardware limit.
        val source = FakeVolumeSource()
        source.configure(VolumeStream.MEDIA, level = 0, minLevel = 0, maxLevel = 10)
        service(source).setLevel(VolumeStream.MEDIA, 0.99f)
        assertEquals(10, source.rawLevelOf(VolumeStream.MEDIA))
    }

    @Test
    fun setLevelClampsBothEnds() {
        val source = FakeVolumeSource()
        source.configure(VolumeStream.MEDIA, level = 5, minLevel = 0, maxLevel = 10)
        val s = service(source)

        s.setLevel(VolumeStream.MEDIA, 4f)
        assertEquals(10, source.rawLevelOf(VolumeStream.MEDIA))

        s.setLevel(VolumeStream.MEDIA, -2f)
        assertEquals(0, source.rawLevelOf(VolumeStream.MEDIA))
    }

    @Test
    fun setLevelRespectsANonZeroMinimum() {
        val source = FakeVolumeSource()
        source.configure(VolumeStream.CALL, level = 3, minLevel = 3, maxLevel = 8)
        service(source).setLevel(VolumeStream.CALL, 0f)
        assertEquals(3, source.rawLevelOf(VolumeStream.CALL))
    }

    // --- observing -----------------------------------------------------------

    @Test
    fun aListenerHearsAChangeThatCameFromTheHardwareKeys() {
        val source = FakeVolumeSource()
        source.configure(VolumeStream.MEDIA, level = 0, maxLevel = 10)
        val s = service(source)

        var heardStream: VolumeStream? = null
        var heardLevel = -1f
        s.addOnVolumeChangedListener { stream, level ->
            heardStream = stream
            heardLevel = level
        }

        source.externalChange(VolumeStream.MEDIA, 7)

        assertEquals(VolumeStream.MEDIA, heardStream)
        assertEquals(0.7f, heardLevel, 1e-6f)
    }

    @Test
    fun aRemovedListenerStopsHearing() {
        val source = FakeVolumeSource()
        val s = service(source)

        var calls = 0
        val listener: (VolumeStream, Float) -> Unit = { _, _ -> calls++ }
        s.addOnVolumeChangedListener(listener)
        source.externalChange(VolumeStream.MEDIA, 1)
        assertEquals(1, calls)

        s.removeOnVolumeChangedListener(listener)
        source.externalChange(VolumeStream.MEDIA, 2)
        assertEquals("a removed listener was still called", 1, calls)
    }

    @Test
    fun theSameListenerRegisteredTwiceAndRemovedOnceStillHearsOnce() {
        // Identity, one occurrence: registering twice is legitimate, and removing once must not
        // silently unregister both. AnimationHandleImpl's subscription behaves the same way.
        val source = FakeVolumeSource()
        val s = service(source)

        var calls = 0
        val listener: (VolumeStream, Float) -> Unit = { _, _ -> calls++ }
        s.addOnVolumeChangedListener(listener)
        s.addOnVolumeChangedListener(listener)
        s.removeOnVolumeChangedListener(listener)

        source.externalChange(VolumeStream.MEDIA, 3)
        assertEquals(1, calls)
    }

    @Test
    fun removingAListenerThatWasNeverAddedIsHarmless() {
        val s = service()
        s.removeOnVolumeChangedListener { _, _ -> fail("never registered") }
    }

    @Test
    fun aListenerWrittenInlineCanNeverBeRemoved() {
        // Question 1, answered by the implementation rather than by review. The pair works exactly
        // as VolumeService declares it - removal is by identity, so a caller must keep the
        // reference it registered. This test exists to record the cost, not to bless it: every
        // other observable surface in the SDK returns a Disposable and has no such trap. Whether
        // the SDK signature should change is an ADR, and this asserts today's behaviour so that a
        // future change to it is a visible one.
        val source = FakeVolumeSource()
        val s = service(source)

        var calls = 0
        s.addOnVolumeChangedListener { _, _ -> calls++ }
        s.removeOnVolumeChangedListener { _, _ -> calls++ }  // a different object, removes nothing

        source.externalChange(VolumeStream.MEDIA, 4)
        assertEquals("removal by equality would have unregistered this", 1, calls)
    }

    @Test
    fun listenersRunWhereTheDispatcherSaysAndNotBefore() {
        val source = FakeVolumeSource()
        source.configure(VolumeStream.MEDIA, level = 0, maxLevel = 10)
        val dispatcher = QueuedDispatcher()
        val s = service(source, dispatcher)

        var calls = 0
        s.addOnVolumeChangedListener { _, _ -> calls++ }

        source.externalChange(VolumeStream.MEDIA, 5)
        assertEquals("the dispatcher was bypassed", 0, calls)
        assertEquals(1, dispatcher.pending)

        dispatcher.drain()
        assertEquals(1, calls)
    }

    @Test
    fun aQueuedListenerIsHandedTheLevelFromTheMomentOfTheChange() {
        // The value is read when the change happens, not when the queue drains. A listener told
        // "volume is now 0.5" about an event that happened three changes ago would be describing
        // the present while claiming to describe the past.
        val source = FakeVolumeSource()
        source.configure(VolumeStream.MEDIA, level = 0, maxLevel = 10)
        val dispatcher = QueuedDispatcher()
        val s = service(source, dispatcher)

        var heard = -1f
        s.addOnVolumeChangedListener { _, level -> heard = level }

        source.externalChange(VolumeStream.MEDIA, 2)
        source.configure(VolumeStream.MEDIA, level = 9, maxLevel = 10)
        dispatcher.drain()

        assertEquals(0.2f, heard, 1e-6f)
    }

    @Test
    fun disposingReleasesTheSubscriptionToTheSource() {
        val source = FakeVolumeSource()
        val s = service(source)
        assertEquals(1, source.listenerCount)

        s.dispose()
        assertEquals(0, source.listenerCount)

        s.dispose()  // idempotent, per Disposable
        assertEquals(0, source.listenerCount)
    }

    // --- refusing ------------------------------------------------------------

    @Test
    fun anEmptyRangeIsRefusedRatherThanNormalisedIntoAnInfinity() {
        val source = FakeVolumeSource()
        source.configure(VolumeStream.SYSTEM, level = 4, minLevel = 4, maxLevel = 4)
        try {
            service(source).levelOf(VolumeStream.SYSTEM)
            fail("expected an empty range to be refused")
        } catch (expected: IllegalStateException) {
            assertTrue(
                "message should name the range: ${expected.message}",
                expected.message!!.contains("min=4"),
            )
        }
    }
}
