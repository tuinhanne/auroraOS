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
 * Elevation scale, in density-independent pixels.
 *
 * Elevation is a z-position, not a shadow. The shadow is what the renderer draws *because* of
 * the elevation, and it is the renderer's business how. Keeping only the z value here means
 * the same token works for a platform that draws shadows, one that tints surfaces by depth,
 * and one that does both.
 *
 * ## Use the ladder, not arbitrary values
 *
 * The levels exist so that "is this above or below that?" has one answer across the system.
 * Two surfaces at the same level should never overlap; if they do, one of them belongs at a
 * different level.
 */
object ElevationTokens {

    /** 0dp. Flush with the background. Most content lives here. */
    const val LEVEL_0: Int = 0

    /** 1dp. Barely lifted: a resting card, a divider-free list section. */
    const val LEVEL_1: Int = 1

    /** 3dp. A raised control, or a card that has been picked up. */
    const val LEVEL_2: Int = 3

    /** 6dp. Menus, dropdowns, and anything that must clear the content beneath it. */
    const val LEVEL_3: Int = 6

    /** 8dp. Navigation surfaces that float above the whole screen. */
    const val LEVEL_4: Int = 8

    /** 12dp. Modal surfaces above everything else. */
    const val LEVEL_5: Int = 12

    // --- Semantic aliases --------------------------------------------------

    /** Ordinary page background. */
    const val SURFACE: Int = LEVEL_0

    /** A card at rest. */
    const val CARD: Int = LEVEL_1

    /** A card or control while the finger is on it. */
    const val CARD_PRESSED: Int = LEVEL_2

    /** An open menu or dropdown. */
    const val MENU: Int = LEVEL_3

    /** App bar or bottom navigation. */
    const val NAVIGATION: Int = LEVEL_4

    /** Modal dialog or bottom sheet. */
    const val MODAL: Int = LEVEL_5

    /**
     * Elevation of an element being dragged.
     *
     * Dragging deliberately jumps to the top of the ladder: while the finger holds it, the
     * element is above everything, and anything less makes it look like it is sliding
     * underneath the interface it is meant to be moving across.
     */
    const val DRAGGED: Int = LEVEL_5
}
