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

package aurora.runtime.time

import aurora.sdk.time.AuroraClock

/**
 * The real monotonic clock.
 *
 * ## The single sanctioned caller of System.nanoTime
 *
 * RULE-007 forbids `System.nanoTime`, `System.currentTimeMillis`, `Thread.sleep`, `Handler`,
 * `Looper` and `Choreographer` everywhere in `aurora.sdk` and `aurora.runtime`. This file is
 * the one exemption, and `arch-test.sh` encodes it by path: the call is a failure in every
 * other file and permitted here.
 *
 * Every such rule needs exactly one hole, or the abstraction it protects cannot be built at
 * all. What matters is that the hole is written into the tool rather than remembered, because
 * an exemption that lives only in someone's head gets copied within a few sprints.
 *
 * `nanoTime` and not `currentTimeMillis`: the latter jumps when NTP corrects it or the user
 * edits the date, which would make an animation freeze or leap. See [AuroraClock].
 *
 * Stateless, so a single instance serves the whole process.
 */
object RealtimeClock : AuroraClock {

    override fun nowNanos(): Long = System.nanoTime()
}
