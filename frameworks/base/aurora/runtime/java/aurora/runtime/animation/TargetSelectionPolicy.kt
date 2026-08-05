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

import kotlin.math.abs

/**
 * Which of several resting positions a motion should settle on.
 *
 * The **policy layer**: a pure function of the values it is handed, with no physics in it and no
 * knowledge of what will be done with its answer. It runs entirely above the unit boundary, in
 * value units, and never meets a sampler.
 *
 * ## Why it takes a candidate rather than a gesture
 *
 * Question 2 of Sprint 06B.3 asked whether the gesture velocity belongs here. It does not, and the
 * argument is not about API taste.
 *
 * A signature carrying a velocity the rule never reads cannot be witnessed: the natural witness for
 * *the policy returns the specified target* is a policy that ignores the velocity, and that witness
 * would **be** the correct policy. RULE-015 would be unsatisfiable rather than inconvenient.
 *
 * Reading the velocity means projecting it, and a projection is a physics model — which would
 * define this layer in terms of one family's physics, the specialisation Task 1 had just finished
 * paying to remove from the integration assertion. Pushing the projection down into `SnapFactory`
 * only moves the ownership: the factory would then hold a rule no contract observes.
 *
 * So the projection stays upstream and its result arrives here as [candidate]. `from` leaves for
 * the same reason the velocity does — nearness is measured from the candidate, so `from` has no
 * part in the rule.
 *
 * **Named gap, recorded rather than implied:** nothing asserts that whoever produces [candidate]
 * implements the intended projection. That step sits above all three evidence layers, in a caller
 * that owns a gesture, which Sprint 06B.3 does not have. See §7 of the motion contract.
 */
object TargetSelectionPolicy {

    /**
     * The target nearest [candidate], with exact ties resolved to the lower one.
     *
     * ## The tie-break is about determinism, not about *lower*
     *
     * Two callers holding the same targets in different orders must select the same target: the
     * answer is a function of the **set**, and the order a list happens to arrive in is the
     * caller's accident. *Lower wins* is simply the cheapest intrinsic rule that guarantees it.
     *
     * That is why this is a loop and not `targets.minByOrNull { abs(it - candidate) }`, which is
     * what it would otherwise be. `minByOrNull` returns the first minimum **in list order**, so it
     * agrees with this on every input where no two targets are equidistant — almost all of them —
     * and disagrees exactly where the answer would otherwise be undefined.
     * `TargetSelectionPolicyTest` keeps that version as a witness for precisely this property.
     *
     * @throws IllegalArgumentException if [targets] is empty, or if [candidate] is not finite. A
     *     NaN candidate is rejected rather than tolerated: every distance from it is NaN and every
     *     comparison between NaNs is false, so the loop below would fall through to whichever
     *     target came first and quietly break the order-independence this promises. `SnapSpec`
     *     already refuses an empty target list at construction; a caller assembling one by hand
     *     never reaches that constructor, so it is refused here too.
     */
    fun nearest(candidate: Float, targets: List<Float>): Float {
        require(targets.isNotEmpty()) {
            "a selection from no targets has no answer to give"
        }
        require(candidate.isFinite()) {
            "a candidate of $candidate has no nearest target; every distance from it is undefined"
        }
        var selected = targets[0]
        var selectedDistance = abs(selected - candidate)
        for (target in targets) {
            val distance = abs(target - candidate)
            if (distance < selectedDistance || (distance == selectedDistance && target < selected)) {
                selected = target
                selectedDistance = distance
            }
        }
        return selected
    }
}
