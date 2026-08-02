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
 * Spacing scale, in density-independent pixels.
 *
 * ## Why these are plain [Int] and not `Dp`
 *
 * `Dp` belongs to Compose, and `aurora.sdk` compiles against `core_current`, which has no
 * Android or Compose on the classpath at all. That is not an accident to work around: tokens
 * are *data*, and keeping them free of any UI framework type means one token set feeds
 * Compose, the View system and XML resources alike. Conversion belongs in a thin adapter in
 * `aurora.platform`, not here.
 *
 * The unit is dp everywhere in this file. It is never px.
 *
 * ## The scale
 *
 * Everything is a multiple of [GRID] (4dp). A 4dp grid is small enough to express tight
 * component padding and large enough that layouts stay visually aligned without designers
 * inventing one-off values.
 *
 * The steps grow roughly geometrically rather than linearly. A linear scale (4, 8, 12, 16,
 * 20...) gives too many near-identical choices at the large end, and in practice people pick
 * arbitrarily between them, which is exactly the inconsistency tokens exist to prevent.
 *
 * Prefer the semantic aliases at the bottom over the raw steps. `COMPONENT_PADDING` survives
 * a decision to change component padding from 16 to 12; a literal `MD` does not.
 */
object SpacingTokens {

    /** Base grid. Every value in this file is a multiple of this. */
    const val GRID: Int = 4

    // --- Raw scale ---------------------------------------------------------

    /** 0dp. Explicit "no space", so callers never write a bare literal. */
    const val NONE: Int = 0

    /** 2dp. Half-grid. Hairline separation only; avoid for layout. */
    const val XXS: Int = 2

    /** 4dp. One grid unit. */
    const val XS: Int = 4

    /** 8dp. */
    const val SM: Int = 8

    /** 12dp. */
    const val MD: Int = 12

    /** 16dp. The most common padding in the system. */
    const val LG: Int = 16

    /** 24dp. */
    const val XL: Int = 24

    /** 32dp. */
    const val XXL: Int = 32

    /** 48dp. */
    const val XXXL: Int = 48

    /** 64dp. Section-level separation. */
    const val HUGE: Int = 64

    // --- Semantic aliases --------------------------------------------------
    // Use these. They express intent, so a change of taste is a one-line edit here
    // instead of a search across the tree.

    /** Padding inside a component, such as a button or list row. */
    const val COMPONENT_PADDING: Int = LG

    /** Gap between two related components in the same group. */
    const val COMPONENT_GAP: Int = SM

    /** Horizontal inset from the screen edge for ordinary content. */
    const val SCREEN_MARGIN: Int = LG

    /** Vertical gap between distinct sections of a screen. */
    const val SECTION_GAP: Int = XXL

    /** Inset for content inside a dialog or sheet. */
    const val DIALOG_PADDING: Int = XL

    /** Space reserved around a touch target so neighbours are not hit by mistake. */
    const val TOUCH_SLOP_PADDING: Int = SM

    /**
     * Minimum edge length of a touch target, in dp.
     *
     * 48dp is the accessibility floor on Android and is not a style choice. Do not lower it.
     */
    const val MIN_TOUCH_TARGET: Int = 48
}
