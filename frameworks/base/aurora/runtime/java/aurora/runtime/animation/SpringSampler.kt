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

import aurora.sdk.animation.MotionSample
import aurora.sdk.animation.MotionSampler
import aurora.sdk.animation.SpringSpec
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt

/**
 * A damped harmonic oscillator, in closed form.
 *
 * The first solver in Aurora, and the first implementation judged against a contract that already
 * existed before it — `docs/contracts/motion-sampler-contract.md`. It integrates nothing and holds
 * no state, so it may be sampled in any order and satisfies `ClosedFormSamplerContract` as well as
 * the universal properties.
 *
 * ## The equation, and where the initial conditions come from
 *
 * Work in the displacement still to cover, `y = 1 - value`, so the motion starts at `y = 1` and
 * settles at `y = 0`:
 *
 * ```
 * y'' + 2ζω y' + ω² y = 0        y(0) = 1        y'(0) = -v₀
 * ```
 *
 * with `ω = √stiffness` and `ζ` the damping ratio. The sign on `y'(0)` is the only subtle part:
 * a caller handing over a positive velocity is moving *toward* the target, which is `y`
 * decreasing.
 *
 * ## There is no branch for critical damping
 *
 * The textbook implementation selects on `ζ < 1`, `ζ == 1` and `ζ > 1`. That is a trap here,
 * because `SPRING_SNAPPY` is `ζ = 1` **exactly** — the critically damped case is not an edge case
 * to be covered for completeness, it is the most-used token in the system, and the two
 * neighbouring formulas both divide by a quantity that vanishes there.
 *
 * They need not. Writing the solution as
 *
 * ```
 * y(t) = e^(-ζωt) · [ C + k·S ]      C = cos(ω_d t)      S = sin(ω_d t)/ω_d      k = ζω - v₀
 * ```
 *
 * every ζ-dependent denominator sits inside `S`, and `S → t` as `ω_d → 0`. Substituting that limit
 * gives `e^(-ωt)·[1 + (ω - v₀)t]`, which **is** the critically damped solution `(A + Bt)e^(-ωt)`.
 * So `S` is computed as `t · sinc(z)` with `sinc(0) = 1`, and the singularity is removed rather
 * than special-cased.
 *
 * The derivative needs no branch either, which is less obvious:
 *
 * ```
 * y'(t) = e^(-ζωt) · [ -ζω(C + kS) + ω²(ζ²-1)·S + kC ]
 * ```
 *
 * For `ζ < 1` the middle term comes from `C' = -ω_d² S` and for `ζ > 1` from `C' = +ω_h² S`, and
 * `-ω_d² = ω²(ζ²-1) = +ω_h²`. The sign flip is already inside the expression. Only `C` and `S`
 * change between the two branches: `cos` and `sinc` against `cosh` and `sinhc`.
 *
 * See ADR-008 and `docs/specs/2026-08-03-sprint-06b1-spring-design.md` §2.
 */
class SpringSampler(spec: SpringSpec) : MotionSampler {

    private val omega = sqrt(spec.spring.stiffness)
    private val zeta = spec.spring.dampingRatio

    /**
     * `1 - ζ²`, factored so it is never a difference of nearly equal quantities.
     *
     * At `ζ = 0.9999`, float32 computes `ζ²` as about 0.9998 and the subtraction `1f - 0.9998f`
     * discards roughly four of its seven significant digits — so `ω_d` would be wrong by about
     * `1e-3` relative before any trigonometry happened. Neither factor here is such a difference.
     *
     * **Nothing in this file may recompute this as `1f - zeta * zeta`.** In particular the
     * derivative's middle term is `ω²(ζ²-1)`, which written literally is exactly the cancellation
     * this line exists to avoid; below it appears as `-omega * omega * discriminant`.
     *
     * Neither tier of the contract harness can see this mistake. A spring with a badly computed
     * `ω_d` derives its value and its velocity from the *same* bad `ω_d`, so they stay mutually
     * consistent and the sampler tier passes; the completion metric uses `ω`, not `ω_d`, so the
     * physics tier passes too. `SpringSamplerTest` guards it with a `Double` reference, and
     * nothing else does.
     */
    private val discriminant = (1f - zeta) * (1f + zeta)

    private val underdamped = discriminant > 0f

    /** `ω_d` when underdamped and `ω_h` when overdamped. Zero at `ζ = 1`, which `sinc` handles. */
    private val omegaScaled = omega * sqrt(abs(discriminant))

    /** `ζω - v₀`, the coefficient the initial conditions produce. */
    private val k = zeta * omega - spec.initialVelocity

    override fun sampleAt(elapsedNanos: Long): MotionSample {
        val t = elapsedNanos / NANOS_PER_SECOND
        val z = omegaScaled * t

        val c = if (underdamped) cos(z) else cosh(z)
        val s = t * (if (underdamped) sinc(z) else sinhc(z))
        val decay = exp(-zeta * omega * t)

        val y = decay * (c + k * s)
        val yPrime = decay * (-zeta * omega * (c + k * s) - omega * omega * discriminant * s + k * c)

        // `value` counts up from 0 toward 1 while `y` counts the distance down to zero, so both
        // are negated. Nothing is clamped: an underdamped spring overshoots past 1 and must.
        return MotionSample(value = 1f - y, velocity = -yPrime)
    }

    private companion object {

        const val NANOS_PER_SECOND = 1_000_000_000f

        /**
         * `sin(z)/z`, with its removable singularity filled in.
         *
         * Only `z == 0` needs the guard. Direct division carries no cancellation anywhere else:
         * `sin(z)` is accurate to about one ulp, `z` is exact, and the quotient to about two. A
         * Taylor series below some cutoff would add a constant to justify and a branch to test
         * for no accuracy — an earlier draft of the spec prescribed one and it was removed.
         */
        fun sinc(z: Float): Float = if (z == 0f) 1f else sin(z) / z

        /** `sinh(z)/z`, the overdamped counterpart, with the same limit at zero. */
        fun sinhc(z: Float): Float = if (z == 0f) 1f else sinh(z) / z
    }
}
