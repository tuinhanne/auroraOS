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

/** How much the user should be interrupted. */
enum class NotificationImportance {
    /** Silent. Visible only when the user goes looking. */
    MIN,

    /** Appears in the shade, no sound. */
    LOW,

    /** Appears in the shade with a sound. */
    DEFAULT,

    /** Interrupts with a heads-up surface. Reserve for things that cannot wait. */
    HIGH,
}

/**
 * A notification as Aurora sees it.
 *
 * Deliberately not `android.app.Notification`: this module compiles without Android, and a
 * plain description also lets the same notification be rendered by the shade, by the island,
 * or by a test that asserts on it.
 *
 * @param key stable identity; posting twice with the same key updates rather than duplicates
 * @param whenMillis wall-clock time the event happened, for ordering
 */
data class AuroraNotification(
    val key: String,
    val packageName: String,
    val title: String,
    val text: String,
    val importance: NotificationImportance,
    val whenMillis: Long,
    val ongoing: Boolean = false,
)

/**
 * Posts and observes notifications.
 *
 * ## Update, not duplicate
 *
 * [post] is keyed by [AuroraNotification.key]. Posting an existing key replaces it in place.
 * This is why the key exists at all: a progress notification that duplicated on every update
 * would fill the shade.
 */
interface NotificationService : AuroraService {

    /** Posts a new notification, or updates the existing one with the same key. */
    fun post(notification: AuroraNotification)

    /** Removes a notification. Does nothing if the key is unknown. */
    fun cancel(key: String)

    /** Removes every notification this service posted. */
    fun cancelAll()

    /** Currently posted notifications, most recent first. */
    val active: List<AuroraNotification>

    /**
     * Observes the whole set.
     *
     * Receives the full list rather than a delta: a listener that missed one event would
     * otherwise diverge permanently, and reconciling deltas is more code in every listener
     * than sending the list is here.
     */
    fun addOnNotificationsChangedListener(listener: (List<AuroraNotification>) -> Unit)

    /** Removes a previously registered listener. */
    fun removeOnNotificationsChangedListener(listener: (List<AuroraNotification>) -> Unit)
}
