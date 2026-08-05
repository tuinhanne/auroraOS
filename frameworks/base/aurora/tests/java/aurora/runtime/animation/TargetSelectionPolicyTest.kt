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
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Sprint 06B.3 Task 2: the policy layer, and deliberately not the integration layer.
 *
 * Every property here is a property of a pure function of `(candidate, targets)`. None crosses a
 * unit boundary, builds a pipeline or touches a sampler, so none belongs in `IntegrationContract` —
 * putting them there would dissolve the distinction that layer was created to make.
 *
 * ## The witnesses are why the signature is what it is
 *
 * Question 2 was settled by this file before it was written. The plan's signature carried a
 * `gestureVelocity` the rule never used, and the natural witness for *the policy returns the
 * specified target* is **a policy that ignores the gesture velocity** — which under that signature
 * is the correct policy. The witness set was empty, so RULE-015 was not merely awkward but
 * unsatisfiable, and the signature lost the parameter rather than the property losing its witness.
 *
 * What survives is a diagonal: three properties, three witnesses, each red at exactly one.
 *
 * | witness | ∈ targets | nearest | order-independent |
 * |---|---|---|---|
 * | returns the candidate | **red** | green | green |
 * | chooses the farthest | green | **red** | green |
 * | breaks ties by list order | green | green | **red** |
 *
 * A witness red at two properties would leave one of them unwitnessed by anything specific to it,
 * which is how a property comes to be guarded only by its neighbours.
 */
class TargetSelectionPolicyTest {

    /** What the three properties are phrased over, so a witness can be substituted for the real one. */
    private fun interface Selection {
        fun select(candidate: Float, targets: List<Float>): Float
    }

    private val realPolicy = Selection { candidate, targets ->
        TargetSelectionPolicy.nearest(candidate, targets)
    }

    // ---------------------------------------------------------------------------------------
    // The properties
    // ---------------------------------------------------------------------------------------

    /**
     * A selection returns one of the values it was given.
     *
     * The weakest of the three and the one that has to hold first: a rule that returns the nearest
     * *position* rather than the nearest *target* satisfies everything else here and still hands
     * the animation somewhere it was never allowed to stop.
     */
    private fun assertSelectsAMember(what: String, selection: Selection) {
        for (case in CASES) {
            val selected = selection.select(case.candidate, case.targets)
            if (case.targets.none { it == selected }) {
                fail(
                    "$what selected $selected from ${case.targets} for a candidate of " +
                        "${case.candidate}, which is not one of them"
                )
            }
        }
    }

    /** No target lies closer to the candidate than the one selected. */
    private fun assertSelectsTheNearest(what: String, selection: Selection) {
        for (case in CASES) {
            val selected = selection.select(case.candidate, case.targets)
            val distance = abs(selected - case.candidate)
            val closest = case.targets.minOf { abs(it - case.candidate) }
            if (distance > closest + TOLERANCE) {
                fail(
                    "$what selected $selected for a candidate of ${case.candidate}, which is " +
                        "$distance away, while ${case.targets} contains one $closest away"
                )
            }
        }
    }

