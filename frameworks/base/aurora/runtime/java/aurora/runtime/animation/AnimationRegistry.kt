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

import aurora.sdk.time.FrameTime

/**
 * Everything the engine is currently advancing.
 *
 * ## Insertion-ordered, never hashed
 *
 * Tick order is observable behaviour: listeners fire in it, and animations started from those
 * listeners are queued in it. A hash container would let two runs of the same program tick in
 * different orders, which is enough on its own to break RULE-009. `contracts/runtime.contract`
 * forbids `HashMap` and `HashSet` in this file specifically -- the hazard is iteration order,
 * not the container, so the ban is scoped to the one file where order becomes behaviour.
 *
 * ## RULE-013: deferred mutation
 *
 * Structural changes made *during* a tick are queued and applied when the frame ends. An
 * animation started from a listener therefore first advances on the following frame.
 *
 * The reason is determinism, not thread safety. If a listener could inject an animation into
 * the frame already in progress, whether it advanced on frame N or N+1 would depend on where
 * in the listener order the call happened -- and listener order is not something a caller
 * controls. See ADR-005.
 *
 * Removal is guarded twice: a queued removal is checked before each entry is ticked, and
 * [Tickable.isTickable] is re-read as well. So a handle cancelled or disposed by an earlier
 * listener in the same frame is skipped rather than advanced and then discarded. That is the
 * mechanism behind the frozen contract *after dispose returns, the handle never receives
 * another tick*.
 *
 * Not thread safe. The engine is driven from one frame source.
 */
class AnimationRegistry {

    /** Something the engine advances once per frame. */
    interface Tickable {

        /** Whether this should advance right now. Re-read immediately before every tick. */
        val isTickable: Boolean

        /** Advance by one frame. */
        fun tick(frameTime: FrameTime)
    }

    private val active = ArrayList<Tickable>()
    private val pendingAdd = ArrayList<Tickable>()
    private val pendingRemove = ArrayList<Tickable>()

    /** Whether a frame is being processed right now. */
    var isTicking: Boolean = false
        private set

    /**
     * The frame index being processed, or [NOT_TICKING] outside a frame.
     *
     * A handle records this when its execution is scheduled, so it can refuse to advance in
     * the same frame it was scheduled in. That is RULE-013 seen from the other side.
     */
    var tickingFrameIndex: Long = NOT_TICKING
        private set

    /**
     * Called when the registry has something to advance and may not have had before.
     *
     * The driver stops posting frames when nothing is running, so that an idle engine wakes no
     * core between refreshes. This is how it is told to start again.
     *
     * Two things a consumer has to know. It can fire **re-entrantly**, from inside the `tick()`
     * call the driver itself is on the stack for, because [commit] runs at the end of a frame.
     * And it can fire when the registry was never actually idle: a frame that removes its last
     * entry and adds another sees `active` empty in between. So it means *there is work now*,
     * not *there was none a moment ago*, and a consumer must be idempotent.
     */
    var onWake: (() -> Unit)? = null

    /** How many animations are being advanced. */
    val size: Int
        get() = active.size

    /** Registers [tickable], or queues the registration when called during a frame. */
    fun add(tickable: Tickable) {
        if (isTicking) {
            pendingRemove.remove(tickable)
            if (!pendingAdd.contains(tickable) && !active.contains(tickable)) {
                pendingAdd.add(tickable)
            }
            return
        }
        if (active.contains(tickable)) return
        val wasEmpty = active.isEmpty()
        active.add(tickable)
        if (wasEmpty) onWake?.invoke()
    }

    /** Unregisters [tickable], or queues the removal when called during a frame. */
    fun remove(tickable: Tickable) {
        if (isTicking) {
            pendingAdd.remove(tickable)
            if (!pendingRemove.contains(tickable)) pendingRemove.add(tickable)
            return
        }
        active.remove(tickable)
    }

    /**
     * Advances everything, in insertion order, with one shared [frameTime].
     *
     * The loop bound is read once, so entries queued during the frame cannot extend it, and no
     * copy of the list is made -- this runs on every frame of every gesture.
     */
    fun tick(frameTime: FrameTime) {
        check(!isTicking) {
            "tick() re-entered from inside a frame. The inner call would commit the outer " +
                "frame's queued work early and break RULE-013 silently."
        }
        isTicking = true
        tickingFrameIndex = frameTime.frameIndex
        try {
            var i = 0
            val bound = active.size
            while (i < bound) {
                val t = active[i]
                if (t.isTickable && !pendingRemove.contains(t)) {
                    t.tick(frameTime)
                }
                i++
            }
        } finally {
            isTicking = false
            tickingFrameIndex = NOT_TICKING
            commit()
        }
    }

    /** A copy, safe to mutate the registry while walking. Used by `cancelAll`. */
    fun snapshot(): List<Tickable> = ArrayList(active)

    /**
     * Drops everything.
     *
     * Illegal during a frame. Emptying the registry behind the handles' backs would leave every
     * one of them reporting [aurora.sdk.animation.AnimationState.RUNNING] while never being
     * ticked again — a lie the API has no way to detect. Cancelling mid-frame is
     * `Animator.cancelAll()`, which goes through the handles so their state stays true.
     *
     * @throws IllegalStateException if called while [tick] is running
     */
    fun clear() {
        check(!isTicking) {
            "clear() during a frame would empty the list tick() is iterating, and would leave " +
                "every handle reporting RUNNING with nothing advancing it. Use cancelAll()."
        }
        active.clear()
        // Empty by construction while not ticking, since commit() drains both at the end of
        // every frame. Cleared anyway so this stays correct if commit() ever stops doing that.
        pendingAdd.clear()
        pendingRemove.clear()
    }

    /**
     * Applies the frame's queued changes.
     *
     * Removals first, so that a frame which empties `active` and then refills it computes
     * `wasEmpty` correctly for [onWake]. Add-then-remove within one frame nets to nothing for a
     * different reason: [add] and [remove] each evict the tickable from the opposite queue, so
     * the two are disjoint by the time this runs.
     */
    private fun commit() {
        if (pendingRemove.isNotEmpty()) {
            var i = 0
            while (i < pendingRemove.size) {
                active.remove(pendingRemove[i])
                i++
            }
            pendingRemove.clear()
        }
        if (pendingAdd.isNotEmpty()) {
            val wasEmpty = active.isEmpty()
            active.addAll(pendingAdd)
            pendingAdd.clear()
            if (wasEmpty && active.isNotEmpty()) onWake?.invoke()
        }
    }

    companion object {
        /** [tickingFrameIndex] outside a frame. Negative, so every real frame index beats it. */
        const val NOT_TICKING: Long = -1L
    }
}
