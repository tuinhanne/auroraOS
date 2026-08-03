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

import aurora.sdk.design.MotionTokens
import aurora.sdk.design.Spring
import aurora.sdk.time.Timeline

/**
 * How an animation decides where it is.
 *
 * ## Why this is sealed, and why it has exactly two branches
 *
 * Spring, decay and fling animations have **no duration**. They run until their energy is
 * spent, and how long that takes depends on the velocity they were handed. [Timeline] --
 * duration, delay, repeat, reverse -- cannot describe them. Forcing it to, by estimating a
 * settle time up front, breaks the moment such an animation is interrupted: the new velocity
 * implies a new settle time, so the timeline would have to be swapped mid-flight and the
 * state machine would need a retargeting state.
 *
 * Splitting the two here means Sprint 06B adds solvers as new files and changes nothing that
 * already exists. See ADR-002.
 *
 * Sprint 06A implements [TimedSpec] only. A [PhysicsSpec] is accepted by the type system and
 * rejected loudly by the engine, in a message naming the sprint that will implement it.
 */
sealed interface AnimationSpec {

    /**
     * Whether a motion described by this spec has ended.
     *
     * The rule belongs here and not on the sampler for two reasons. It is made of this spec's
     * own numbers — a spring rests inside its `restDelta` and below its `restVelocity` — so a
     * sampler reporting it would be applying a rule it does not own, and every alternative
     * spring solver would have to re-implement it identically. And a timed animation cannot
     * derive it from a value at all: a timeline ends because time ran out, not because the value
     * arrived anywhere, which is why this takes [elapsedNanos] as well as [sample].
     *
     * Putting it here also keeps a `when` over spec kinds out of the engine. A spec added in a
     * later sprint brings its own rule with it and `AnimationHandleImpl` never learns it exists.
     */
    fun isFinished(elapsedNanos: Long, sample: MotionSample): Boolean
}

/**
 * Time decides progress.
 *
 * @param timeline where in the sequence a given elapsed time falls. Stateless, so seeking is
 *     a query rather than a rewind.
 * @param interpolator shapes the linear progress. Defaults to [Interpolator.LINEAR] because
 *     Sprint 06A ships no other.
 */
data class TimedSpec(
    val timeline: Timeline,
    val interpolator: Interpolator = Interpolator.LINEAR,
) : AnimationSpec {

    /**
     * Elapsed time at which [timeline] reports [progress].
     *
     * The inverse of [Timeline.progressAt], and the **only** place the progress-to-elapsed
     * direction is computed. Both directions of the mapping belong to this one type: scattering
     * them across `ExecutionTimeline`, `MotionSampler` and the handle is how the two halves
     * of an inverse pair drift apart without anyone noticing.
     *
     * ## Positions are per iteration
     *
     * [Timeline.progressAt] counts within an iteration and resets to 0 each time round, so a
     * progress value identifies a position **in one iteration**, not in the whole repeated
     * sequence. Seeking a three-times animation to 0.5 therefore lands halfway through its
     * first iteration and the remaining repeats play out from there.
     *
     * A draft of this method spanned the whole sequence instead, which reads more naturally
     * and is wrong: it made the two functions stop being inverses, and `seek(0.25f)` on a
     * three-times timeline produced a progress of 0.75. `AnimationApiTest` asserts the round
     * trip across five timeline shapes so that cannot come back.
     *
     * Out-of-range input is clamped rather than rejected, unlike [Animation.valueAt], which
     * must let an overshooting curve through. The two take different things: this takes a
     * *seek position*, which is normalised 0..1 by definition, while `valueAt` takes *eased
     * progress*, which a bouncy spring legitimately pushes past 1. `AnimationHandle.seek`
     * rejects out-of-range input loudly before it ever reaches here, so this clamp is a
     * second line rather than the policy.
     *
     * Pure arithmetic on the timeline own fields; it runs no animation, so it stays on the data.
     */
    fun elapsedForProgress(progress: Float): Long {
        val p = progress.coerceIn(0f, 1f)
        // An iteration boundary is ambiguous: it is both the end of one iteration and the
        // start of the next, and Timeline.progressAt resolves it as the start. On a timeline
        // with another iteration to come, landing exactly on the boundary would therefore
        // report progress 0 - seeking a repeating animation to its far end would snap it back
        // to its beginning. Land one nanosecond inside instead, which progressAt reports as 1.
        if (p == 1f && timeline.durationNanos > 0L &&
            (timeline.isInfinite || timeline.repeatCount > 0)
        ) {
            return timeline.delayNanos + timeline.durationNanos - 1L
        }
        return timeline.delayNanos + (timeline.durationNanos * p.toDouble()).toLong()
    }

    /** A timeline ends when it runs out. An infinite one never does. */
    override fun isFinished(elapsedNanos: Long, sample: MotionSample): Boolean =
        timeline.isFinishedAt(elapsedNanos)
}

