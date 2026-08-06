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

package aurora.platform.android

import android.content.Context
import aurora.runtime.ServiceProvider
import aurora.runtime.volume.DefaultVolumeService
import aurora.sdk.service.AuroraService
import aurora.sdk.service.VolumeService

/**
 * What the runtime asks when a caller wants a service.
 *
 * `AuroraRuntime` hands out services and cannot see their implementations — `aurora.runtime` is
 * forbidden from importing this layer. `ServiceProvider` is the seam that makes that survivable,
 * and this is its first real implementation: Sprint 04 declared seven service interfaces and
 * shipped none of the platform half, which is why `AnimationService` has never had an implementing
 * class and why `DefaultVolumeService` has had nowhere to live since Sprint 04.1 built it.
 *
 * ## One service, and the rest answer null
 *
 * [find] returns null for anything not yet implemented, which is what its contract asks for:
 * *"whether a missing service is fatal is the caller's judgement"*. Six of the seven are missing
 * today and say so, rather than throwing or returning something that pretends.
 *
 * ## Built once
 *
 * The volume service is constructed eagerly and held. It registers no broadcast receiver until
 * something listens to it — that is [AndroidVolumeSource]'s doing — so an Aurora that nobody asks
 * about volume costs one object and no platform resources.
 */
class AndroidServiceProvider(context: Context) : ServiceProvider {

    private val volume: VolumeService =
        DefaultVolumeService(AndroidVolumeSource(context))

    @Suppress("UNCHECKED_CAST")
    override fun <T : AuroraService> find(type: Class<T>): T? = when (type) {
        VolumeService::class.java -> volume as T
        else -> null
    }
}
