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

package aurora.runtime.animation

import aurora.sdk.animation.AnimationStrategy
import aurora.sdk.animation.TimedSpec

/**
 * The only strategy Sprint 06A ships: time decides progress.
 *
 * Almost all of the work is already done by [aurora.sdk.time.Timeline], which is stateless,
 * so this holds nothing but the last values it computed. That is deliberate: progress is
 * derived from elapsed time on every frame rather than accumulated from deltas, so a dropped
 * frame cannot make it drift, and two runs that reach the same elapsed time by different
 * routes agree exactly.
 *
 * Sprint 06B adds `SpringStrategy`, `DecayStrategy`, `SnapStrategy` and `FlingStrategy`
 * beside this file. None of them will need to change it.
 */
class TimedStrategy(private val spec: TimedSpec) : AnimationStrategy {

    override var progress: Float = 0f
        private set

    override var easedProgress: Float = spec.interpolator.transform(0f)
        private set

    override var isFinished: Boolean = false
        private set

    override fun advance(elapsedNanos: Long, deltaNanos: Long) {
        // deltaNanos is unused, and that is the point. See the class documentation.
        progress = spec.timeline.progressAt(elapsedNanos)
        easedProgress = spec.interpolator.transform(progress)
        isFinished = spec.timeline.isFinishedAt(elapsedNanos)
    }

    override fun reset() {
        progress = 0f
        easedProgress = spec.interpolator.transform(0f)
        isFinished = false
    }

    /**
     * Nothing to do.
     *
     * Moving the execution is `ExecutionTimeline.seekTo`; this hook exists for strategies
     * that carry integrator state across frames, and a timed one carries none. It is not
     * empty by oversight.
     */
    override fun seekTo(progress: Float) = Unit
}
