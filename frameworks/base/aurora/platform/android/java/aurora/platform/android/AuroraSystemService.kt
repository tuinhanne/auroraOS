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
 * ## Not wired to anything yet
 *
 * Sprint 03 Task 4.1 introduces the layer and nothing else. `onStart` publishes no service and the
 * runtime is not constructed here; the device overlay does not name this class, and no makefile
 * puts this module on the system server's classpath. It is reachable by nothing, on purpose, so
 * that the build-graph change can be verified on its own before anything can affect boot.
 */
class AuroraSystemService(context: Context) : SystemService(context) {

    /**
     * Called by `SystemServiceManager` once this service has been constructed.
     *
     * Empty until Task 4.3. What belongs here is the runtime's construction and the registration
     * of its services — and both need `AuroraContext.hostContext()` to stop being `Object` first,
     * which is Task 4.2.
     */
    override fun onStart() {
        // Deliberately empty. See the class documentation.
    }
}
