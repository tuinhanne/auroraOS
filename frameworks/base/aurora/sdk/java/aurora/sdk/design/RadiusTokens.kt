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
 * Corner radius scale, in density-independent pixels.
 *
 * See [SpacingTokens] for why these are plain [Int] rather than `Dp`.
 *
 * ## Nesting rule
 *
 * When one rounded surface sits inside another, the inner radius should be the outer radius
 * minus the padding between them, or the corners will not look concentric. [nested] does that
 * arithmetic so the rule is applied rather than remembered.
 */
object RadiusTokens {

    /** 0dp. Square corners. */
    const val NONE: Int = 0

    /** 4dp. Chips, tags, small inline surfaces. */
    const val XS: Int = 4

    /** 8dp. Buttons, text fields. */
    const val SM: Int = 8

    /** 12dp. Cards and list surfaces. */
    const val MD: Int = 12

    /** 16dp. Dialogs and menus. */
    const val LG: Int = 16

    /** 28dp. Bottom sheets and large containers. */
    const val XL: Int = 28

    /**
     * Fully rounded, i.e. a pill or a circle.
     *
     * Deliberately a large sentinel rather than a real measurement: the renderer clamps it to
     * half the shorter side, which is what "fully rounded" means for any size. Do not do
     * arithmetic on this value.
     */
    const val FULL: Int = 9999

    // --- Semantic aliases --------------------------------------------------

    /** Default radius for a pressable control. */
    const val BUTTON: Int = SM

    /** Default radius for a card surface. */
    const val CARD: Int = MD

    /** Default radius for a modal dialog. */
    const val DIALOG: Int = LG

    /** Default radius for a bottom sheet's top corners. */
    const val SHEET: Int = XL

    /** Avatars and other circular imagery. */
    const val AVATAR: Int = FULL

    /**
     * Radius for a surface nested inside another rounded surface.
     *
     * Concentric corners require `inner = outer - padding`. Without this the inner corner
     * looks too round and the gap between the two curves visibly varies.
     *
     * Returns [NONE] rather than a negative radius when the padding is large enough to
     * flatten the inner corner, and passes [FULL] through untouched because it is a sentinel,
     * not a measurement.
     *
     * @param outer radius of the enclosing surface, in dp
     * @param padding gap between the two surfaces, in dp
     */
    fun nested(outer: Int, padding: Int): Int {
        if (outer == FULL) return FULL
        val inner = outer - padding
        return if (inner > NONE) inner else NONE
    }
}
