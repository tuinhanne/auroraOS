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
 * Single entry point to the Aurora design language.
 *
 * ```kotlin
 * val pad = DesignTokens.spacing.COMPONENT_PADDING
 * val ms  = DesignTokens.motion.DURATION_ENTER
 * val bg  = DesignTokens.colors(dark = true).background
 * ```
 *
 * ## The rule this exists to enforce
 *
 * Interface code must not contain raw design values. No `16`, no `200`, no `0xFF000000`. Every
 * such number is a decision made once and then copied, and the copies drift. Reading it from a
 * token means the decision lives in one place and changing it is one edit.
 *
 * This is a convention, not something the compiler can catch: nothing stops a caller writing
 * `16`. What the compiler *does* guarantee is the other half of the design — that none of
 * these tokens can reach into Android or Compose, because `aurora.sdk` compiles against
 * `core_current`. See `frameworks/base/aurora/README.md`.
 *
 * ## Units
 *
 * | Kind | Unit | Type |
 * |---|---|---|
 * | Spacing, radius, elevation | dp | `Int` |
 * | Type size, line height | sp | `Int` |
 * | Duration | milliseconds | `Int` |
 * | Colour | packed ARGB `0xAARRGGBB` | `Long` |
 * | Letter spacing | em | `Float` |
 *
 * Converting these into platform types is the job of an adapter in `aurora.platform`. Keeping
 * that conversion out of this module is what lets the same tokens serve Compose, the View
 * system and XML resources without any of them being a dependency here.
 */
object DesignTokens {

    /**
     * Version of the design language.
     *
     * Separate from [aurora.sdk.AuroraVersion] on purpose. Visual decisions change on a
     * different rhythm from API surface, and a retint should not look like a platform release.
     */
    const val VERSION: Int = 1

    /** Spacing scale, in dp. */
    @JvmField
    val spacing = SpacingTokens

    /** Corner radius scale, in dp. */
    @JvmField
    val radius = RadiusTokens

    /** Elevation ladder, in dp. */
    @JvmField
    val elevation = ElevationTokens

    /** Durations, easing curves and springs. */
    @JvmField
    val motion = MotionTokens

    /** Type scale. */
    @JvmField
    val typography = TypographyTokens

    /**
     * Colour roles for the requested appearance.
     *
     * Always resolve through this rather than reading [ColorTokens.Palette] directly; the
     * palette holds raw hues, and interface code that binds to a hue instead of a role breaks
     * the moment the theme changes.
     *
     * @param dark true for dark appearance
     */
    @JvmStatic
    fun colors(dark: Boolean): ColorScheme = ColorTokens.scheme(dark)
}
