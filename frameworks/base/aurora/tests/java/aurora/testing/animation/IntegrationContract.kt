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

import aurora.sdk.animation.Animation
import aurora.sdk.animation.DecaySpec
import kotlin.math.abs
import org.junit.Assert.fail

/**
 * Properties of a whole pipeline, including its caller and the point where units change.
 *
 * The **integration layer**, opened by Sprint 06B.2 and empty before it.
 *
 * `SamplerContract` and `PhysicsContract` both take a `MotionSampler` and work entirely in
 * normalised progress, so neither can observe anything on the far side of a unit boundary. That is
 * not a gap in them; it is what they are. Sprint 06B.0 nevertheless claimed
 * `assertConvergesToOne` guarded the boundary, and that claim was retracted in all four places it
 * stood before this layer was built — a normalised property cannot see a caller's arithmetic,
 * because the caller's arithmetic never reaches it.
 */
object IntegrationContract {

    /**
     * The travel a caller inferred, scaled by friction, returns exactly the velocity it was given.
     *
     * ```
     * (to - from) · friction  =  v₀
     * ```
     *
     * A product rather than a quotient, on three grounds. It introduces no division, so `to == from`
     * needs no special case. It reads as a conservation law — the inferred travel, scaled back by
     * friction, must return the gesture that produced it. And a caller who forgets to divide lands
     * off by exactly a factor of `friction`, which is a signature rather than a discrepancy.
     *
     * ## What this proves, and what it does not
     *
     * If a caller derives `to` through `DecaySpec.restingDisplacement`, this is an **identity** for
     * every friction: `(v/f)·f = v`. So it does not prove the friction model correct. It proves
     * the caller **routed through the single implementation** of that model — which is exactly what
     * a caller computing the travel itself violates, and exactly what this layer exists to observe.
     *
     * That `v₀/friction` is the right model was established in Sprint 06B.0 by
     * `aDecaysNormalisedInitialVelocityIsAlwaysItsFriction`, and is not re-proved here.
     *
     * ## The premise
     *
     * The friction used to derive `to` and the friction on the `DecaySpec` handed to the sampler
     * are assumed to be the same value from the same place, which holds while a caller receives one
     * spec and reads `friction` from it once. Should a later pipeline admit a gesture
     * configuration, a design token and a runtime override at the same time, this stays true but
     * stops being sufficient, and it would need a second witness in which two sources disagree
     * while each is individually plausible.
     */
    fun assertInferredTravelReturnsTheVelocity(
        name: String,
        animation: Animation,
        spec: DecaySpec,
        gestureVelocity: Float,
    ) {
        val travel = animation.to - animation.from
        val returned = travel * spec.friction
        val scale = maxOf(abs(gestureVelocity), abs(returned), 1f)
        if (abs(returned - gestureVelocity) / scale > TOLERANCE) {
            fail(
                "$name inferred a travel of $travel, which at friction ${spec.friction} returns " +
                    "$returned rather than the $gestureVelocity it was given"
            )
        }
    }

    /** Relative, and loose only enough to absorb float32 rounding across two multiplications. */
    private const val TOLERANCE = 1e-4f
}
