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

/**
 * Marker for anything publishable on [AuroraEventBus].
 *
 * Events should be immutable. A subscriber may hold one, hand it to another thread, or receive
 * it as a sticky value long after publication, and a mutable event turns every one of those
 * into a race.
 */
interface AuroraEvent

/**
 * Where an event travels.
 *
 * ## Why events are not global
 *
 * A single global bus makes every subscriber a candidate for every event, so cost grows with
 * the product of publishers and subscribers, and a window that closed keeps being offered
 * events it no longer cares about. Scopes cut that: a publish only walks the subscribers of
 * one scope.
 *
 * The second reason matters more than performance. A scope is a lifetime. When a window goes
 * away, [AuroraEventBus.clearScope] drops its subscribers and its sticky values in one call,
 * which is far harder to get wrong than asking every component to unsubscribe itself.
 *
 * ## Identity
 *
 * Two scopes are the same when their [name] and [id] match. [WINDOW] is a template: real
 * window scopes come from [window], each with its own id, so two windows do not share a bus.
 */
data class EventScope(val name: String, val id: String = "") {

    init {
        require(name.isNotBlank()) { "scope name must not be blank" }
    }

    override fun toString(): String = if (id.isEmpty()) name else "$name:$id"

    companion object {
        /** Process-wide events: theme, power, configuration. */
        @JvmField
        val SYSTEM = EventScope("system")

        /** Notification posting and dismissal. */
        @JvmField
        val NOTIFICATION = EventScope("notification")

        /** Gesture routing. */
        @JvmField
        val GESTURE = EventScope("gesture")

        /** Overlay surfaces: island, panels, bubbles. */
        @JvmField
        val OVERLAY = EventScope("overlay")

        /**
         * A scope private to one window.
         *
         * @param windowId identity of the window; distinct ids never see each other's events
         */
        @JvmStatic
        fun window(windowId: String): EventScope = EventScope("window", windowId)
    }
}

/**
 * Delivery order within a scope.
 *
 * An enum rather than an integer for the same reason [aurora.sdk.service.GesturePriority] is:
 * integers invite each caller to invent a number, the numbers inflate as newcomers try to
 * outrank incumbents, and nothing records what the ordering meant. Declared in delivery order,
 * so the natural [compareTo] is the ranking.
 *
 * Subscribers of equal priority are delivered to in registration order, which keeps behaviour
 * repeatable rather than depending on hash iteration.
 */
enum class EventPriority {
    /** Runs first. For subscribers that must observe or veto before anything else acts. */
    HIGHEST,

    /** Above ordinary subscribers. */
    HIGH,

    /** The default. */
    NORMAL,

    /** After ordinary subscribers. */
    LOW,

    /** Runs last. For logging and diagnostics that must see the final state. */
    LOWEST,
}
