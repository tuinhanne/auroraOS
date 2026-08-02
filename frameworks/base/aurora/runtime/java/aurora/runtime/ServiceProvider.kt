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

import aurora.sdk.service.AuroraService

/**
 * Supplies service implementations to the runtime.
 *
 * ## Why this indirection exists
 *
 * [AuroraRuntime] hands out services, but the implementations live in `aurora.platform`, and
 * `aurora.runtime` is forbidden from importing that package. Without a seam the only ways
 * forward would be to move the runtime up a layer or to punch a hole in the boundary, and
 * both give up host-side unit testing for everything below.
 *
 * So the runtime declares what it needs and the platform satisfies it. This is the pattern the
 * module README prescribes for exactly this situation: define the interface in the layer that
 * needs it, implement it in the layer that can.
 *
 * A useful side effect is that a test can install a provider returning fakes, with no Android
 * anywhere.
 */
interface ServiceProvider {

    /**
     * Returns the implementation registered for [type], or null when there is none.
     *
     * Null rather than an exception: whether a missing service is fatal is the caller's
     * judgement, and [AuroraRuntime.service] already provides the throwing variant for callers
     * who consider it a programming error.
     */
    fun <T : AuroraService> find(type: Class<T>): T?
}
