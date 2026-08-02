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
 * Shapes linear progress into eased progress.
 *
 * ## Stateless, without exception
 *
 * RULE-009 requires that all mutable state live in an [AnimationStrategy]. An interpolator
 * that remembered anything between calls — a previous velocity, a last progress — would make
 * `seek()` and `restart()` non-repeatable, because `transform(0.5f)` twice would return two
 * different numbers. Implementations must be pure functions.
 *
 * That is also why the cubic Bézier solver is not here. It arrives in Sprint 06B as an
 * implementation of this interface; the four control points that describe it are already
 * design data in [aurora.sdk.design.Easing].
 */
fun interface Interpolator {

    /**
     * @param progress usually 0..1, but not clamped: an overshooting curve legitimately
     *     produces values outside the range, and clamping here would silently flatten it
     * @return the shaped progress
     */
    fun transform(progress: Float): Float

    companion object {

        /**
         * The identity element: returns its argument.
         *
         * Sprint 06A ships no solver, so this is the only interpolator that exists. It is
         * not an exception to "no executable code in the SDK" — it computes nothing.
         */
        @JvmField
        val LINEAR = Interpolator { it }
    }
}
