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
 * Where a motion is and how fast it is going, at one instant.
 *
 * ## A value, and it stays one
 *
 * Immutable, never cached, never reused, never pooled. One is created per sample and forgotten.
 *
 * The rule exists because of a specific temptation: anyone worried about allocating per
 * animation per frame will reach for an object pool, and pooling breaks exactly the property
 * that makes a sample useful. A pooled sample handed to a listener can be overwritten underneath
 * it later in the same frame, so the number the listener read is no longer the number it acts
 * on — a failure that is invisible in review and intermittent at runtime.
 *
 * It also keeps determinism easy to reason about: if a sample can never change after it is made,
 * "what was this animation doing at 96ms" has exactly one answer.
 *
 * ## No `finished`
 *
 * Whether a motion has ended is a policy, not a measurement, and it is made of the spec's own
 * numbers. See [AnimationSpec.isFinished].
 *
 * @param value normalised position. May leave 0..1 — an overshooting spring is supposed to — and
 *     may decrease, which is why it is not called progress.
 * @param velocity rate of change of [value] with respect to time, in normalised units per
 *     second. Each sampler supplies this by whatever method suits its model.
 */
data class MotionSample(
    val value: Float,
    val velocity: Float,
)
