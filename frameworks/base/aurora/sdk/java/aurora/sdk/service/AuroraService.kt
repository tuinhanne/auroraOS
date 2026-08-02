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

/**
 * Marker for every Aurora platform service.
 *
 * ## Why the interfaces live in `aurora.sdk` and not next to their implementations
 *
 * Callers depend on the contract, never on the class that satisfies it. That is what lets
 * `aurora.runtime` hand out services it cannot see the implementation of: the implementations
 * live in `aurora.platform`, which `runtime` is forbidden from importing.
 *
 * It also means a service can be swapped, faked in a test, or left unimplemented without any
 * caller changing.
 *
 * ## Rules for anything declared under this package
 *
 * These interfaces are compiled against `core_current`. No `android.*`, no Compose, no
 * `aurora.platform`. If a service contract seems to need an Android type, express it with a
 * plain type here and convert in the implementation.
 */
interface AuroraService {

    /**
     * Stable identifier for logs and diagnostics.
     *
     * Not a lookup key: services are resolved by their interface type, so a rename is caught
     * by the compiler rather than becoming a runtime miss.
     */
    val serviceName: String

    /**
     * Whether this service is usable right now.
     *
     * A service can exist but be unavailable, for example when the platform it wraps is not
     * present on this device. Callers should check rather than assume, because the alternative
     * is discovering it through an exception on a user-visible path.
     */
    val isAvailable: Boolean
        get() = true
}
