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

package aurora.runtime

import aurora.sdk.design.ColorScheme
import aurora.sdk.service.AuroraService
import aurora.sdk.service.ThemeMode
import aurora.sdk.service.ThemeService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Tests the seam between the runtime and its service implementations.
 *
 * That this file exists at all, on a host JVM with no Android in sight, is the point of the
 * [ServiceProvider] indirection: a fake provider is enough to exercise the whole path.
 */
class ServiceProviderTest {

    /** A theme service that needs nothing from the platform. */
    private class FakeThemeService : ThemeService {
        override val serviceName = "fake-theme"
        override var mode: ThemeMode = ThemeMode.LIGHT
            private set
        override val isDark get() = mode == ThemeMode.DARK
        override val colors: ColorScheme
            get() = aurora.sdk.design.ColorTokens.scheme(isDark)

        override fun setMode(mode: ThemeMode) {
            this.mode = mode
        }

        override fun addOnThemeChangedListener(listener: (ColorScheme) -> Unit) = Unit
        override fun removeOnThemeChangedListener(listener: (ColorScheme) -> Unit) = Unit
    }

    /** A provider that knows about exactly one service. */
    private class SingleServiceProvider(private val service: AuroraService) : ServiceProvider {
        @Suppress("UNCHECKED_CAST")
        override fun <T : AuroraService> find(type: Class<T>): T? =
            if (type.isInstance(service)) service as T else null
    }

    private lateinit var runtime: AuroraRuntime

    @Before
    fun setUp() {
        AuroraRuntime.shutdown()
        runtime = AuroraRuntime.initialize(AuroraContext.builder("aurora.test").build())
    }

    @After
    fun tearDown() {
        AuroraRuntime.shutdown()
    }

    @Test
    fun noProviderInstalledByDefault() {
        assertFalse(runtime.hasServiceProvider())
    }

    @Test
    fun findServiceReturnsNullWithoutAProvider() {
        assertNull(runtime.findService(ThemeService::class.java))
    }

    @Test
    fun requireServiceThrowsWithoutAProvider() {
        try {
            runtime.requireService(ThemeService::class.java)
            fail("requireService should throw when no provider is installed")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message, expected.message!!.contains("ThemeService"))
        }
    }

    @Test
    fun typedAccessorThrowsWithoutAProvider() {
        // Sprint 04 ships contracts only, so this is the expected state of every accessor.
        try {
            runtime.theme()
            fail("theme() should throw until the platform registers an implementation")
        } catch (expected: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun installedProviderIsUsed() {
        val fake = FakeThemeService()
        runtime.setServiceProvider(SingleServiceProvider(fake))

        assertTrue(runtime.hasServiceProvider())
        assertSame(fake, runtime.findService(ThemeService::class.java))
        assertSame(fake, runtime.requireService(ThemeService::class.java))
        assertSame(fake, runtime.theme())
    }

    @Test
    fun providerReturningNullStillFailsLoudlyForRequire() {
        val fake = FakeThemeService()
        runtime.setServiceProvider(SingleServiceProvider(fake))

        // The provider knows about ThemeService only.
        assertNull(runtime.findService(aurora.sdk.service.PowerService::class.java))
        try {
            runtime.power()
            fail("power() should throw when the provider has no implementation for it")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message, expected.message!!.contains("PowerService"))
        }
    }

    @Test
    fun providerCanBeDetached() {
        runtime.setServiceProvider(SingleServiceProvider(FakeThemeService()))
        runtime.setServiceProvider(null)

        assertFalse(runtime.hasServiceProvider())
        assertNull(runtime.findService(ThemeService::class.java))
    }

    @Test
    fun findServiceRejectsNullType() {
        try {
            // The type argument has to be written out: Kotlin cannot infer T from a bare
            // null, and findService is a generic Java method.
            runtime.findService<ThemeService>(null)
            fail("null type should be rejected")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun serviceContractIsUsableWithoutAndroid() {
        // The fake drives the real contract end to end, including the design tokens it
        // returns, with nothing from the platform involved.
        val fake = FakeThemeService()
        assertEquals(ThemeMode.LIGHT, fake.mode)
        assertFalse(fake.isDark)

        fake.setMode(ThemeMode.DARK)
        assertTrue(fake.isDark)
        assertEquals(aurora.sdk.design.ColorTokens.DARK, fake.colors)
        assertTrue(fake.isAvailable)
    }
}
