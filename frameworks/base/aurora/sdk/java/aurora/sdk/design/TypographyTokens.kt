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
 * One entry in the type scale.
 *
 * Size and line height are in scale-independent pixels (sp), so they follow the user's font
 * size preference. Never convert these to dp: doing so silently opts the whole interface out
 * of an accessibility setting.
 *
 * @param sizeSp glyph size in sp
 * @param lineHeightSp distance between baselines in sp
 * @param weight CSS-style numeric weight, 100..900; 400 is regular, 700 is bold
 * @param letterSpacingEm tracking as a fraction of the font size, so it scales with it
 */
data class TextStyle(
    val sizeSp: Int,
    val lineHeightSp: Int,
    val weight: Int,
    val letterSpacingEm: Float,
)

/**
 * The type scale.
 *
 * Five roles, each in three sizes. Roles carry meaning, sizes carry emphasis within a role;
 * picking a style is therefore two decisions, not one out of fifteen.
 *
 * ## Why line height is stored, not derived
 *
 * Line height is not a fixed multiple of size across the scale. Large display text needs a
 * proportionally tighter leading or it looks disconnected, while small body text needs a
 * looser one to stay readable. Deriving it from a single ratio gets one end of the scale
 * wrong, so each entry states its own.
 *
 * ## Weights
 *
 * Only 400, 500 and 700 are used. Every extra weight is another font file in the system image
 * and another way for two screens to disagree, and the three here already cover regular,
 * medium emphasis and strong emphasis.
 */
object TypographyTokens {

    // --- Weights -----------------------------------------------------------

    /** Regular. Body copy and anything long enough to read rather than scan. */
    const val WEIGHT_REGULAR: Int = 400

    /** Medium. Labels, titles and controls. Enough emphasis without shouting. */
    const val WEIGHT_MEDIUM: Int = 500

    /** Bold. Reserve for genuine emphasis; overuse flattens the hierarchy. */
    const val WEIGHT_BOLD: Int = 700

    // --- Display: the largest text, used once per screen at most ------------

    @JvmField
    val DISPLAY_LARGE = TextStyle(57, 64, WEIGHT_REGULAR, -0.025f)

    @JvmField
    val DISPLAY_MEDIUM = TextStyle(45, 52, WEIGHT_REGULAR, 0f)

    @JvmField
    val DISPLAY_SMALL = TextStyle(36, 44, WEIGHT_REGULAR, 0f)

    // --- Headline: section headings ----------------------------------------

    @JvmField
    val HEADLINE_LARGE = TextStyle(32, 40, WEIGHT_REGULAR, 0f)

    @JvmField
    val HEADLINE_MEDIUM = TextStyle(28, 36, WEIGHT_REGULAR, 0f)

    @JvmField
    val HEADLINE_SMALL = TextStyle(24, 32, WEIGHT_REGULAR, 0f)

    // --- Title: component and dialog headings ------------------------------

    @JvmField
    val TITLE_LARGE = TextStyle(22, 28, WEIGHT_MEDIUM, 0f)

    @JvmField
    val TITLE_MEDIUM = TextStyle(16, 24, WEIGHT_MEDIUM, 0.009f)

    @JvmField
    val TITLE_SMALL = TextStyle(14, 20, WEIGHT_MEDIUM, 0.007f)

    // --- Body: running text ------------------------------------------------

    @JvmField
    val BODY_LARGE = TextStyle(16, 24, WEIGHT_REGULAR, 0.031f)

    @JvmField
    val BODY_MEDIUM = TextStyle(14, 20, WEIGHT_REGULAR, 0.017f)

    @JvmField
    val BODY_SMALL = TextStyle(12, 16, WEIGHT_REGULAR, 0.033f)

    // --- Label: text inside controls ---------------------------------------

    @JvmField
    val LABEL_LARGE = TextStyle(14, 20, WEIGHT_MEDIUM, 0.007f)

    @JvmField
    val LABEL_MEDIUM = TextStyle(12, 16, WEIGHT_MEDIUM, 0.041f)

    @JvmField
    val LABEL_SMALL = TextStyle(11, 16, WEIGHT_MEDIUM, 0.045f)

    // --- Semantic aliases --------------------------------------------------

    /** Default text for paragraphs and list rows. */
    @JvmField
    val BODY_DEFAULT = BODY_MEDIUM

    /** Text on a button. */
    @JvmField
    val BUTTON = LABEL_LARGE

    /** Title bar of a screen. */
    @JvmField
    val SCREEN_TITLE = TITLE_LARGE

    /** Title of a dialog. */
    @JvmField
    val DIALOG_TITLE = HEADLINE_SMALL

    /** Supporting text under a field or setting. */
    @JvmField
    val CAPTION = BODY_SMALL
}
