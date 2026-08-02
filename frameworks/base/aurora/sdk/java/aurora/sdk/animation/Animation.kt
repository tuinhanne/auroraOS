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
 * What is being animated, described as data.
 *
 * Immutable and free of behaviour, so one instance can be played, restarted and played again
 * on a different handle with nothing to reset. That matters more than it sounds: a component
 * animating on every volume key press builds this once and reuses it.
 *
 * @param name for logs and diagnostics only. Never a lookup key -- handles are held, not
 *     looked up, so a duplicate name is harmless and a rename breaks nothing.
 * @param spec how progress is decided: [TimedSpec] now, [PhysicsSpec] from Sprint 06B
 * @param from the value at progress 0
 * @param to the value at progress 1
 *
 * ## Equality
 *
 * Generated structurally, which reaches through [spec] into its [Interpolator]. An interpolator
 * written as a lambda has identity equality, so two otherwise identical animations built with
 * separately constructed interpolators are not equal. Animations sharing [Interpolator.LINEAR],
 * which is a singleton, are. Nothing depends on this today; it is written down so that Sprint
 * 06B, which adds the second interpolator, is not surprised by it.
 */
data class Animation(
    val name: String,
    val spec: AnimationSpec,
    val from: Float = 0f,
    val to: Float = 1f,
) {

    init {
        require(name.isNotBlank()) {
            "an animation needs a name; an unnamed one is undiagnosable in a log"
        }
        // from and to are the payload. A NaN bound poisons every value the animation will ever
        // produce, silently and forever, and an infinite one is no better. Caught here, where
        // the caller that computed the bad number is still on the stack.
        require(from.isFinite()) { "animation '$name' has a non-finite from: $from" }
        require(to.isFinite()) { "animation '$name' has a non-finite to: $to" }
    }

    /**
     * The value at a given *eased* progress.
     *
     * Pure, and the entire progress-to-value mapping in Aurora: keeping it here rather than
     * inside the engine is what puts it within reach of a unit test.
     *
     * ## Why this form and not `from + (to - from) * t`
     *
     * Both are deterministic, so RULE-009 holds either way. They differ at the endpoints. The
     * other form is exact at 0 but not guaranteed exact at 1, so an animation that finishes can
     * land an ULP short of [to] — and [to] is the value a caller compares against and the
     * position the interface comes to rest at. This form is exact at both ends, in exchange for
     * giving up a guarantee of monotonicity in the interior, where an ULP of wobble is invisible.
     *
     * Deliberately unclamped. A bouncy spring produces eased progress above 1, and clamping
     * would flatten the overshoot into nothing while still looking correct. Note this is the
     * opposite of [TimedSpec.elapsedForProgress], which does clamp: that one takes a normalised
     * seek position, this one takes eased progress.
     */
    fun valueAt(easedProgress: Float): Float =
        from * (1f - easedProgress) + to * easedProgress
}
