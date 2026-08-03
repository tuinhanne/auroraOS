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
     * own numbers and its own model — a spring measures its distance from rest as an oscillation
     * envelope, which is derived from the model rather than from the method of solution — so a
     * sampler reporting it would be applying a rule it does not own, and every alternative
     * spring solver, closed-form or stepped, would have to re-implement it identically. And a
     * timed animation cannot
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

    /**
     * How small [completionMetric] must get before the motion counts as over.
     *
     * In progress units, and comparable across families: 0.001 means the residual motion is under
     * a thousandth of the animation's travel, whether that motion is a spring settling or a fling
     * coasting. The two numbers this replaces were not comparable — nobody could say what
     * `restVelocity = 0.01` implied without first being told the friction, and for a decay one of
     * the two was always dead.
     */
    val completionThreshold: Float

    /**
     * How far this motion still is from rest, in progress units.
     *
     * **Must never increase while the sampler evolves.** The contract states observable
     * behaviour, not a formula: an implementation may compute this from energy, from a decay
     * envelope or from anything else, provided a later sample never reports more than an earlier
     * one. Stating it as behaviour rather than as monotonicity is deliberate — a claim about the
     * whole function would have to hold where the metric has fallen to 1e-9, and there float32
     * rounds in both directions.
     *
     * ## Why the old rule could not stay
     *
     * It read displacement and velocity separately: `|1 - value| < restDelta && |velocity| <
     * restVelocity`. An underdamped spring has zero velocity at **every turning point**, so once
     * its envelope fell below `restDelta` the rule reported finished at a turning point and
     * not-finished a moment later, as the motion swept back through its target. The engine
     * survives that by stopping at the first frame reporting true and never asking again — but
     * then the instant a spring settles depends on which frame happens to land near a turning
     * point, and the same spring finishes at different times at 60Hz and at 120Hz.
     *
     * See ADR-008.
     */
    fun completionMetric(sample: MotionSample): Float

    /**
     * A comparison, and nothing else.
     *
     * No spec overrides this. `completionMetric` owns the physics and `completionThreshold` owns
     * the UX; leaving a third thing for a family to redefine is how "finished" quietly came to
     * mean two different things in the first place.
     */
    override fun isFinished(elapsedNanos: Long, sample: MotionSample): Boolean =
        completionMetric(sample) < completionThreshold
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
    override val completionThreshold: Float = 0.001f,
) : PhysicsSpec {

    init {
        require(completionThreshold > 0f) {
            "completionThreshold must be positive; $completionThreshold would never be reached"
        }
    }

    /**
     * The amplitude this oscillation would settle at if damping stopped now.
     *
     * With `x` the displacement still to cover and `v` the current speed, `√(x² + (v/ω)²)` is the
     * conserved amplitude of the equivalent undamped oscillator. Damping only removes energy: for
     * `E = ½(v² + ω²x²)`, substituting `ẋ = v` and `v̇ = -ω²x - 2ζωv` gives `dE/dt = -2ζωv²`,
     * which is zero exactly at the turning points and negative everywhere else. So this never
     * increases.
     *
     * That is what repairs the hole the old rule left. At a turning point `v = 0`, so this equals
     * the true envelope rather than the instantaneous distance; between turning points the
     * `(v/ω)²` term supplies what displacement alone stops accounting for.
     *
     * `v/ω` has units of `(progress/second) / (1/second)`, so the whole expression is in progress
     * and shares one threshold with every other family.
     */
    override fun completionMetric(sample: MotionSample): Float {
        val omega = kotlin.math.sqrt(spring.stiffness)
        val x = 1f - sample.value
        val scaledVelocity = sample.velocity / omega
        return kotlin.math.sqrt(x * x + scaledVelocity * scaledVelocity)
    }
}

