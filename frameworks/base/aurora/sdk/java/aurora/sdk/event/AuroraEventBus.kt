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

import java.util.concurrent.atomic.AtomicLong

/**
 * Scoped, prioritised publish/subscribe.
 *
 * ```kotlin
 * val token = bus.subscribe(ThemeChanged::class.java, EventScope.SYSTEM) { e -> apply(e) }
 * bus.publishSticky(ThemeChanged(dark = true), EventScope.SYSTEM)
 * token.dispose()
 * ```
 *
 * ## Sticky events
 *
 * Some state is only announced when it changes. A surface created afterwards would sit in the
 * wrong state until the next change, which for a theme could be never. [publishSticky] keeps
 * the last event of its type in its scope, and hands it to every later subscriber at
 * subscription time. Use it for *state*; use [publish] for things that happened.
 *
 * ## Isolation between subscribers
 *
 * A subscriber that throws does not stop the others. One faulty logging subscriber must not
 * be able to prevent a state update reaching the component that renders it, and with a shared
 * bus that is exactly what an uncaught exception would do.
 *
 * ## Thread safety
 *
 * Registration and lookup are guarded. Subscribers are invoked outside the lock, so a
 * subscriber may publish, subscribe or dispose without deadlocking. Where they actually run is
 * the [AuroraDispatcher]'s business.
 */
