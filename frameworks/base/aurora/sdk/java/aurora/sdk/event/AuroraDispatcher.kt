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
 * Decides where a subscriber runs.
 *
 * ## Why the bus does not just call subscribers directly
 *
 * On a device most subscribers must run on the main thread, but this module has no looper and
 * must not have one. The dispatcher is the seam: `aurora.platform` supplies one that posts to
 * the main thread, while tests supply one that runs inline or that they drain by hand.
 *
 * Without it, the bus would either have to know about Android or force every subscriber to
 * marshal for itself, and the second is the kind of rule that is followed everywhere except
 * the one place that matters.
 */
fun interface AuroraDispatcher {

    /** Runs [block] wherever this dispatcher runs things. */
    fun dispatch(block: () -> Unit)

    companion object {

        /**
         * Runs on the calling thread, before [dispatch] returns.
         *
         * The default, and what tests should use unless they are specifically testing
         * threading: it makes publication synchronous, so an assertion straight after a
         * publish sees the result without any waiting.
         */
        @JvmField
        val IMMEDIATE = AuroraDispatcher { block -> block() }
    }
}

/**
 * Queues work until [drain] is called.
 *
 * Lets a test control exactly when subscribers run, so ordering and re-entrancy can be
 * asserted without threads or sleeps. Also useful on device for batching a burst of events
 * into one frame.
 */
class QueuedDispatcher : AuroraDispatcher {

    private val lock = Any()
    private val queue = ArrayDeque<() -> Unit>()

    override fun dispatch(block: () -> Unit) {
        synchronized(lock) { queue.addLast(block) }
    }

    /** How many blocks are waiting. */
    val pending: Int
        get() = synchronized(lock) { queue.size }

    /**
     * Runs everything queued so far.
     *
     * Work queued *by* the blocks being run is left for the next drain rather than being run
     * in this one. Otherwise a subscriber that publishes in response to an event could spin
     * this call forever.
     *
     * @return how many blocks ran
     */
    fun drain(): Int {
        val batch: List<() -> Unit>
        synchronized(lock) {
            if (queue.isEmpty()) return 0
            batch = queue.toList()
            queue.clear()
        }
        batch.forEach { it() }
        return batch.size
    }

    /** Discards queued work without running it. */
    fun clear() {
        synchronized(lock) { queue.clear() }
    }
}
