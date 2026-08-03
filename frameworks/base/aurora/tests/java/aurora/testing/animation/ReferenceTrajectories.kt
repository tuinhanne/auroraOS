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

package aurora.testing.animation

import aurora.sdk.animation.MotionSample
import aurora.sdk.animation.MotionSampler
import kotlin.math.exp

/**
 * Closed-form motions written directly into the test tree, to give the contract properties
 * something that satisfies them.
 *
 * **Synthetic trajectories for exercising contract properties only. Intentionally not physics
 * solvers, and not to be used to validate numerical integration.** Sprint 06B.1 adds the real
 * spring and replaces nothing here; these keep their job afterwards, which is to be the thing a
 * green run was green against.
 */

/**
 * An exponential decay, written out: `value = 1 - e^(-f·t)`, `velocity = f·e^(-f·t)`.
 *
 * Three lines, no state, no dispatch, no spec. It is the analytic solution rather than a model of
 * one, which is what keeps it on the right side of the sprint's no-solver boundary — there is
 * nothing here to get wrong that a solver could get right.
 *
 * It is the only production-shaped subject the physics tier has in Sprint 06B.0, and it earns
 * that by being the one motion whose completion metric is monotone **by inspection**: the metric
 * for a decay is `|1 - value|`, which is `e^(-f·t)`, which falls.
 */
class DecayTrajectory(private val friction: Float) : MotionSampler {
    override fun sampleAt(elapsedNanos: Long): MotionSample {
        val remaining = exp(-friction * elapsedNanos / 1_000_000_000f)
        return MotionSample(value = 1f - remaining, velocity = friction * remaining)
    }
}

/*
 * ## Why there is no oscillating trajectory here
 *
 * A spring's completion metric is `√(x² + (v/ω)²)`, and its never-increasing property is a
 * theorem about **solutions of** `ẍ + 2ζωẋ + ω²x = 0` — it follows from `dE/dt = -2ζωv²`, which
 * needs the trajectory to obey that equation. A hand-built oscillation that merely looks damped
 * is not a solution, so the metric is under no obligation to fall along it, and a red would not
 * distinguish a wrong metric from a wrong fixture. Making it a genuine solution means writing the
 * closed form, which is Sprint 06B.1's job and this sprint's stated boundary.
 *
 * So the spring and snap metrics have **no trajectory subject in Sprint 06B.0**, and saying so is
 * better than a fixture that cannot support the conclusion drawn from it. What they do have is
 * `PhysicsContractTest`, which checks the formula algebraically on hand-built samples — no
 * trajectory required, and no claim made about monotonicity along one. Sprint 06B.1 is the first
 * time `assertMetricNeverIncreases` is asked about a spring at all, and it should be read as the
 * first real test of the envelope rather than a regression check on it.
 */
