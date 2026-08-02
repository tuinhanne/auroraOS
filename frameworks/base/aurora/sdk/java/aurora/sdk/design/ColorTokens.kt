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
 * A complete set of colour roles for one appearance, light or dark.
 *
 * Roles come in pairs: every surface has a matching `on` colour that is guaranteed legible on
 * it. Always take the foreground from the same scheme as the background rather than choosing
 * it separately, which is how contrast bugs get in.
 *
 * Values are packed ARGB in a [Long], `0xAARRGGBB`. A [Long] rather than an [Int] because
 * `0xFF000000` does not fit in a signed 32-bit Int without becoming negative, and comparing
 * negative colour constants in tests is a needless trap.
 */
data class ColorScheme(
    /** Furthest-back surface of a screen. */
    val background: Long,
    /** Text and icons on [background]. */
    val onBackground: Long,
    /** Cards, sheets and other raised containers. */
    val surface: Long,
    /** Text and icons on [surface]. */
    val onSurface: Long,
    /** A surface that needs to read as distinct from [surface] without elevation. */
    val surfaceVariant: Long,
    /** Secondary text and icons on [surfaceVariant]. */
    val onSurfaceVariant: Long,
    /** The brand colour; the main call to action. */
    val primary: Long,
    /** Text and icons on [primary]. */
    val onPrimary: Long,
    /** A muted fill derived from [primary], for selected or highlighted states. */
    val primaryContainer: Long,
    /** Text and icons on [primaryContainer]. */
    val onPrimaryContainer: Long,
    /** Borders and dividers. */
    val outline: Long,
    /** A weaker outline for decorative separation. */
    val outlineVariant: Long,
    /** Destructive or failed states. */
    val error: Long,
    /** Text and icons on [error]. */
    val onError: Long,
    /** Confirmation of a completed action. */
    val success: Long,
    /** Caution that does not block the user. */
    val warning: Long,
    /** Scrim behind a modal surface. Partially transparent by design. */
    val scrim: Long,
)

/**
 * Colour tokens.
 *
 * ## Two layers, on purpose
 *
 * [Palette] holds raw hues, named by what they *are*. [LIGHT] and [DARK] map those to roles,
 * named by what they are *for*. Interface code must only ever read roles.
 *
 * The separation is what makes a theme change possible: retint the palette and every role
 * follows. Code that reads `Palette.NEUTRAL_10` directly defeats that and will be wrong the
 * first time the brand colour changes.
 *
 * ## Why dark is not light inverted
 *
 * Inverting a light scheme produces harsh, over-contrasty dark mode. Dark surfaces here are
 * lifted off pure black, and the foregrounds are pulled off pure white, because maximum
 * contrast on a dark background causes visible halation and is tiring to read. Pure black is
 * reserved for the scrim.
 */
object ColorTokens {

    /**
     * Raw palette. Named by appearance, not by use.
     *
     * Do not reference these from interface code; read a role from [LIGHT] or [DARK] instead.
     * The numeric suffix is lightness: 0 is black, 100 is white.
     */
    object Palette {
        const val NEUTRAL_0: Long = 0xFF000000
        const val NEUTRAL_6: Long = 0xFF0F1113
        const val NEUTRAL_10: Long = 0xFF1A1C1E
        const val NEUTRAL_17: Long = 0xFF2B2E31
        const val NEUTRAL_22: Long = 0xFF373A3D
        const val NEUTRAL_40: Long = 0xFF5F6368
        const val NEUTRAL_60: Long = 0xFF919498
        const val NEUTRAL_80: Long = 0xFFC7C9CC
        const val NEUTRAL_90: Long = 0xFFE2E4E7
        const val NEUTRAL_95: Long = 0xFFF1F2F4
        const val NEUTRAL_99: Long = 0xFFFCFCFD
        const val NEUTRAL_100: Long = 0xFFFFFFFF

        /** Aurora brand. A cool teal-blue, legible against both light and dark neutrals. */
        const val BRAND_30: Long = 0xFF00504D
        const val BRAND_40: Long = 0xFF006A66
        const val BRAND_50: Long = 0xFF008782
        const val BRAND_80: Long = 0xFF6FF7EE
        const val BRAND_90: Long = 0xFF9DFFF7

        const val RED_40: Long = 0xFFBA1A1A
        const val RED_80: Long = 0xFFFFB4AB
        const val GREEN_40: Long = 0xFF246C2F
        const val GREEN_80: Long = 0xFF8FD996
        const val AMBER_40: Long = 0xFF7D5800
        const val AMBER_80: Long = 0xFFF5BD3F

        /** Black at 40% alpha. Used only as a modal scrim. */
        const val SCRIM_40: Long = 0x66000000
    }

    /** Roles for light appearance. */
    @JvmField
    val LIGHT = ColorScheme(
        background = Palette.NEUTRAL_99,
        onBackground = Palette.NEUTRAL_10,
        surface = Palette.NEUTRAL_100,
        onSurface = Palette.NEUTRAL_10,
        surfaceVariant = Palette.NEUTRAL_95,
        onSurfaceVariant = Palette.NEUTRAL_40,
        primary = Palette.BRAND_40,
        onPrimary = Palette.NEUTRAL_100,
        primaryContainer = Palette.BRAND_90,
        onPrimaryContainer = Palette.BRAND_30,
        outline = Palette.NEUTRAL_60,
        outlineVariant = Palette.NEUTRAL_90,
        error = Palette.RED_40,
        onError = Palette.NEUTRAL_100,
        success = Palette.GREEN_40,
        warning = Palette.AMBER_40,
        scrim = Palette.SCRIM_40,
    )

    /** Roles for dark appearance. */
    @JvmField
    val DARK = ColorScheme(
        background = Palette.NEUTRAL_6,
        onBackground = Palette.NEUTRAL_90,
        surface = Palette.NEUTRAL_10,
        onSurface = Palette.NEUTRAL_90,
        surfaceVariant = Palette.NEUTRAL_17,
        onSurfaceVariant = Palette.NEUTRAL_80,
        primary = Palette.BRAND_80,
        onPrimary = Palette.BRAND_30,
        primaryContainer = Palette.BRAND_30,
        onPrimaryContainer = Palette.BRAND_90,
        outline = Palette.NEUTRAL_60,
        outlineVariant = Palette.NEUTRAL_22,
        error = Palette.RED_80,
        onError = Palette.NEUTRAL_0,
        success = Palette.GREEN_80,
        warning = Palette.AMBER_80,
        scrim = Palette.SCRIM_40,
    )

    /**
     * Returns the scheme for the requested appearance.
     *
     * @param dark true for dark appearance
     */
    @JvmStatic
    fun scheme(dark: Boolean): ColorScheme = if (dark) DARK else LIGHT

    /** Alpha channel of a packed colour, 0..255. */
    @JvmStatic
    fun alphaOf(color: Long): Int = ((color shr 24) and 0xFF).toInt()

    /** Red channel of a packed colour, 0..255. */
    @JvmStatic
    fun redOf(color: Long): Int = ((color shr 16) and 0xFF).toInt()

    /** Green channel of a packed colour, 0..255. */
    @JvmStatic
    fun greenOf(color: Long): Int = ((color shr 8) and 0xFF).toInt()

    /** Blue channel of a packed colour, 0..255. */
    @JvmStatic
    fun blueOf(color: Long): Int = (color and 0xFF).toInt()
}