/**
 * Energy decides progress. Declared in Sprint 06A, solved in Sprint 06B.
 *
 * The three properties are exactly what a solver needs and a [Timeline] cannot express: how fast
 * the motion was already going, and when it is close enough to its target to stop. Fixing their
 * shape now is what lets 06B be additive.
 *
 * ## Everything here is in normalised progress, not value units
 *
 * A solver built from this spec integrates progress from 0 toward 1 and reports it as
 * [MotionSample.value]; `Animation.valueAt` then maps that into value space. That is
 * what lets a sampler be constructed from the spec alone, with no knowledge of the animation's
 * `from` and `to` — and that in turn is what keeps Sprint 06B additive, since the engine's
 * `samplerFor(spec)` never has to grow a second parameter.
 *
 * Working in normalised space costs a spring nothing. Substituting `x = from + (to - from) * p`
 * into `x'' = -k(x - target) - c * x'` leaves `p'' = -k(p - 1) - c * p'`: the `(to - from)` factor
 * cancels, so stiffness, damping and settle time are all unchanged. Only velocity scales, which
 * is why the conversion below belongs at the call site rather than in the solver.
 *
 * A caller releasing a gesture at 800 pixels per second over a 400 pixel travel therefore passes
 * `initialVelocity = 2f`, not `800f`. `AnimationService.springTo` takes value units and does that
 * division, so ordinary callers never see it.
 *
 * The default values on the implementing specs are placeholders, chosen to be plausible rather
 * than measured. Sprint 06B is the first sprint with a solver that can say whether they are
 * right, and is free to change them.
 */
sealed interface PhysicsSpec : AnimationSpec {

    /**
     * Progress per second at the moment the animation starts.
     *
     * Normalised: 1.0 means the motion was crossing its whole range every second. Divide a
     * measured gesture velocity by the distance the animation spans to get this.
     */
    val initialVelocity: Float

    /** Below this speed, in progress per second, the motion counts as stopped. */
    val restVelocity: Float

    /** Within this distance, in progress, the motion counts as arrived. */
    val restDelta: Float

    /**
     * At rest when it is close enough to its target and slow enough.
     *
     * The target is 1 because everything on a `PhysicsSpec` is normalised progress, not value
     * units — see ADR-002. `SpringSpec` and `SnapSpec` both use this; `DecaySpec` overrides it,
     * because a decay has no target to be near.
     */
    override fun isFinished(elapsedNanos: Long, sample: MotionSample): Boolean =
        kotlin.math.abs(1f - sample.value) < restDelta &&
            kotlin.math.abs(sample.velocity) < restVelocity
}

/**
 * A spring pulling toward its target.
 *
 * Wraps a design token rather than replacing it: [Spring] says *which* spring the design
 * chose, this says *how to run it*. Two decisions made by two different people, so two types.
 */
data class SpringSpec(
    val spring: Spring = MotionTokens.SPRING_GENTLE,
    override val initialVelocity: Float = 0f,
    override val restVelocity: Float = 0.01f,
    override val restDelta: Float = 0.001f,
) : PhysicsSpec {

    init {
        require(restVelocity > 0f) {
            "restVelocity must be positive; $restVelocity would never report the motion stopped"
        }
        require(restDelta > 0f) {
            "restDelta must be positive; $restDelta would never report the motion arrived"
        }
    }
}

/** Motion coasting to a stop under friction. A fling with nothing to land on. */
data class DecaySpec(
    val friction: Float = 0.5f,
    override val initialVelocity: Float = 0f,
    override val restVelocity: Float = 0.01f,
    override val restDelta: Float = 0.001f,
) : PhysicsSpec {

    init {
        require(friction > 0f) { "friction must be positive; $friction would never settle" }
        require(restVelocity > 0f) {
            "restVelocity must be positive; $restVelocity would never report the motion stopped"
        }
        require(restDelta > 0f) {
            "restDelta must be positive; $restDelta would never report the motion arrived"
        }
    }

    /** A decay has nowhere to arrive. It ends when it stops moving. */
    override fun isFinished(elapsedNanos: Long, sample: MotionSample): Boolean =
        kotlin.math.abs(sample.velocity) < restVelocity
}

/** Motion settling onto the nearest of several resting positions. */
data class SnapSpec(
    val targets: List<Float>,
    val spring: Spring = MotionTokens.SPRING_SNAPPY,
    override val initialVelocity: Float = 0f,
    override val restVelocity: Float = 0.01f,
    override val restDelta: Float = 0.001f,
) : PhysicsSpec {

    init {
        require(targets.isNotEmpty()) { "a snap spec needs at least one target to snap to" }
        require(restVelocity > 0f) {
            "restVelocity must be positive; $restVelocity would never report the motion stopped"
        }
        require(restDelta > 0f) {
            "restDelta must be positive; $restDelta would never report the motion arrived"
        }
    }
}
