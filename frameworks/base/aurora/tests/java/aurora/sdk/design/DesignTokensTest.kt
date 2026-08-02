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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Invariants of the token set.
 *
 * These do not test that the values are tasteful — no test can. They test the properties a
 * token set has to hold to be usable at all: scales that go the right way, colours that are
 * opaque when they claim to be, foreground and background that actually differ. A mistyped
 * digit in a token is silent otherwise, and shows up as a layout that looks slightly wrong on
 * one screen months later.
 */
class DesignTokensTest {

    @Test
    fun spacingScaleIsStrictlyIncreasing() {
        val scale = listOf(
            SpacingTokens.NONE,
            SpacingTokens.XXS,
            SpacingTokens.XS,
            SpacingTokens.SM,
            SpacingTokens.MD,
            SpacingTokens.LG,
            SpacingTokens.XL,
            SpacingTokens.XXL,
            SpacingTokens.XXXL,
            SpacingTokens.HUGE,
        )
        scale.zipWithNext { smaller, larger ->
            assertTrue("spacing scale must increase: $smaller then $larger", larger > smaller)
        }
    }

    @Test
    fun spacingStepsSitOnTheGrid() {
        val onGrid = listOf(
            SpacingTokens.XS,
            SpacingTokens.SM,
            SpacingTokens.MD,
            SpacingTokens.LG,
            SpacingTokens.XL,
            SpacingTokens.XXL,
            SpacingTokens.XXXL,
            SpacingTokens.HUGE,
        )
        onGrid.forEach {
            assertEquals("$it must be a multiple of the ${SpacingTokens.GRID}dp grid",
                0, it % SpacingTokens.GRID)
        }
    }

    @Test
    fun touchTargetMeetsTheAccessibilityFloor() {
        // 48dp is the Android accessibility minimum, not a style preference.
        assertTrue(SpacingTokens.MIN_TOUCH_TARGET >= 48)
    }

    @Test
    fun radiusScaleIsStrictlyIncreasing() {
        val scale = listOf(
            RadiusTokens.NONE,
            RadiusTokens.XS,
            RadiusTokens.SM,
            RadiusTokens.MD,
            RadiusTokens.LG,
            RadiusTokens.XL,
        )
        scale.zipWithNext { smaller, larger ->
            assertTrue("radius scale must increase: $smaller then $larger", larger > smaller)
        }
        assertTrue("FULL is a sentinel above every real radius", RadiusTokens.FULL > RadiusTokens.XL)
    }

    @Test
    fun nestedRadiusKeepsCornersConcentric() {
        assertEquals(12, RadiusTokens.nested(outer = 16, padding = 4))
        assertEquals(0, RadiusTokens.nested(outer = 8, padding = 8))
    }

    @Test
    fun nestedRadiusNeverGoesNegative() {
        assertEquals(RadiusTokens.NONE, RadiusTokens.nested(outer = 4, padding = 100))
    }

    @Test
    fun nestedRadiusPassesTheFullSentinelThrough() {
        // FULL means "half the shorter side", so subtracting padding from it is meaningless.
        assertEquals(RadiusTokens.FULL, RadiusTokens.nested(RadiusTokens.FULL, padding = 8))
    }

    @Test
    fun elevationLadderIsStrictlyIncreasing() {
        val ladder = listOf(
            ElevationTokens.LEVEL_0,
            ElevationTokens.LEVEL_1,
            ElevationTokens.LEVEL_2,
            ElevationTokens.LEVEL_3,
            ElevationTokens.LEVEL_4,
            ElevationTokens.LEVEL_5,
        )
        ladder.zipWithNext { lower, higher ->
            assertTrue("elevation must increase: $lower then $higher", higher > lower)
        }
    }

    @Test
    fun durationsAreOrderedAndNonNegative() {
        val durations = listOf(
            MotionTokens.DURATION_INSTANT,
            MotionTokens.DURATION_FAST,
            MotionTokens.DURATION_SHORT,
            MotionTokens.DURATION_MEDIUM,
            MotionTokens.DURATION_LONG,
            MotionTokens.DURATION_EXTRA_LONG,
        )
        assertTrue(durations.all { it >= 0 })
        durations.zipWithNext { shorter, longer ->
            assertTrue("durations must increase: $shorter then $longer", longer > shorter)
        }
    }

    @Test
    fun exitIsFasterThanEnter() {
        // The user has already decided to dismiss; a symmetric exit feels like lag.
        assertTrue(MotionTokens.DURATION_EXIT < MotionTokens.DURATION_ENTER)
    }

    @Test
    fun easingControlPointsStayInRangeOnX() {
        // A cubic Bezier easing curve is only a function of time if its control points'
        // x values are within [0,1]. y may overshoot, which is what gives anticipation.
        val curves = listOf(
            MotionTokens.EASING_LINEAR,
            MotionTokens.EASING_STANDARD,
            MotionTokens.EASING_DECELERATE,
            MotionTokens.EASING_ACCELERATE,
            MotionTokens.EASING_EMPHASISED,
        )
        curves.forEach {
            assertTrue("x1 out of range in $it", it.x1 in 0f..1f)
            assertTrue("x2 out of range in $it", it.x2 in 0f..1f)
        }
    }