/** Motion coasting to a stop under friction. A fling with nothing to land on. */
data class DecaySpec(
    /**
     * How quickly the motion sheds speed, in inverse seconds.
     *
     * Derived rather than chosen. A decay is over when `e^(-f·t)` falls below
     * [completionThreshold], so the settle time is `-ln(threshold)/f`; at the default threshold
     * of 0.001, `f = 4.6` puts a fling at about 1.5 seconds.
     *
     * The previous default of 0.5 works out to **13.8 seconds**. It had been documented as
     * plausible rather than measured, and Sprint 06B.0 is the first sprint able to measure it,
     * because until completion became a single scalar there was no closed form to solve.
     */
    val friction: Float = 4.6f,
    override val initialVelocity: Float,
    override val completionThreshold: Float = 0.001f,
) : PhysicsSpec {

    init {
        require(friction > 0f) { "friction must be positive; $friction would never settle" }
        require(initialVelocity != 0f) {
            "a decay released at rest travels nowhere; there is nothing to animate"
        }
        require(completionThreshold > 0f) {
            "completionThreshold must be positive; $completionThreshold would never be reached"
        }
    }

    /**
     * How far a decay released at [velocity] travels before stopping, in [velocity]'s own units.
     *
     * Under exponential friction the total travel is `v₀/f`, available in closed form before the
     * first frame. That is what makes a decay's target derived rather than absent:
     *
     * ```
     * to = from + restingDisplacement(v₀)
     * ```
     *
     * ADR-002 called this reading circular — where a decay stops is an output of the physics, so
     * it cannot also be an input. That rested on an unstated assumption: that finding the resting
     * position means simulating to it. It does not; it is one division. See ADR-008.
     *
     * ## Why this lives here
     *
     * So the friction model has one implementation. A caller computing `v₀/friction` itself while
     * a sampler computes `1 - e^(-ft)` would be two copies of one model, and if they drifted the
     * animation would still run, still look smooth, and stop in the wrong place with nothing to
     * report it. `PhysicsContract.assertConvergesToOne` is what turns "the two agree" from a
     * comment into a check.
     *
     * Note what this does **not** do: it never reaches the sampler. Normalised against its own
     * travel, a decay's position is `1 - e^(-f·t)` and `v₀` cancels out entirely — initial
     * velocity decides how far a decay goes, not how it goes. So this is for whoever builds the
     * `Animation`, and `samplerFor(spec)` keeps its single parameter.
     */
    fun restingDisplacement(velocity: Float): Float = velocity / friction

    /**
     * The fraction of its travel this decay has still to cover.
     *
     * Normalised against its own total travel, a decay's position is `1 - e^(-f·t)`, so this is
     * `e^(-f·t)` — falling monotonically, with no oscillation to account for and so no envelope
     * to reconstruct.
     *
     * The override this replaces read velocity alone, on the grounds that a decay has nowhere to
     * arrive. It has: its resting point is derived rather than supplied, which is what Sprint
     * 06B.0 settled. So a decay uses the same target-relative measure every other family does.
     */
    override fun completionMetric(sample: MotionSample): Float =
        kotlin.math.abs(1f - sample.value)
}

/** Motion settling onto the nearest of several resting positions. */
data class SnapSpec(
    val targets: List<Float>,
    val spring: Spring = MotionTokens.SPRING_SNAPPY,
    override val initialVelocity: Float = 0f,
    override val completionThreshold: Float = 0.001f,
) : PhysicsSpec {

    init {
        require(targets.isNotEmpty()) { "a snap spec needs at least one target to snap to" }
        require(completionThreshold > 0f) {
            "completionThreshold must be positive; $completionThreshold would never be reached"
        }
    }

    /**
     * The same envelope a spring uses, because once a target is chosen a snap **is** a spring.
     *
     * Duplicated rather than shared. Extracting it would mean a base class or a helper that only
     * two specs use and that would have to be widened the moment a third family measures rest
     * differently — the abstraction-with-no-variation this sprint declined to build. If a fourth
     * family arrives wanting the same formula, that is when it earns a home of its own.
     *
     * Whether snap needs a sampler at all is left to Sprint 06B.3, after the spring exists and
     * how general it turned out can be seen.
     */
    override fun completionMetric(sample: MotionSample): Float {
        val omega = kotlin.math.sqrt(spring.stiffness)
        val x = 1f - sample.value
        val scaledVelocity = sample.velocity / omega
        return kotlin.math.sqrt(x * x + scaledVelocity * scaledVelocity)
    }
}
