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

/**
 * The environment a {@link AuroraRuntime} runs inside.
 *
 * <p>This is Aurora's own view of "where am I running", deliberately kept independent of
 * {@code android.content.Context}. Keeping the abstraction here means the runtime can be unit
 * tested on a host JVM with no device, no emulator and no Android stubs on the classpath.
 *
 * <p>The link to the real platform is {@link #hostContext()}, typed as {@link Object}
 * permanently. Callers that need the platform context cast it in the layer that is allowed to
 * name Android types, rather than spreading them through the runtime.
 *
 * <p>This paragraph used to end "Sprint 02 will narrow it to {@code android.content.Context}",
 * and Sprint 03's plan in the module README said the same. Sprint 03 refused the narrowing
 * instead, because it contradicts the sentence above it: {@code runtime.contract} forbids
 * {@code android.} with no sunset, for the stated reason that this layer must unit test on a host
 * JVM. Narrowing the return type would put Android on the runtime's classpath and cost the 356
 * host tests — the first of the three invariants ADR-012 exists to keep. The cast is one line in
 * {@code aurora.platform.android} and the alternative was a layer boundary.
 *
 * <p>Instances are immutable and safe to share between threads. Build them with
 * {@link #builder(String)}.
 */
public final class AuroraContext {

    private final String mPackageName;
    private final boolean mSystemContext;
    private final Object mHostContext;

    private AuroraContext(Builder builder) {
        mPackageName = builder.mPackageName;
        mSystemContext = builder.mSystemContext;
        mHostContext = builder.mHostContext;
    }

    /**
     * Starts building a context for the given package.
     *
     * @param packageName owning package, for example {@code "android"} for the system server
     * @throws IllegalArgumentException if {@code packageName} is null or blank
     */
    public static Builder builder(String packageName) {
        return new Builder(packageName);
    }

    /** Returns the package that owns this context. Never null or blank. */
    public String packageName() {
        return mPackageName;
    }

    /**
     * Returns whether this context represents a system-privileged process.
     *
     * <p>Sprint 01 treats this as a plain flag supplied by the caller. Once Aurora is wired into
     * the platform this will be derived from the real process UID instead.
     */
    public boolean isSystemContext() {
        return mSystemContext;
    }

    /**
     * Returns the underlying platform context, or {@code null} when running outside Android
     * such as in a host-side unit test.
     *
     * <p>Typed as {@link Object} on purpose and permanently; see the class documentation.
     */
    public Object hostContext() {
        return mHostContext;
    }

    @Override
    public String toString() {
        return "AuroraContext{package=" + mPackageName
                + ", system=" + mSystemContext
                + ", hostContext=" + (mHostContext != null ? "present" : "absent")
                + "}";
    }

    /** Builder for {@link AuroraContext}. Not thread safe; build on one thread, then share. */
    public static final class Builder {

        private final String mPackageName;
        private boolean mSystemContext;
        private Object mHostContext;

        private Builder(String packageName) {
            if (packageName == null || packageName.trim().isEmpty()) {
                throw new IllegalArgumentException("packageName must not be null or blank");
            }
            mPackageName = packageName;
        }

        /** Marks this context as belonging to a system-privileged process. */
        public Builder setSystemContext(boolean systemContext) {
            mSystemContext = systemContext;
            return this;
        }

        /**
         * Attaches the platform context. Pass {@code null} when running on a host JVM.
         *
         * <p>Will become {@code android.content.Context} in Sprint 02.
         */
        public Builder setHostContext(Object hostContext) {
            mHostContext = hostContext;
            return this;
        }

        /** Builds an immutable {@link AuroraContext}. */
        public AuroraContext build() {
            return new AuroraContext(this);
        }
    }
}
