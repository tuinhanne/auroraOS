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

package aurora.sdk.service

import aurora.sdk.design.ColorScheme

/** How the system decides between light and dark. */
enum class ThemeMode {
    /** Always light, regardless of anything else. */
    LIGHT,

    /** Always dark. */
    DARK,

    /** Follow the platform's own light/dark setting. */
    SYSTEM,
}

/**
 * Resolves the design system's colour roles for the current appearance.
 *
 * ## Why this exists rather than reading [aurora.sdk.design.ColorTokens] directly
 *
 * The tokens know what light and dark look like. They do not know which one is in force right
 * now, and they must not: that depends on a user setting, the time of day, and a battery
 * saver, none of which belong in a data-only module.
 *
 * Read [colors] every time you need it. Caching it means missing the change when the user
 * flips the setting, which shows up as one screen in the wrong theme.
 */
interface ThemeService : AuroraService {

    /** The configured mode. */
    val mode: ThemeMode

    /** Whether dark appearance is in force right now, after resolving [ThemeMode.SYSTEM]. */
    val isDark: Boolean

    /** Colour roles for the current appearance. */
    val colors: ColorScheme

    /**
     * Changes the mode.
     *
     * Takes effect immediately and notifies every listener registered through
     * [addOnThemeChangedListener].
     */
    fun setMode(mode: ThemeMode)

    /**
     * Registers a listener for appearance changes.
     *
     * Fires both when [mode] changes and when the platform's own setting changes while the
     * mode is [ThemeMode.SYSTEM].
     */
    fun addOnThemeChangedListener(listener: (scheme: ColorScheme) -> Unit)

    /** Removes a previously registered listener. */
    fun removeOnThemeChangedListener(listener: (scheme: ColorScheme) -> Unit)
}