    /**
     * The same targets in a different order select the same target.
     *
     * The property the tie-break exists for. *Lower wins* is arbitrary in itself; what is not
     * arbitrary is that the answer must be a function of the **set**, and list order is the
     * caller's accident. A rule that resolves ties by position is correct on every input where no
     * two targets are equidistant, which is almost all of them — so this is the only property that
     * can catch it.
     */
    private fun assertIndependentOfListOrder(what: String, selection: Selection) {
        for (case in CASES) {
            val forwards = selection.select(case.candidate, case.targets)
            val backwards = selection.select(case.candidate, case.targets.reversed())
            if (forwards != backwards) {
                fail(
                    "$what selected $forwards from ${case.targets} and $backwards from the same " +
                        "targets reversed, for a candidate of ${case.candidate}"
                )
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // The real policy
    // ---------------------------------------------------------------------------------------

    @Test
    fun theSelectedTargetIsAlwaysOneOfTheTargets() =
        assertSelectsAMember("TargetSelectionPolicy", realPolicy)

    @Test
    fun theSelectedTargetIsTheNearestToTheCandidate() =
        assertSelectsTheNearest("TargetSelectionPolicy", realPolicy)

    @Test
    fun theSelectionDoesNotDependOnTheOrderTheTargetsArrivedIn() =
        assertIndependentOfListOrder("TargetSelectionPolicy", realPolicy)

    @Test
    fun anExactTieResolvesToTheLowerTarget() {
        // Stated as its own test rather than left to the order-independence property, which would
        // be satisfied by any deterministic rule. This pins which one.
        assertEquals(0f, TargetSelectionPolicy.nearest(5f, listOf(0f, 10f)), 0f)
        assertEquals(0f, TargetSelectionPolicy.nearest(5f, listOf(10f, 0f)), 0f)
        assertEquals(-10f, TargetSelectionPolicy.nearest(0f, listOf(10f, -10f)), 0f)
    }

    @Test
    fun aSingleTargetIsSelectedWhereverTheCandidateIs() {
        assertEquals(42f, TargetSelectionPolicy.nearest(-1000f, listOf(42f)), 0f)
        assertEquals(42f, TargetSelectionPolicy.nearest(1000f, listOf(42f)), 0f)
    }

    @Test
    fun anEmptyTargetListIsRejected() {
        try {
            TargetSelectionPolicy.nearest(0f, emptyList())
            fail("a selection from no targets has no answer to give")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun aCandidateThatIsNotFiniteIsRejected() {
        // Not tidiness. Every distance from a NaN candidate is NaN, every comparison between them
        // is false, and the loop would fall through to whichever target happened to be first -
        // silently breaking the one property this policy promises that a caller cannot check.
        for (candidate in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            try {
                TargetSelectionPolicy.nearest(candidate, listOf(0f, 10f))
                fail("a candidate of $candidate cannot select a nearest target")
            } catch (expected: IllegalArgumentException) {
                // expected
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // The witnesses (RULE-015)
    // ---------------------------------------------------------------------------------------

    /** Returns where the gesture was heading rather than a place it is allowed to stop. */
    private val selectionReturningTheCandidate = Selection { candidate, _ -> candidate }

    /** A selection with the comparison inverted. Order-independent, and still wrong. */
    private val selectionChoosingTheFarthest = Selection { candidate, targets ->
        var best = targets[0]
        for (target in targets) {
            val d = abs(target - candidate)
            val bestDistance = abs(best - candidate)
            if (d > bestDistance || (d == bestDistance && target < best)) best = target
        }
        best
    }

    /**
     * `minByOrNull`, which is what this policy would have been written as.
     *
     * It returns the first minimum **in list order**, so it is correct on every input where no two
     * targets are equidistant from the candidate.
     *
     * ## Its silence is not the prediction's silence
     *
     * This witness is silent unless a tie occurs, which invites reading it as a third instance of
     * the degeneracy recorded before Task 2. It is not. That prediction is about a *normalising
     * quantity* falling to 1, where the normalisation becomes the identity and performing it is
     * indistinguishable from skipping it. Nothing is normalised here, and a tie is not an identity
     * — it is a place where two answers exist and the rule declines to say which. Counting this as
     * a confirmation would inflate the pattern with a case that does not share its mechanism, which
     * is the failure mode a prediction written in advance exists to prevent.
     */
    private val selectionBreakingTiesByListOrder = Selection { candidate, targets ->
        targets.minByOrNull { abs(it - candidate) }!!
    }

    @Test
    fun aSelectionThatReturnsTheCandidateIsCaughtByMembershipAlone() {
        assertRejects("selectionReturningTheCandidate") {
            assertSelectsAMember("candidate", selectionReturningTheCandidate)
        }
        // Declared red set: membership only. It sits exactly on the candidate, so it is trivially
        // the nearest, and it reads no list order.
        assertSelectsTheNearest("candidate", selectionReturningTheCandidate)
        assertIndependentOfListOrder("candidate", selectionReturningTheCandidate)
    }

    @Test
    fun aSelectionThatChoosesTheFarthestIsCaughtByNearnessAlone() {
        assertRejects("selectionChoosingTheFarthest") {
            assertSelectsTheNearest("farthest", selectionChoosingTheFarthest)
        }
        // Declared red set: nearness only. It returns a real target and breaks its own ties by
        // value, so neither of the other two has anything to say.
        assertSelectsAMember("farthest", selectionChoosingTheFarthest)
        assertIndependentOfListOrder("farthest", selectionChoosingTheFarthest)
    }

    @Test
    fun aSelectionThatBreaksTiesByListOrderIsCaughtByOrderIndependenceAlone() {
        assertRejects("selectionBreakingTiesByListOrder") {
            assertIndependentOfListOrder("listOrder", selectionBreakingTiesByListOrder)
        }
        // Declared red set: order-independence only. Every target it returns is a member and is
        // *a* nearest; it simply picks the wrong one of two equally near.
        assertSelectsAMember("listOrder", selectionBreakingTiesByListOrder)
        assertSelectsTheNearest("listOrder", selectionBreakingTiesByListOrder)
    }

    @Test
    fun theTieBreakingWitnessIsSilentWhereNoTwoTargetsAreEquidistant() {
        // Asserted rather than remembered, on the same principle as the travel-of-one silence in
        // SpringPipelineTest: a blind spot left in a comment is a witness that an unrelated edit to
        // the case table can disarm without anyone noticing.
        val noTies = CASES.filter { case ->
            val distances = case.targets.map { abs(it - case.candidate) }
            distances.distinct().size == distances.size
        }
        for (case in noTies) {
            assertEquals(
                TargetSelectionPolicy.nearest(case.candidate, case.targets),
                selectionBreakingTiesByListOrder.select(case.candidate, case.targets),
                0f,
            )
        }
    }

    private fun assertRejects(what: String, body: () -> Unit) {
        var rejected = false
        try {
            body()
        } catch (expected: AssertionError) {
            rejected = true
        }
        if (!rejected) fail("$what should have been rejected by the policy properties, and was not")
    }

    private data class Case(val candidate: Float, val targets: List<Float>)

    private companion object {
        /**
         * Shared by every property so the diagonal above means something: a witness green at a
         * property is green over the same inputs that made another one red.
         *
         * Ties are present deliberately, and so are candidates outside the span of the targets —
         * a gesture may well project past the last snap point, and the nearest target is then an
         * endpoint rather than an interior one.
         */
        private val CASES = listOf(
            Case(5f, listOf(0f, 10f)),          // exact tie
            Case(0f, listOf(10f, -10f)),        // exact tie, straddling zero
            Case(4f, listOf(0f, 10f)),          // nearest is the lower
            Case(6f, listOf(0f, 10f)),          // nearest is the higher
            Case(-50f, listOf(0f, 10f, 20f)),   // candidate below every target
            Case(500f, listOf(0f, 10f, 20f)),   // candidate above every target
            Case(11f, listOf(0f, 10f, 20f)),    // interior, unequal spacing to either side
            Case(10f, listOf(0f, 10f, 20f)),    // candidate exactly on a target
            Case(-3.5f, listOf(-10f, -1f)),     // negative targets
        )

        private const val TOLERANCE = 1e-6f
    }
}
