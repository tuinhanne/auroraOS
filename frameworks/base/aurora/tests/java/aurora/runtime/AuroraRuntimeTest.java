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

package aurora.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import aurora.sdk.AuroraVersion;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link AuroraRuntime}.
 *
 * <p>The runtime is a process-wide singleton, so every test starts and ends from a clean state.
 * Without that these tests would pass or fail depending on the order JUnit happened to run them
 * in.
 */
public class AuroraRuntimeTest {

    private AuroraContext mContext;

    @Before
    public void setUp() {
        AuroraRuntime.shutdown();
        mContext = AuroraContext.builder("aurora.test").build();
    }

    @After
    public void tearDown() {
        AuroraRuntime.shutdown();
    }

    @Test
    public void isInitialized_falseBeforeInitialize() {
        assertFalse(AuroraRuntime.isInitialized());
    }

    @Test
    public void getInstance_beforeInitialize_throws() {
        try {
            AuroraRuntime.getInstance();
            fail("getInstance() before initialize() should throw");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("not initialized"));
        }
    }

    @Test
    public void initialize_returnsRuntimeAndMarksInitialized() {
        AuroraRuntime runtime = AuroraRuntime.initialize(mContext);

        assertNotNull(runtime);
        assertTrue(AuroraRuntime.isInitialized());
        assertFalse(runtime.isShutdown());
    }

    @Test
    public void getInstance_returnsSameInstanceAsInitialize() {
        AuroraRuntime created = AuroraRuntime.initialize(mContext);

        assertSame(created, AuroraRuntime.getInstance());
        assertSame(AuroraRuntime.getInstance(), AuroraRuntime.getInstance());
    }

    @Test
    public void initialize_withNullContext_throws() {
        try {
            AuroraRuntime.initialize(null);
            fail("initialize(null) should throw");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        assertFalse("a failed initialize must leave the runtime uninitialized",
                AuroraRuntime.isInitialized());
    }

    @Test
    public void initialize_twice_throwsAndKeepsFirstRuntime() {
        AuroraRuntime first = AuroraRuntime.initialize(mContext);

        try {
            AuroraRuntime.initialize(AuroraContext.builder("aurora.other").build());
            fail("initialize() twice should throw");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("already"));
        }

        // The rejected second call must not have replaced the live runtime.
        assertSame(first, AuroraRuntime.getInstance());
        assertEquals("aurora.test", AuroraRuntime.getInstance().context().packageName());
    }

    @Test
    public void context_returnsTheContextPassedToInitialize() {
        AuroraRuntime runtime = AuroraRuntime.initialize(mContext);

        assertSame(mContext, runtime.context());
        assertEquals("aurora.test", runtime.context().packageName());
    }

    @Test
    public void shutdown_clearsRuntimeAndMarksStaleInstance() {
        AuroraRuntime runtime = AuroraRuntime.initialize(mContext);

        AuroraRuntime.shutdown();

        assertFalse(AuroraRuntime.isInitialized());
        assertTrue("a reference held across shutdown must report itself shut down",
                runtime.isShutdown());
    }

    @Test
    public void shutdown_whenNotInitialized_isNoOp() {
        // Teardown paths call this unconditionally, so it must not throw.
        AuroraRuntime.shutdown();
        AuroraRuntime.shutdown();

        assertFalse(AuroraRuntime.isInitialized());
    }

    @Test
    public void initialize_afterShutdown_succeeds() {
        AuroraRuntime first = AuroraRuntime.initialize(mContext);
        AuroraRuntime.shutdown();

        AuroraRuntime second = AuroraRuntime.initialize(
                AuroraContext.builder("aurora.second").build());

        assertFalse(first == second);
        assertEquals("aurora.second", second.context().packageName());
    }

    @Test
    public void versionAccessors_delegateToAuroraVersion() {
        AuroraRuntime runtime = AuroraRuntime.initialize(mContext);

        assertEquals(AuroraVersion.versionString(), runtime.versionString());
        assertEquals(AuroraVersion.apiLevel(), runtime.apiLevel());
    }

    @Test
    public void context_defaults() {
        AuroraContext context = AuroraContext.builder("aurora.defaults").build();

        assertEquals("aurora.defaults", context.packageName());
        assertFalse(context.isSystemContext());
        assertNull("host context is absent outside Android", context.hostContext());
    }

    @Test
    public void context_builderRejectsBlankPackageName() {
        try {
            AuroraContext.builder("   ");
            fail("blank package name should be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            AuroraContext.builder(null);
            fail("null package name should be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void context_carriesSystemFlagAndHostContext() {
        Object host = new Object();
        AuroraContext context = AuroraContext.builder("android")
                .setSystemContext(true)
                .setHostContext(host)
                .build();

        assertTrue(context.isSystemContext());
        assertSame(host, context.hostContext());
    }
}
