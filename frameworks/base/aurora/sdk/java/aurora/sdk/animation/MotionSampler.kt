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

package aurora.sdk.animation

/**
 * Turns an elapsed time into a position and a velocity.
 *
 * ## One method, and nothing else
 *
 * No `advance`, no `reset`, no properties, no policy. A sampler answers one question and holds
 * no opinion about anything else — not about when the motion ends, not about which execution it
 * belongs to, not about what a frame is.
 *
 * ## Created per execution
 *
 * A sampler is built when an execution starts and discarded when it ends, so it never has to be
 * reset and never carries anything from a previous run. That makes a stepped sampler's internal
 * state — position, velocity, step count — entirely its own business, with nothing for the
 * engine to remember to clear.
 *
 * ## Elapsed, never delta
 *
 * A closed-form sampler evaluates a function of elapsed time. A stepped one derives its step
 * count from elapsed rather than accumulating frame deltas, which is what keeps 60Hz and 120Hz
 * bit-identical at the same instant. Neither needs a delta, and a parameter nobody uses is an
 * invitation to accumulate something — and accumulation drifts.
 */
interface MotionSampler {

    /**
     * Where the motion is at [elapsedNanos] since this execution began.
     *
     * Callers sample in non-decreasing order of elapsed time. A closed-form sampler ignores that
     * and could be called in any order; a stepped one integrates forward and cannot go back.
     *
     * This constrains the *caller*, not the implementation, and that is deliberate: it is what
     * makes a stepped sampler legal to write at all. Without it a caller would be entitled to
     * sample in any order, and a stepped sampler defending itself would be breaking the contract
     * rather than keeping it.
     */
    fun sampleAt(elapsedNanos: Long): MotionSample
}
