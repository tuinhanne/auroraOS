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

package aurora.sdk.event

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Events used only by these tests. */
private data class ThemeChanged(val dark: Boolean) : AuroraEvent
private data class VolumeChanged(val level: Float) : AuroraEvent
private class Unrelated : AuroraEvent

class AuroraEventBusTest {

    private lateinit var bus: AuroraEventBus

    @Before
    fun setUp() {
        // Immediate dispatch: a publish is finished by the time it returns, so assertions
        // need no waiting and no sleeps.
        bus = AuroraEventBus()
    }

    // --- publish / subscribe ------------------------------------------------

    @Test
    fun subscriberReceivesPublishedEvent() {
        val seen = mutableListOf<ThemeChanged>()
        bus.subscribe(ThemeChanged::class.java) { seen.add(it) }

        bus.publish(ThemeChanged(dark = true))

        assertEquals(1, seen.size)
        assertTrue(seen[0].dark)
    }

    @Test
    fun publishWithNoSubscribersIsHarmless() {
        bus.publish(ThemeChanged(dark = false))
        assertEquals(0, bus.subscriberCount())
    }

    @Test
    fun onlyMatchingTypesAreDelivered() {
        var themes = 0
        var volumes = 0
        bus.subscribe(ThemeChanged::class.java) { themes++ }
        bus.subscribe(VolumeChanged::class.java) { volumes++ }

        bus.publish(ThemeChanged(dark = true))
        bus.publish(Unrelated())

        assertEquals(1, themes)
        assertEquals(0, volumes)
    }

    @Test
    fun sameSubscriberSeesEveryPublish() {
        var count = 0
        bus.subscribe(ThemeChanged::class.java) { count++ }

        repeat(3) { bus.publish(ThemeChanged(dark = true)) }

        assertEquals(3, count)
    }

    // --- unsubscribe / disposable -------------------------------------------

    @Test
    fun disposeStopsDelivery() {
        var count = 0
        val token = bus.subscribe(ThemeChanged::class.java) { count++ }

        bus.publish(ThemeChanged(dark = true))
        token.dispose()
        bus.publish(ThemeChanged(dark = false))

        assertEquals("no event should arrive after dispose", 1, count)
    }

    @Test
    fun disposeIsIdempotent() {
        val token = bus.subscribe(ThemeChanged::class.java) { }
        assertFalse(token.isDisposed)

        token.dispose()
        token.dispose() // Teardown paths run twice more often than anyone expects.

        assertTrue(token.isDisposed)
        assertEquals(0, bus.subscriberCount())
    }

    @Test
    fun disposeRemovesOnlyItsOwnSubscription() {
        var a = 0
        var b = 0
        val tokenA = bus.subscribe(ThemeChanged::class.java) { a++ }
        bus.subscribe(ThemeChanged::class.java) { b++ }

        tokenA.dispose()
        bus.publish(ThemeChanged(dark = true))

        assertEquals(0, a)
        assertEquals(1, b)
        assertEquals(1, bus.subscriberCount())
    }

    @Test
    fun compositeDisposableReleasesEverythingItOwns() {
        val composite = CompositeDisposable()
        var count = 0
        composite.add(bus.subscribe(ThemeChanged::class.java) { count++ })
        composite.add(bus.subscribe(ThemeChanged::class.java) { count++ })
        assertEquals(2, composite.size)

        composite.dispose()
        bus.publish(ThemeChanged(dark = true))

        assertEquals(0, count)
        assertTrue(composite.isDisposed)
        assertEquals(0, bus.subscriberCount())
    }

    @Test
    fun compositeDisposesLateArrivalsImmediately() {
        val composite = CompositeDisposable()
        composite.dispose()

        var count = 0
        val late = bus.subscribe(ThemeChanged::class.java) { count++ }
        composite.add(late)

        // Registered during teardown; it must not outlive the owner.
        assertTrue(late.isDisposed)
        bus.publish(ThemeChanged(dark = true))
        assertEquals(0, count)
    }

    // --- priority ------------------------------------------------------------

    @Test
    fun higherPriorityRunsFirst() {
        val order = mutableListOf<String>()
        bus.subscribe(ThemeChanged::class.java, priority = EventPriority.LOWEST) { order.add("lowest") }
        bus.subscribe(ThemeChanged::class.java, priority = EventPriority.NORMAL) { order.add("normal") }
        bus.subscribe(ThemeChanged::class.java, priority = EventPriority.HIGHEST) { order.add("highest") }
        bus.subscribe(ThemeChanged::class.java, priority = EventPriority.LOW) { order.add("low") }
        bus.subscribe(ThemeChanged::class.java, priority = EventPriority.HIGH) { order.add("high") }

        bus.publish(ThemeChanged(dark = true))

        assertEquals(listOf("highest", "high", "normal", "low", "lowest"), order)
    }

