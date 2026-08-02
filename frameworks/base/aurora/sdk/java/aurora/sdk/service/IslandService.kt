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

package aurora.sdk.service

/** How much room an island presentation is asking for. */
enum class IslandSize {
    /** Collapsed to the cutout itself. A dot or a pair of small indicators. */
    COMPACT,

    /** A pill wider than the cutout, enough for a short line and an icon. */
    EXPANDED,

    /** A full card. Only in response to the user opening it. */
    FULL,
}

/**
 * Something asking to occupy the island.
 *
 * @param id stable identity; presenting the same id again updates in place
 * @param priority higher wins when several presentations compete
 * @param autoDismissMillis how long before it collapses on its own, or 0 to stay until
 *     dismissed
 */
data class IslandPresentation(
    val id: String,
    val priority: Int,
    val size: IslandSize,
    val title: String,
    val subtitle: String = "",
    val autoDismissMillis: Long = 0L,
)

/**
 * Owns the area around the display cutout.
 *
 * ## One at a time, by priority
 *
 * The island is a single physical space, so presentations queue rather than stack. A charging
 * indicator must not push away an in-progress call. [present] therefore takes a priority and
 * returns whether the request actually became visible, instead of silently losing.
 *
 * ## Why it is a service and not a widget
 *
 * The island reacts to things no single app can see: charging, calls, volume, timers. Making
 * it a system service means those sources publish to one owner that arbitrates, rather than
 * each drawing its own overlay and fighting for the same pixels.
 */
interface IslandService : AuroraService {

    /**
     * Requests the island.
     *
     * @return true if this presentation is now showing, false if something with a higher
     *     priority holds it
     */
    fun present(presentation: IslandPresentation): Boolean

    /** Removes a presentation. Does nothing if [id] is not present. */
    fun dismiss(id: String)

    /** Removes everything, including queued presentations. */
    fun dismissAll()

    /** What is showing right now, or null when the island is idle. */
    val current: IslandPresentation?

    /** Whether the display actually has a cutout to build an island around. */
    val isSupported: Boolean

    /** Observes what the island is showing. Receives null when it becomes idle. */
    fun addOnIslandChangedListener(listener: (IslandPresentation?) -> Unit)

    /** Removes a previously registered listener. */
    fun removeOnIslandChangedListener(listener: (IslandPresentation?) -> Unit)
}
