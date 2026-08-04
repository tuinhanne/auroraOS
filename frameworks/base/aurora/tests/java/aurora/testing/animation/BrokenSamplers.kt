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

/**
 * Samplers that are wrong on purpose, one for each contract property.
 *
 * They exist so every property can be shown to go red for its stated reason (RULE-015). A suite
 * that only ever runs against correct implementations cannot tell a property that holds from a
 * property that checks nothing — both are green, and both stay green when the contract is later
 * weakened by accident.
 *
 * None of these is a solver. They live in the test tree, never in `runtime/`, and
 * `verify-motion-evidence.sh` fails if one appears outside this directory.
 */

/**
 * Correct, and the baseline the broken ones are deviations from.
 *
 * Constant velocity, so its derivative is exact at every probe and any rejection of it is a
 * defect in the harness rather than in the sampler.
 */
class LinearSampler(private val perSecond: Float = 1f) : MotionSampler {
    override fun sampleAt(elapsedNanos: Long): MotionSample =
        MotionSample(perSecond * elapsedNanos / 1_000_000_000f, perSecond)
}

/**
 * Breaks `assertFinite`: plausible until it arrives, then NaN.
 *
 * Placed after convergence deliberately. A sampler that returned NaN from its first sample would
 * be caught by anything; the one that survives review is the one that behaves until the moment
 * the animation was going to end anyway.
 */
class NaNAfterConvergenceSampler : MotionSampler {
    override fun sampleAt(elapsedNanos: Long): MotionSample =
        if (elapsedNanos > 1_000_000_000L) MotionSample(Float.NaN, Float.NaN)
        else MotionSample(elapsedNanos / 1_000_000_000f, 1f)
}

/**
 * Breaks `assertDeterministic`: two instances disagree, because they share a counter.
 *
 * The state is in a companion object rather than in an instance, and that is the whole point. An
 * instance field would make every sampler behave identically from its own construction, which
 * `assertDeterministic` would not catch and should not — a stepped sampler has instance state by
 * design. What is never legitimate is state that outlives the execution it belongs to.
 *
 * It models an ordinary mistake, a memoisation cache hung off a companion, and it needs no clock
 * and no random source to do it — RULE-009 bans the obvious ones, and this shows the ban is not
 * what makes determinism hold.
 */
class SharedCounterSampler : MotionSampler {
    override fun sampleAt(elapsedNanos: Long): MotionSample {
        calls++
        return MotionSample(elapsedNanos / 1_000_000_000f + calls * 1e-4f, 1f)
    }

    private companion object {
        var calls = 0
    }
}

/**
 * Breaks `assertMetricNeverIncreases`: climbs halfway, then slides back.
 *
 * The turn happens at value 0.5, far above any threshold, because a fixture that turned around
 * after arriving would prove nothing — the assertion stops looking once the motion is finished,
 * which is correct, since the engine has stopped asking by then. The interesting failure is a
 * motion that loses ground while still running, and this is the smallest one.
 */
class IncreasingEnvelopeSampler : MotionSampler {
    override fun sampleAt(elapsedNanos: Long): MotionSample {
        val t = elapsedNanos / 1_000_000_000f
        return if (t < 1f) MotionSample(0.5f * t, 0.5f) else MotionSample(1f - 0.5f * t, -0.5f)
    }
}

/**
 * Breaks `assertConvergesToOne`: settles neatly, at the wrong place.
 *
 * Its metric falls the whole way, so `assertMetricNeverIncreases` accepts it — deliberately. This
 * is the failure that only convergence catches: a decay whose caller-computed travel disagrees
 * with the sampler's shape stops short exactly like this, moving smoothly and monotonically to a
 * target that is not the one the `Animation` was built with.
 */
class NonConvergingSampler : MotionSampler {
    override fun sampleAt(elapsedNanos: Long): MotionSample {
        val progress = 1f - kotlin.math.exp(-4.6f * elapsedNanos / 1_000_000_000f)
        return MotionSample(0.5f * progress, 0f)
    }
}

/**
 * Breaks `assertVelocityMatchesDerivative`: the value is fine, the velocity is doubled.
 *
 * A constant factor rather than noise, because that is the mistake a real solver makes — a
 * missing or duplicated normalisation — and because it is exactly what a derivative check
 * computed at the sampler's own step would fail to notice.
 */
class WrongDerivativeSampler : MotionSampler {
    override fun sampleAt(elapsedNanos: Long): MotionSample =
        MotionSample(elapsedNanos / 1_000_000_000f, 2f)
}
