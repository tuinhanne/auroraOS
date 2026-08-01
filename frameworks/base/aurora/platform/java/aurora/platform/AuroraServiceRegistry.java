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

package aurora.platform;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Type-keyed registry of Aurora platform services.
 *
 * <p>Services are looked up by their interface rather than by a string name. That choice trades
 * a little flexibility for compile-time safety: a rename is caught by the compiler, and
 * {@link #get(Class)} needs no cast at the call site.
 *
 * <p>Registration is deliberately strict. {@link #register(Class, Object)} refuses to replace an
 * existing binding, because a silent overwrite would leave two callers holding different objects
 * for the same service with no indication which one wins. Call {@link #unregister(Class)} first
 * if replacement is genuinely intended.
 *
 * <p>Instances are safe to use from multiple threads.
 *
 * <p>Sprint 01 provides the registry but registers nothing into it. Wiring real services to the
 * Android system server is Sprint 02 work; see the README.
 */
public final class AuroraServiceRegistry {

    private final ConcurrentHashMap<Class<?>, Object> mServices = new ConcurrentHashMap<>();

    /** Creates an empty registry. */
    public AuroraServiceRegistry() {
    }

    /**
     * Registers a service under its interface type.
     *
     * @param type interface the service is published as
     * @param service implementation to publish
     * @param <T> service type
     * @throws IllegalArgumentException if {@code type} or {@code service} is null, or if
     *     {@code service} is not an instance of {@code type}
     * @throws IllegalStateException if a service is already registered for {@code type}
     */
    public <T> void register(Class<T> type, T service) {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (service == null) {
            throw new IllegalArgumentException("service must not be null");
        }
        if (!type.isInstance(service)) {
            throw new IllegalArgumentException(
                    service.getClass().getName() + " is not an instance of " + type.getName());
        }
        Object previous = mServices.putIfAbsent(type, service);
        if (previous != null) {
            throw new IllegalStateException(
                    "a service is already registered for " + type.getName()
                            + "; unregister it first to replace it");
        }
    }

    /**
     * Returns the service registered for {@code type}, or {@code null} if there is none.
     *
     * @param type interface to look up
     * @param <T> service type
     */
    public <T> T get(Class<T> type) {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        return type.cast(mServices.get(type));
    }

    /**
     * Returns the service registered for {@code type}, failing if absent.
     *
     * <p>Use this where a missing service is a programming error rather than a condition to
     * handle, so the failure surfaces at the point of use.
     *
     * @param type interface to look up
     * @param <T> service type
     * @throws IllegalStateException if no service is registered for {@code type}
     */
    public <T> T requireService(Class<T> type) {
        T service = get(type);
        if (service == null) {
            throw new IllegalStateException("no service registered for " + type.getName());
        }
        return service;
    }

    /** Returns whether a service is registered for {@code type}. */
    public boolean contains(Class<?> type) {
        return type != null && mServices.containsKey(type);
    }

    /**
     * Removes the service registered for {@code type}.
     *
     * @return true if a service was removed, false if there was nothing to remove
     */
    public boolean unregister(Class<?> type) {
        return type != null && mServices.remove(type) != null;
    }

    /** Returns the number of registered services. */
    public int size() {
        return mServices.size();
    }

    /**
     * Returns the registered service types.
     *
     * <p>The returned set is an unmodifiable snapshot; later registrations are not reflected in
     * it.
     */
    public Set<Class<?>> registeredTypes() {
        return Collections.unmodifiableSet(
                new java.util.HashSet<Class<?>>(mServices.keySet()));
    }

    /** Removes every registered service. Intended for teardown and tests. */
    public void clear() {
        mServices.clear();
    }

    @Override
    public String toString() {
        return "AuroraServiceRegistry{services=" + mServices.size() + "}";
    }
}