class AuroraEventBus(
    private val dispatcher: AuroraDispatcher = AuroraDispatcher.IMMEDIATE,
) {

    private class Registration(
        val type: Class<out AuroraEvent>,
        val priority: EventPriority,
        val sequence: Long,
        val subscriber: AuroraSubscriber<AuroraEvent>,
    ) {
        @Volatile
        var cancelled = false
    }

    private val lock = Any()
    private val sequencer = AtomicLong(0)

    /** scope -> registrations, kept sorted by priority then registration order. */
    private val registrations = mutableMapOf<EventScope, MutableList<Registration>>()

    /** scope -> event type -> last sticky event of that type. */
    private val sticky = mutableMapOf<EventScope, MutableMap<Class<out AuroraEvent>, AuroraEvent>>()

    private val errors = AtomicLong(0)

    /** How many subscriber invocations have thrown. Intended for diagnostics and tests. */
    val errorCount: Long
        get() = errors.get()

    /**
     * Registers [subscriber] for events of [type] published into [scope].
     *
     * If a sticky event of a matching type is already present in [scope], it is delivered
     * before this call returns control to the dispatcher.
     *
     * @return a handle; call [Disposable.dispose] to unsubscribe
     */
    @Suppress("UNCHECKED_CAST")
    @JvmOverloads
    fun <T : AuroraEvent> subscribe(
        type: Class<T>,
        scope: EventScope = EventScope.SYSTEM,
        priority: EventPriority = EventPriority.NORMAL,
        subscriber: AuroraSubscriber<T>,
    ): Disposable {
        val registration = Registration(
            type = type,
            priority = priority,
            sequence = sequencer.incrementAndGet(),
            subscriber = subscriber as AuroraSubscriber<AuroraEvent>,
        )

        val pending: AuroraEvent?
        synchronized(lock) {
            val list = registrations.getOrPut(scope) { mutableListOf() }
            list.add(registration)
            // Sorted once here rather than on every publish: subscriptions are rare and
            // publications are not.
            list.sortWith(compareBy({ it.priority.ordinal }, { it.sequence }))
            pending = sticky[scope]?.entries?.firstOrNull { type.isAssignableFrom(it.key) }?.value
        }

        if (pending != null) {
            deliver(registration, pending)
        }
        return RegistrationDisposable(scope, registration)
    }

    /**
     * Publishes [event] into [scope].
     *
     * Delivered to every live subscriber whose type matches, in priority order, then in
     * registration order within a priority.
     */
    @JvmOverloads
    fun publish(event: AuroraEvent, scope: EventScope = EventScope.SYSTEM) {
        val targets: List<Registration> = synchronized(lock) {
            registrations[scope]
                ?.filter { !it.cancelled && it.type.isInstance(event) }
                ?.toList()
                ?: emptyList()
        }
        targets.forEach { deliver(it, event) }
    }

    /**
     * Publishes [event] and retains it as the sticky value for its exact type in [scope].
     *
     * A later [publishSticky] of the same type replaces it. Use for state, not for
     * notifications of something that happened.
     */
    @JvmOverloads
    fun publishSticky(event: AuroraEvent, scope: EventScope = EventScope.SYSTEM) {
        synchronized(lock) {
            sticky.getOrPut(scope) { mutableMapOf() }[event.javaClass] = event
        }
        publish(event, scope)
    }

    /** Returns the sticky event of [type] in [scope], or null when there is none. */
    @Suppress("UNCHECKED_CAST")
    @JvmOverloads
    fun <T : AuroraEvent> stickyOf(type: Class<T>, scope: EventScope = EventScope.SYSTEM): T? =
        synchronized(lock) {
            sticky[scope]?.entries?.firstOrNull { type.isAssignableFrom(it.key) }?.value as T?
        }

    /**
     * Forgets the sticky event of [type] in [scope].
     *
     * @return true if there was one to remove
     */
    @JvmOverloads
    fun removeSticky(
        type: Class<out AuroraEvent>,
        scope: EventScope = EventScope.SYSTEM,
    ): Boolean = synchronized(lock) {
        val map = sticky[scope] ?: return false
        val key = map.keys.firstOrNull { type.isAssignableFrom(it) } ?: return false
        map.remove(key)
        if (map.isEmpty()) sticky.remove(scope)
        true
    }

    /**
     * Drops every subscriber and sticky value in [scope].
     *
     * This is what makes a scope a lifetime. When a window closes, one call retires everything
     * belonging to it, instead of trusting each component to have disposed itself.
     */
    fun clearScope(scope: EventScope) {
        val removed: List<Registration> = synchronized(lock) {
            sticky.remove(scope)
            registrations.remove(scope)?.toList() ?: emptyList()
        }
        removed.forEach { it.cancelled = true }
    }

    /** Drops everything, in every scope. */
    fun clear() {
        val removed: List<Registration> = synchronized(lock) {
            val all = registrations.values.flatten()
            registrations.clear()
            sticky.clear()
            all
        }
        removed.forEach { it.cancelled = true }
    }

    /** Number of live subscribers in [scope]. */
    @JvmOverloads
    fun subscriberCount(scope: EventScope = EventScope.SYSTEM): Int = synchronized(lock) {
        registrations[scope]?.count { !it.cancelled } ?: 0
    }

    /** Scopes that currently hold at least one subscriber or sticky value. */
    val activeScopes: Set<EventScope>
        get() = synchronized(lock) { registrations.keys + sticky.keys }

    private fun deliver(registration: Registration, event: AuroraEvent) {
        dispatcher.dispatch {
            // Re-checked inside the dispatched block: with an asynchronous dispatcher the
            // subscription may have been disposed between publication and delivery, and
            // delivering to a disposed subscriber is exactly the leak dispose() was for.
            if (registration.cancelled) return@dispatch
            try {
                registration.subscriber.onEvent(event)
            } catch (e: Exception) {
                errors.incrementAndGet()
            }
        }
    }

    private inner class RegistrationDisposable(
        private val scope: EventScope,
        private val registration: Registration,
    ) : Disposable {

        override val isDisposed: Boolean
            get() = registration.cancelled

        override fun dispose() {
            synchronized(lock) {
                if (registration.cancelled) return
                registration.cancelled = true
                val list = registrations[scope] ?: return
                list.remove(registration)
                if (list.isEmpty()) registrations.remove(scope)
            }
        }
    }
}