    @Test
    fun equalPriorityKeepsRegistrationOrder() {
        val order = mutableListOf<Int>()
        repeat(5) { i -> bus.subscribe(ThemeChanged::class.java) { order.add(i) } }

        bus.publish(ThemeChanged(dark = true))

        // Repeatable ordering, rather than whatever a hash iteration happens to give.
        assertEquals(listOf(0, 1, 2, 3, 4), order)
    }

    @Test
    fun priorityEnumIsDeclaredInDeliveryOrder() {
        assertTrue(EventPriority.HIGHEST < EventPriority.HIGH)
        assertTrue(EventPriority.HIGH < EventPriority.NORMAL)
        assertTrue(EventPriority.NORMAL < EventPriority.LOW)
        assertTrue(EventPriority.LOW < EventPriority.LOWEST)
    }

    // --- sticky ---------------------------------------------------------------

    @Test
    fun stickyEventReachesASubscriberThatArrivedLater() {
        bus.publishSticky(ThemeChanged(dark = true))

        var received: ThemeChanged? = null
        bus.subscribe(ThemeChanged::class.java) { received = it }

        // Without sticky delivery this surface would sit in the wrong theme until the user
        // happened to change it again.
        assertTrue(received!!.dark)
    }

    @Test
    fun stickyAlsoReachesSubscribersPresentAtPublishTime() {
        var count = 0
        bus.subscribe(ThemeChanged::class.java) { count++ }

        bus.publishSticky(ThemeChanged(dark = true))

        assertEquals(1, count)
    }

    @Test
    fun onlyTheLatestStickyIsKept() {
        bus.publishSticky(ThemeChanged(dark = true))
        bus.publishSticky(ThemeChanged(dark = false))

        var received: ThemeChanged? = null
        bus.subscribe(ThemeChanged::class.java) { received = it }

        assertFalse(received!!.dark)
    }

    @Test
    fun plainPublishLeavesNoStickyBehind() {
        bus.publish(ThemeChanged(dark = true))

        var called = false
        bus.subscribe(ThemeChanged::class.java) { called = true }

        assertFalse("publish() is for things that happened, not for state", called)
        assertNull(bus.stickyOf(ThemeChanged::class.java))
    }

    @Test
    fun stickyCanBeReadAndRemoved() {
        val event = ThemeChanged(dark = true)
        bus.publishSticky(event)

        assertSame(event, bus.stickyOf(ThemeChanged::class.java))
        assertTrue(bus.removeSticky(ThemeChanged::class.java))
        assertNull(bus.stickyOf(ThemeChanged::class.java))
        assertFalse("removing twice reports nothing to remove",
            bus.removeSticky(ThemeChanged::class.java))
    }

    // --- scope ----------------------------------------------------------------

    @Test
    fun scopesDoNotSeeEachOthersEvents() {
        var system = 0
        var overlay = 0
        bus.subscribe(ThemeChanged::class.java, EventScope.SYSTEM) { system++ }
        bus.subscribe(ThemeChanged::class.java, EventScope.OVERLAY) { overlay++ }

        bus.publish(ThemeChanged(dark = true), EventScope.SYSTEM)

        assertEquals(1, system)
        assertEquals(0, overlay)
    }

    @Test
    fun windowScopesAreIsolatedById() {
        val one = EventScope.window("w1")
        val two = EventScope.window("w2")
        var first = 0
        var second = 0
        bus.subscribe(ThemeChanged::class.java, one) { first++ }
        bus.subscribe(ThemeChanged::class.java, two) { second++ }

        bus.publish(ThemeChanged(dark = true), one)

        assertEquals(1, first)
        assertEquals(0, second)
        assertEquals(one, EventScope.window("w1"))
    }

    @Test
    fun stickyIsPerScope() {
        bus.publishSticky(ThemeChanged(dark = true), EventScope.SYSTEM)

        var overlayReceived = false
        bus.subscribe(ThemeChanged::class.java, EventScope.OVERLAY) { overlayReceived = true }

        assertFalse(overlayReceived)
        assertNull(bus.stickyOf(ThemeChanged::class.java, EventScope.OVERLAY))
    }

    @Test
    fun clearScopeRetiresSubscribersAndSticky() {
        val scope = EventScope.window("closing")
        var count = 0
        val token = bus.subscribe(ThemeChanged::class.java, scope) { count++ }
        bus.publishSticky(ThemeChanged(dark = true), scope)
        assertEquals(1, count)

        bus.clearScope(scope)
        bus.publish(ThemeChanged(dark = false), scope)

        // One call retires everything the window owned, instead of trusting each component
        // to have disposed itself.
        assertEquals(1, count)
        assertTrue(token.isDisposed)
        assertNull(bus.stickyOf(ThemeChanged::class.java, scope))
        assertEquals(0, bus.subscriberCount(scope))
    }

