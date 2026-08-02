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

import aurora.sdk.AuroraVersion;
import aurora.sdk.service.AnimationService;
import aurora.sdk.service.AuroraService;
import aurora.sdk.service.GestureService;
import aurora.sdk.service.IslandService;
import aurora.sdk.service.NotificationService;
import aurora.sdk.service.PowerService;
import aurora.sdk.service.ThemeService;
import aurora.sdk.service.VolumeService;

/**
 * Per-process entry point to the Aurora platform.
 *
 * <p>Exactly one runtime exists per process. It is created by {@link #initialize(AuroraContext)},
 * retrieved by {@link #getInstance()} and torn down by {@link #shutdown()}.
 *
 * <p>Lifecycle rules, chosen so that mistakes fail loudly instead of silently doing the wrong
 * thing:
 *
 * <ul>
 *   <li>{@link #getInstance()} before {@link #initialize} throws rather than lazily creating a
 *       runtime with an environment nobody chose.
 *   <li>{@link #initialize} twice throws, because the second caller would otherwise believe it
 *       had installed its own context when it had not.
 *   <li>{@link #shutdown()} on an uninitialized runtime is a no-op, so teardown paths and test
 *       {@code @After} blocks can call it unconditionally.
 * </ul>
 *
 * <p>All methods are safe to call from any thread.
 *
 * <p>Sprint 01 deliberately does no platform work: nothing is registered with the Android
 * system server and no behaviour changes. This class exists so later sprints have a defined
 * place to hook into.
 */
public final class AuroraRuntime {

    private static final Object sLock = new Object();

    /** Guarded by {@link #sLock} for writes; volatile so reads need no lock. */
    private static volatile AuroraRuntime sInstance;

    private final AuroraContext mContext;
    private volatile boolean mShutdown;

    /**
     * Supplies service implementations. Null until the platform installs one, which is the
     * normal state on a host JVM and during early boot.
     */
    private volatile ServiceProvider mServiceProvider;

    private AuroraRuntime(AuroraContext context) {
        mContext = context;
    }

    /**
     * Installs the object that supplies service implementations.
     *
     * <p>Called by {@code aurora.platform} once the system is far enough along to have
     * services. Until then every accessor below reports the service as unavailable, which is
     * correct rather than merely convenient: asking for a service before the platform is ready
     * is a real error, and returning a half-built one would hide it.
     *
     * @param provider the provider, or null to detach
     */
    public void setServiceProvider(ServiceProvider provider) {
        mServiceProvider = provider;
    }

    /** Whether a {@link ServiceProvider} has been installed. */
    public boolean hasServiceProvider() {
        return mServiceProvider != null;
    }

    /**
     * Returns the service registered for {@code type}, or null when it is unavailable.
     *
     * <p>Use this where a missing service is a condition to handle. Use
     * {@link #requireService(Class)} where it is a programming error.
     */
    public <T extends AuroraService> T findService(Class<T> type) {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        ServiceProvider provider = mServiceProvider;
        return provider == null ? null : provider.find(type);
    }

    /**
     * Returns the service registered for {@code type}, failing if it is unavailable.
     *
     * @throws IllegalStateException if no provider is installed, or the provider has no
     *     implementation for {@code type}
     */
    public <T extends AuroraService> T requireService(Class<T> type) {
        T service = findService(type);
        if (service == null) {
            throw new IllegalStateException(
                    "no implementation available for " + type.getName()
                            + "; the platform has not registered one yet");
        }
        return service;
    }

    // --- Typed service accessors -------------------------------------------
    //
    // Sprint 04 ships the contracts only. Every implementation arrives later, so each of
    // these throws until the platform installs a provider. The failure names the service,
    // because a bare TODO() tells whoever hits it nothing about what is missing.

    /** Animation driving. See {@link aurora.sdk.service.AnimationService}. */
    public AnimationService animation() {
        return requireService(AnimationService.class);
    }

    /** Light and dark appearance. See {@link aurora.sdk.service.ThemeService}. */
    public ThemeService theme() {
        return requireService(ThemeService.class);
    }

    /** Notification posting and observation. */
    public NotificationService notifications() {
        return requireService(NotificationService.class);
    }

    /** System gesture routing. */
    public GestureService gestures() {
        return requireService(GestureService.class);
    }

    /** Audio volume. */
    public VolumeService volume() {
        return requireService(VolumeService.class);
    }

    /** Battery and power state. */
    public PowerService power() {
        return requireService(PowerService.class);
    }

    /** The display-cutout island. */
    public IslandService island() {
        return requireService(IslandService.class);
    }

    /**
     * Creates the runtime for this process.
     *
     * @param context environment the runtime should run in
     * @return the newly created runtime
     * @throws IllegalArgumentException if {@code context} is null
     * @throws IllegalStateException if the runtime is already initialized
     */
    public static AuroraRuntime initialize(AuroraContext context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        synchronized (sLock) {
            if (sInstance != null) {
                throw new IllegalStateException(
                        "AuroraRuntime is already initialized for this process");
            }
            sInstance = new AuroraRuntime(context);
            return sInstance;
        }
    }

    /**
     * Returns the runtime for this process.
     *
     * @throws IllegalStateException if {@link #initialize(AuroraContext)} has not been called,
     *     or if the runtime has been shut down
     */
    public static AuroraRuntime getInstance() {
        AuroraRuntime instance = sInstance;
        if (instance == null) {
            throw new IllegalStateException(
                    "AuroraRuntime is not initialized; call initialize() first");
        }
        return instance;
    }

    /** Returns whether a runtime currently exists for this process. */
    public static boolean isInitialized() {
        return sInstance != null;
    }

    /**
     * Tears down the runtime for this process.
     *
     * <p>Does nothing when no runtime exists, so this is safe to call unconditionally. After
     * this returns, {@link #isInitialized()} is false and the discarded instance reports
     * {@link #isShutdown()} as true.
     */
    public static void shutdown() {
        synchronized (sLock) {
            AuroraRuntime instance = sInstance;
            if (instance == null) {
                return;
            }
            instance.mShutdown = true;
            sInstance = null;
        }
    }

    /** Returns the environment this runtime was initialized with. Never null. */
    public AuroraContext context() {
        return mContext;
    }

    /**
     * Returns whether this particular instance has been shut down.
     *
     * <p>Useful for spotting a stale reference held across a {@link #shutdown()}.
     */
    public boolean isShutdown() {
        return mShutdown;
    }

    /** Convenience accessor for {@link AuroraVersion#versionString()}. */
    public String versionString() {
        return AuroraVersion.versionString();
    }

    /** Convenience accessor for {@link AuroraVersion#apiLevel()}. */
    public int apiLevel() {
        return AuroraVersion.apiLevel();
    }

    @Override
    public String toString() {
        return "AuroraRuntime{version=" + AuroraVersion.versionString()
                + ", apiLevel=" + AuroraVersion.apiLevel()
                + ", shutdown=" + mShutdown
                + ", context=" + mContext
                + "}";
    }
}
