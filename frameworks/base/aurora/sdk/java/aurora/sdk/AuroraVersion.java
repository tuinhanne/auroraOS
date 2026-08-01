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

package aurora.sdk;

/**
 * Version information for the Aurora platform.
 *
 * <p>Two numbering schemes are kept deliberately separate:
 *
 * <ul>
 *   <li>The <em>release version</em> ({@link #MAJOR}.{@link #MINOR}.{@link #PATCH}) is what
 *       humans see. It may change for reasons that do not affect callers at all, such as a
 *       rebase onto a newer LineageOS tag.
 *   <li>The <em>API level</em> ({@link #API_LEVEL}) is what code should branch on. It only ever
 *       increments, and only when the surface exposed by {@code aurora.sdk} actually changes.
 * </ul>
 *
 * <p>Feature detection should therefore use {@link #isApiAtLeast(int)} rather than comparing
 * release numbers, which keeps callers working across cosmetic version bumps.
 *
 * <p>This class holds no state and cannot be instantiated.
 */
public final class AuroraVersion {

    /** Major release number. Incremented for changes that break existing callers. */
    public static final int MAJOR = 1;

    /** Minor release number. Incremented for backwards-compatible additions. */
    public static final int MINOR = 0;

    /** Patch release number. Incremented for fixes that add no surface. */
    public static final int PATCH = 0;

    /**
     * API level of the {@code aurora.sdk} surface.
     *
     * <p>Level 1 is the bootstrap surface introduced in Sprint 01: version reporting, runtime
     * lifecycle, environment abstraction and service registry. Nothing is wired into the
     * Android platform at this level.
     */
    public static final int API_LEVEL = 1;

    /** Human-readable name for this release line. */
    public static final String CODENAME = "Bootstrap";

    private static final String VERSION_STRING = MAJOR + "." + MINOR + "." + PATCH;

    private AuroraVersion() {
        throw new AssertionError("no instances");
    }

    /** Returns the release version as {@code "MAJOR.MINOR.PATCH"}, for example {@code "1.0.0"}. */
    public static String versionString() {
        return VERSION_STRING;
    }

    /**
     * Returns a display string such as {@code "AuroraOS 1.0.0 (Bootstrap)"}.
     *
     * <p>Intended for logs and about screens. Do not parse this; use {@link #versionString()}
     * or the individual constants instead.
     */
    public static String displayName() {
        return "AuroraOS " + VERSION_STRING + " (" + CODENAME + ")";
    }

    /** Returns the API level of the {@code aurora.sdk} surface. See {@link #API_LEVEL}. */
    public static int apiLevel() {
        return API_LEVEL;
    }

    /**
     * Returns whether the running platform is at least the given release version.
     *
     * <p>Prefer {@link #isApiAtLeast(int)} for feature detection. This method exists for
     * reporting and for the rare case where behaviour must key off a specific release.
     *
     * @param major major version to compare against
     * @param minor minor version to compare against
     */
    public static boolean isAtLeast(int major, int minor) {
        if (MAJOR != major) {
            return MAJOR > major;
        }
        return MINOR >= minor;
    }

    /**
     * Returns whether the {@code aurora.sdk} surface is at least the given API level.
     *
     * <p>This is the check to use when guarding calls to newer Aurora APIs.
     *
     * @param apiLevel API level to compare against
     */
    public static boolean isApiAtLeast(int apiLevel) {
        return API_LEVEL >= apiLevel;
    }
}