    @Test
    fun springsArePhysicallySensible() {
        val springs = listOf(
            MotionTokens.SPRING_SNAPPY,
            MotionTokens.SPRING_GENTLE,
            MotionTokens.SPRING_BOUNCY,
        )
        springs.forEach {
            assertTrue("stiffness must be positive in $it", it.stiffness > 0f)
            assertTrue("damping must be positive in $it", it.dampingRatio > 0f)
            assertTrue("damping above 1 would never reach the target in $it", it.dampingRatio <= 1f)
        }
        assertTrue("bouncy must overshoot more than snappy",
            MotionTokens.SPRING_BOUNCY.dampingRatio < MotionTokens.SPRING_SNAPPY.dampingRatio)
    }

    @Test
    fun typeScaleHasLineHeightAtLeastTheFontSize() {
        val styles = listOf(
            TypographyTokens.DISPLAY_LARGE,
            TypographyTokens.HEADLINE_LARGE,
            TypographyTokens.TITLE_LARGE,
            TypographyTokens.BODY_LARGE,
            TypographyTokens.BODY_MEDIUM,
            TypographyTokens.BODY_SMALL,
            TypographyTokens.LABEL_SMALL,
        )
        styles.forEach {
            assertTrue("line height below font size would clip glyphs: $it",
                it.lineHeightSp >= it.sizeSp)
            assertTrue("weight out of range: $it", it.weight in 100..900)
        }
    }

    @Test
    fun typeScaleIsOrderedWithinARole() {
        assertTrue(TypographyTokens.BODY_LARGE.sizeSp > TypographyTokens.BODY_MEDIUM.sizeSp)
        assertTrue(TypographyTokens.BODY_MEDIUM.sizeSp > TypographyTokens.BODY_SMALL.sizeSp)
        assertTrue(TypographyTokens.DISPLAY_LARGE.sizeSp > TypographyTokens.HEADLINE_LARGE.sizeSp)
    }

    @Test
    fun colourRolesAreOpaqueExceptTheScrim() {
        listOf(ColorTokens.LIGHT, ColorTokens.DARK).forEach { scheme ->
            val opaque = listOf(
                scheme.background, scheme.onBackground,
                scheme.surface, scheme.onSurface,
                scheme.surfaceVariant, scheme.onSurfaceVariant,
                scheme.primary, scheme.onPrimary,
                scheme.primaryContainer, scheme.onPrimaryContainer,
                scheme.outline, scheme.outlineVariant,
                scheme.error, scheme.onError,
                scheme.success, scheme.warning,
            )
            opaque.forEach {
                assertEquals("role colours must be fully opaque: ${java.lang.Long.toHexString(it)}",
                    255, ColorTokens.alphaOf(it))
            }
            // The scrim is the one role that must NOT be opaque; it exists to dim what is
            // behind it.
            assertTrue("scrim must be translucent", ColorTokens.alphaOf(scheme.scrim) < 255)
        }
    }

    @Test
    fun foregroundDiffersFromItsBackground() {
        listOf(ColorTokens.LIGHT, ColorTokens.DARK).forEach { scheme ->
            assertNotEquals(scheme.background, scheme.onBackground)
            assertNotEquals(scheme.surface, scheme.onSurface)
            assertNotEquals(scheme.primary, scheme.onPrimary)
            assertNotEquals(scheme.error, scheme.onError)
        }
    }

    @Test
    fun lightAndDarkAreDistinct() {
        assertNotEquals(ColorTokens.LIGHT.background, ColorTokens.DARK.background)
        assertNotEquals(ColorTokens.LIGHT.surface, ColorTokens.DARK.surface)
    }

    @Test
    fun darkBackgroundIsNotPureBlack() {
        // Pure black surfaces plus near-white text cause halation and are tiring to read.
        assertNotEquals(0xFF000000L, ColorTokens.DARK.background)
        assertNotEquals(0xFF000000L, ColorTokens.DARK.surface)
    }

    @Test
    fun channelHelpersUnpackCorrectly() {
        val color = 0xFF336699L
        assertEquals(0xFF, ColorTokens.alphaOf(color))
        assertEquals(0x33, ColorTokens.redOf(color))
        assertEquals(0x66, ColorTokens.greenOf(color))
        assertEquals(0x99, ColorTokens.blueOf(color))
    }

    @Test
    fun designTokensFacadeResolvesTheSameObjects() {
        assertEquals(SpacingTokens.LG, DesignTokens.spacing.LG)
        assertEquals(MotionTokens.DURATION_ENTER, DesignTokens.motion.DURATION_ENTER)
        assertEquals(ColorTokens.DARK, DesignTokens.colors(dark = true))
        assertEquals(ColorTokens.LIGHT, DesignTokens.colors(dark = false))
        assertTrue(DesignTokens.VERSION > 0)
    }
}
