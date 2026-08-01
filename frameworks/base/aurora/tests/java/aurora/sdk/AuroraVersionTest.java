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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

/** Unit tests for {@link AuroraVersion}. */
public class AuroraVersionTest {

    @Test
    public void versionString_isMajorMinorPatch() {
        assertEquals(
                AuroraVersion.MAJOR + "." + AuroraVersion.MINOR + "." + AuroraVersion.PATCH,
                AuroraVersion.versionString());
    }

    @Test
    public void versionString_isStable() {
        // Callers may cache it; two calls must not produce different strings.
        assertEquals(AuroraVersion.versionString(), AuroraVersion.versionString());
    }

    @Test
    public void displayName_containsProductAndVersionAndCodename() {
        String name = AuroraVersion.displayName();
        assertTrue(name, name.contains("AuroraOS"));
        assertTrue(name, name.contains(AuroraVersion.versionString()));
        assertTrue(name, name.contains(AuroraVersion.CODENAME));
    }

    @Test
    public void apiLevel_matchesConstantAndIsPositive() {
        assertEquals(AuroraVersion.API_LEVEL, AuroraVersion.apiLevel());
        assertTrue("API level must be positive", AuroraVersion.apiLevel() > 0);
    }

    @Test
    public void isAtLeast_acceptsCurrentVersion() {
        assertTrue(AuroraVersion.isAtLeast(AuroraVersion.MAJOR, AuroraVersion.MINOR));
    }

    @Test
    public void isAtLeast_acceptsOlderVersions() {
        assertTrue(AuroraVersion.isAtLeast(AuroraVersion.MAJOR - 1, Integer.MAX_VALUE));
        if (AuroraVersion.MINOR > 0) {
            assertTrue(AuroraVersion.isAtLeast(AuroraVersion.MAJOR, AuroraVersion.MINOR - 1));
        }
    }

    @Test
    public void isAtLeast_rejectsNewerMinor() {
        assertFalse(AuroraVersion.isAtLeast(AuroraVersion.MAJOR, AuroraVersion.MINOR + 1));
    }

    @Test
    public void isAtLeast_rejectsNewerMajor() {
        // A newer major must lose even when its minor is lower: 1.9 is not at least 2.0.
        assertFalse(AuroraVersion.isAtLeast(AuroraVersion.MAJOR + 1, 0));
    }

    @Test
    public void isApiAtLeast_acceptsCurrentAndOlder() {
        assertTrue(AuroraVersion.isApiAtLeast(AuroraVersion.API_LEVEL));
        assertTrue(AuroraVersion.isApiAtLeast(AuroraVersion.API_LEVEL - 1));
    }

    @Test
    public void isApiAtLeast_rejectsNewer() {
        assertFalse(AuroraVersion.isApiAtLeast(AuroraVersion.API_LEVEL + 1));
    }

    @Test
    public void class_isFinalAndNotInstantiable() {
        assertTrue("AuroraVersion must be final",
                Modifier.isFinal(AuroraVersion.class.getModifiers()));

        Constructor<?>[] constructors = AuroraVersion.class.getDeclaredConstructors();
        assertEquals("expected exactly one (private) constructor", 1, constructors.length);
        assertTrue("constructor must be private",
                Modifier.isPrivate(constructors[0].getModifiers()));

        constructors[0].setAccessible(true);
        try {
            constructors[0].newInstance();
            fail("constructing AuroraVersion should fail");
        } catch (Exception expected) {
            // Reflection wraps the AssertionError thrown by the constructor.
        }
    }
}
