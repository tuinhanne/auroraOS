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

package aurora.sdk.design

/**
 * A cubic Bézier easing curve, given by its two control points.
 *
 * The curve always runs from (0,0) to (1,1); only the control points vary. These are the same
 * four numbers CSS `cubic-bezier()` takes and that Android's `PathInterpolator` accepts, which
 * is why the curve is stored this way rather than as a platform interpolator object: it can be
 * handed to any of them without this module depending on any of them.
 */
data class Easing(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
)

/**
 * A spring, described the way a physics-based animator wants it.
 *
 * Springs are preferred over fixed-duration curves for anything the finger drives, because a
 * spring can absorb the velocity the gesture ended with. A duration-based curve cannot: it
 * restarts from zero velocity and the motion visibly detaches from the finger.
 *
 * @param stiffness how hard the spring pulls toward its target; higher is faster
 * @param dampingRatio 1.0 settles without overshoot, below 1.0 overshoots and bounces
 */
data class Spring(
    val stiffness: Float,
    val dampingRatio: Float,
)

/**
 * Motion tokens: how long things take and how they accelerate.
 *
 * Durations are milliseconds. As with the other token files these are plain numbers, so this
 * module stays free of any animation framework.
 *
 * ## Choosing between a duration and a spring
 *
 * Use a [Spring] whenever the motion continues something the user was physically doing, such
 * as releasing a swipe. Use a duration and an [Easing] for motion the system initiates on its
 * own, such as a dialog appearing.
 *
 * ## Why durations scale with distance
 *
 * A short move and a full-screen transition should not take the same time; matching durations
 * makes the long one feel slow and the short one feel sluggish. Pick the duration from how far
 * the element travels, not from which component it belongs to.
 */
object MotionTokens {

    // --- Durations, in milliseconds ----------------------------------------

    /** 0ms. No animation. Use for state that must appear to change instantly. */
    const val DURATION_INSTANT: Int = 0

    /** 100ms. Small, local feedback such as a ripple or a state colour change. */
    const val DURATION_FAST: Int = 100

    /** 200ms. Short travel: a control expanding, a chip appearing. */
    const val DURATION_SHORT: Int = 200

    /** 300ms. The default for most on-screen transitions. */
    const val DURATION_MEDIUM: Int = 300

    /** 500ms. Long travel, such as a full-screen push. */
    const val DURATION_LONG: Int = 500

    /** 700ms. Reserve for deliberately slow, attention-carrying motion. */
    const val DURATION_EXTRA_LONG: Int = 700

    // --- Easing curves -----------------------------------------------------

    /**
     * Linear. Constant speed.
     *
     * Almost always wrong for spatial movement, which reads as mechanical without
     * acceleration. Correct for continuous non-spatial change such as a colour cross-fade or
     * a progress indicator.
     */
    @JvmField
    val EASING_LINEAR = Easing(0f, 0f, 1f, 1f)

    /**
     * Standard. Accelerates quickly, decelerates gently into place.
     *
     * The default for elements moving within the screen. The long tail is what makes motion
     * feel like it settles rather than stops.
     */
    @JvmField
    val EASING_STANDARD = Easing(0.2f, 0f, 0f, 1f)

    /**
     * Decelerate. Starts at full speed and eases out.
     *
     * For elements entering the screen: they were already moving before they became visible.
     */
    @JvmField
    val EASING_DECELERATE = Easing(0f, 0f, 0f, 1f)

    /**
     * Accelerate. Eases in and leaves at full speed.
     *
     * For elements leaving the screen. Pairing accelerate-out with decelerate-in is what makes
     * a transition feel continuous rather than like two separate animations.
     */
    @JvmField
    val EASING_ACCELERATE = Easing(0.3f, 0f, 1f, 1f)

    /**
     * Emphasised. A pronounced ease-out with a slight anticipation.
     *
     * For motion that should draw the eye. Used sparingly; everywhere at once it becomes
     * noise.
     */
    @JvmField
    val EASING_EMPHASISED = Easing(0.05f, 0.7f, 0.1f, 1f)

    // --- Springs -----------------------------------------------------------

    /** Settles quickly with no overshoot. Good default for gesture release. */
    @JvmField
    val SPRING_SNAPPY = Spring(stiffness = 800f, dampingRatio = 1f)

    /** Slight overshoot. Gives weight to sheets and cards. */
    @JvmField
    val SPRING_GENTLE = Spring(stiffness = 400f, dampingRatio = 0.85f)

    /** Visible bounce. For playful, deliberately noticeable motion only. */
    @JvmField
    val SPRING_BOUNCY = Spring(stiffness = 500f, dampingRatio = 0.6f)

    // --- Semantic aliases --------------------------------------------------

    /** Pressed or focused state changing on a control. */
    const val DURATION_STATE_CHANGE: Int = DURATION_FAST

    /** A dialog, menu or sheet appearing. */
    const val DURATION_ENTER: Int = DURATION_MEDIUM

    /**
     * A dialog, menu or sheet disappearing.
     *
     * Shorter than entering on purpose. The user has already decided to dismiss it, so waiting
     * for a symmetric exit feels like the system is lagging behind them.
     */
    const val DURATION_EXIT: Int = DURATION_SHORT

    /** Moving between two full screens. */
    const val DURATION_SCREEN_TRANSITION: Int = DURATION_LONG
}