    @Test
    fun clearRetiresEveryScope() {
        bus.subscribe(ThemeChanged::class.java, EventScope.SYSTEM) { }
        bus.subscribe(ThemeChanged::class.java, EventScope.OVERLAY) { }
        bus.publishSticky(ThemeChanged(dark = true), EventScope.SYSTEM)

        bus.clear()

        assertEquals(0, bus.subscriberCount(EventScope.SYSTEM))
        assertEquals(0, bus.subscriberCount(EventScope.OVERLAY))
        assertTrue(bus.activeScopes.isEmpty())
    }

    @Test
    fun scopeNameMustNotBeBlank() {
        try {
            EventScope("   ")
            org.junit.Assert.fail("a blank scope name should be rejected")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    // --- isolation ------------------------------------------------------------

    @Test
    fun aThrowingSubscriberDoesNotStopTheOthers() {
        val reached = mutableListOf<String>()
        bus.subscribe(ThemeChanged::class.java, priority = EventPriority.HIGHEST) {
            reached.add("first")
            throw RuntimeException("subscriber is broken")
        }
        bus.subscribe(ThemeChanged::class.java, priority = EventPriority.LOWEST) {
            reached.add("second")
        }

        bus.publish(ThemeChanged(dark = true))

        // A broken logging subscriber must not be able to stop a state update reaching the
        // component that renders it.
        assertEquals(listOf("first", "second"), reached)
        assertEquals(1L, bus.errorCount)
    }

    // --- dispatcher -----------------------------------------------------------

    @Test
    fun queuedDispatcherDefersUntilDrained() {
        val queued = QueuedDispatcher()
        val deferredBus = AuroraEventBus(queued)
        var count = 0
        deferredBus.subscribe(ThemeChanged::class.java) { count++ }

        deferredBus.publish(ThemeChanged(dark = true))
        assertEquals("nothing runs before drain", 0, count)
        assertEquals(1, queued.pending)

        assertEquals(1, queued.drain())
        assertEquals(1, count)
    }

    @Test
    fun disposeBeforeDrainPreventsDelivery() {
        val queued = QueuedDispatcher()
        val deferredBus = AuroraEventBus(queued)
        var count = 0
        val token = deferredBus.subscribe(ThemeChanged::class.java) { count++ }

        deferredBus.publish(ThemeChanged(dark = true))
        token.dispose()
        queued.drain()

        // The subscription died between publication and delivery; delivering anyway is the
        // leak dispose() exists to prevent.
        assertEquals(0, count)
    }

    @Test
    fun drainDoesNotRunWorkQueuedDuringItself() {
        val queued = QueuedDispatcher()
        val deferredBus = AuroraEventBus(queued)
        var themes = 0
        deferredBus.subscribe(ThemeChanged::class.java) {
            themes++
            // A subscriber that republishes must not be able to spin drain() forever.
            if (themes < 3) deferredBus.publish(ThemeChanged(dark = true))
        }

        deferredBus.publish(ThemeChanged(dark = true))
        assertEquals(1, queued.drain())
        assertEquals(1, themes)
        assertEquals(1, queued.pending)
    }

    @Test
    fun queuedDispatcherCanDiscardWork() {
        val queued = QueuedDispatcher()
        val deferredBus = AuroraEventBus(queued)
        var count = 0
        deferredBus.subscribe(ThemeChanged::class.java) { count++ }

        deferredBus.publish(ThemeChanged(dark = true))
        queued.clear()
        queued.drain()

        assertEquals(0, count)
        assertEquals(0, queued.pending)
    }

    // --- bookkeeping ----------------------------------------------------------

    @Test
    fun subscriberCountTracksLiveSubscriptions() {
        assertEquals(0, bus.subscriberCount())
        val a = bus.subscribe(ThemeChanged::class.java) { }
        val b = bus.subscribe(VolumeChanged::class.java) { }
        assertEquals(2, bus.subscriberCount())

        a.dispose()
        assertEquals(1, bus.subscriberCount())
        b.dispose()
        assertEquals(0, bus.subscriberCount())
    }

    @Test
    fun activeScopesReportsWhereWorkExists() {
        assertTrue(bus.activeScopes.isEmpty())
        bus.subscribe(ThemeChanged::class.java, EventScope.GESTURE) { }
        bus.publishSticky(ThemeChanged(dark = true), EventScope.NOTIFICATION)

        assertTrue(bus.activeScopes.contains(EventScope.GESTURE))
        assertTrue(bus.activeScopes.contains(EventScope.NOTIFICATION))
    }
}
