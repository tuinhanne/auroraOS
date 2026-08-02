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

/**
 * Turns elapsed time into progress.
 *
 * ## The seam Sprint 06B plugs into
 *
 * `ExecutionTimeline` works out *how long this execution has been running*; a strategy works
 * out *how far through it therefore is*. Splitting them there is what lets 06B add
 * `SpringStrategy`, `DecayStrategy`, `SnapStrategy` and `FlingStrategy` as new files while
 * the handle, the registry, the driver and the state machine stay untouched. See ADR-006.
 *
 * ## The one legitimate home for mutable state
 *
 * RULE-009 puts all mutable animation state here. A physics strategy is inherently
 * incremental -- its position is the result of integrating from its previous position -- and
 * that is fine, because the sequence of [advance] calls is itself deterministic. What is not
 * fine is the same state hiding in an [Interpolator] or a design token, where seeking and
 * restarting would silently stop being repeatable.
 *
 * ## Allocation
 *
 * [advance] returns nothing and the results are read from properties. Returning a result
 * object would allocate once per animation per frame: at 120Hz with twenty animations that is
 * 2400 short-lived objects a second, on the path that runs during every gesture.
 */
interface AnimationStrategy {

    /** Unshaped progress, 0..1. For a timed animation, linear in elapsed time. */
    val progress: Float

    /**
     * Progress after shaping. Equal to [progress] when the strategy applies no curve.
     *
     * The value a caller sees is `animation.valueAt(easedProgress)`. Keeping the shaping
     * inside the strategy is what stops the engine having to ask what kind of spec it holds:
     * a [PhysicsSpec] has no interpolator to apply.
     *
     * May legitimately leave 0..1. An overshooting spring is supposed to.
     */
    val easedProgress: Float

    /**
     * Whether the motion has ended.
     *
     * A timed animation ends at the end of its timeline; a spring ends when it settles.
     */
    val isFinished: Boolean

    /**
     * Advances to [elapsedNanos].
     *
     * @param elapsedNanos time since this execution began, from `ExecutionTimeline`. Never a
     *     clock reading (RULE-008).
     * @param deltaNanos time since the previous frame. Physics integration needs it; a timed
     *     strategy ignores it, because deriving progress from elapsed time rather than
     *     accumulating deltas is what stops a dropped frame causing drift.
     */
    fun advance(elapsedNanos: Long, deltaNanos: Long)

    /** Returns to the state of a fresh execution. Called by restart. */
    fun reset()

    /**
     * Optional operation.
     *
     * A physics strategy may reject seeking with `UnsupportedOperationException`. That is a
     * deliberate part of this design, not a gap: a spring position is the result of
     * integrating from its previous state, so there is no elapsed time to jump to. See
     * ADR-002.
     *
     * Callers that must support both kinds should offer seeking only when the animation spec
     * is a [TimedSpec].
     */
    fun seekTo(progress: Float)
}
