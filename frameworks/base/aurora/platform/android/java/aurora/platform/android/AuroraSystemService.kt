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
import android.util.Log
import aurora.sdk.service.VolumeService
import aurora.sdk.service.VolumeStream
import com.android.server.SystemService

/**
 * Where Aurora starts on a device.
 *
 * ## Started by a resource, not by a patch
 *
 * `SystemServer` reads `config_deviceSpecificSystemServices` and starts every class it names. That
 * array is empty in AOSP and filled by a device resource overlay — a tree Aurora already owns — so
 * nothing upstream is modified to reach this constructor. Sprint 03 Task 2 established that, after
 * a survey that was not allowed to assume `SystemServer` was the answer.
 *
 * The call site matters as much as the mechanism:
 *
 * ```java
 * for (final String className : classes) {
 *     try { mSystemServiceManager.startService(className); }
 *     catch (Throwable e) { reportWtf("starting " + className, e); }
 * }
 * ```
 *
 * A failure here is a WTF in the log, not a bootloop — **structurally**, rather than because
 * whoever wired this up remembered to guard it. That is the strongest reason this hook was
 * preferred to an edit in `SystemServer`, where the try/catch would have been one review away from
 * not existing.
 *
 * ## Why this class is in a module of its own
 *
 * ADR-012. It is the only place in Aurora that may import `android.`, and that is now a build fact
 * rather than a convention: everything below it compiles against `core_current`, where Android is
 * absent from the classpath instead of merely unused. A later change that pulls `android.content.`
 * into the host-verifiable half is a compile error rather than something review has to notice.
 *
 * ## It says something, because silence proves nothing
 *
 * `onStart` was empty until Task 4.4b, and an empty one would have made Boot PASS worthless: a
 * device that boots with a silent Aurora is indistinguishable from one where the overlay did not
 * apply, the jar never reached the classpath, or the class was never named. All four look like a
 * phone that works.
 *
 * So each stage reports itself, and each is caught separately. `SystemServer` already wraps this
 * whole call in `try/catch → reportWtf`, which keeps a failure from bootlooping; the inner catch
 * exists so that a failure is described by **Aurora** — naming which stage and why — rather than
 * arriving as a stack trace attributed to a class name.
 *
 * Still publishes no binder service. Reaching the runtime's registry is later work; what this
 * proves is that the class loads, constructs, and can build its own service graph inside
 * `system_server`.
 */
class AuroraSystemService(context: Context) : SystemService(context) {

    /**
     * Called by `SystemServiceManager` once this service has been constructed.
     *
     * Three facts, in the order they become knowable: that the class was reached at all, that the
     * provider can be built, and that a service resolved through it can answer a real question
     * about the device. The third is the one that exercises everything — `AndroidVolumeSource`
     * asking `AudioManager` for a level it did not make up.
     */
    override fun onStart() {
        Log.i(TAG, "onStart: Aurora is running inside system_server")
        try {
            val provider = AndroidServiceProvider(context)
            val volume = provider.find(VolumeService::class.java)
            if (volume == null) {
                Log.w(TAG, "volume service did not resolve")
                return
            }
            Log.i(
                TAG,
                "volume service resolved: media=" + volume.levelOf(VolumeStream.MEDIA) +
                    " steps=" + volume.stepCountOf(VolumeStream.MEDIA) +
                    " active=" + volume.activeStream
            )
        } catch (t: Throwable) {
            // Reported here as well as by SystemServer, because SystemServer's message names the
            // class it was starting and this one names what Aurora was doing when it failed.
            Log.e(TAG, "building the service graph failed", t)
        }
    }

    private companion object {
        const val TAG = "Aurora"
    }
}
