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

import aurora.sdk.animation.Animation
import aurora.sdk.animation.SnapSpec

/**
 * Builds the `Animation` for a snap, and adds no physics of its own.
 *
 * ```
 * TargetSelectionPolicy  →  SpringFactory  →  SpringSampler
 *    (policy)                (integration)     (unchanged since 06B.1)
 * ```
 *
 * Sprint 06B.0 declined to design snap in advance and said what would count as evidence that the
 * abstraction had been found rather than imposed: that 06B.3 turns out to be a target-selection
 * policy and **not a solver at all**. This file is that outcome. It selects, then delegates, and
 * the only arithmetic in it belongs to neither step.
 *
 * ## Why the animation carries a `SpringSpec`
 *
 * Once a target is chosen a snap **is** a spring, so what reaches the engine is a spring, and
 * `AnimationHandleImpl.samplerFor` never sees a `SnapSpec`. That is what lets the solver layer stay
 * untouched for the first time in the 06B sequence — not a shortcut but the claim being tested.
 *
 * A consequence, followed through in Task 4: `SnapSpec.completionMetric` was reached by no
 * pipeline. Sprint 06B.0 left it duplicating `SpringSpec`'s envelope on purpose, pending a third
 * family that might measure rest differently. The third family arrived and turned out to be this
 * one, which measures rest by *being* a spring — so the member had no reader, and `SnapSpec` left
 * the spec hierarchy rather than keeping an envelope, a velocity and an `isFinished` alive for an
 * interface to be satisfied. See ADR-009.
 *
 * ## What this deliberately does not do
 *
 * It does not compute [candidate]. Question 2 settled that the projection from a gesture to a
 * landing position is the caller's, because a factory that owned the rule would hold semantics no
 * contract observes — and the policy below it would then be defined in terms of one family's
 * physics. See the sprint spec, and the **projection provenance** gap it names.
 */
object SnapFactory {

    /**
     * A snap from [from] onto the target of [spec] nearest [candidate].
     *
     * @param candidate where the gesture would have come to rest, in the same value units as
     *     [from]. Not a target and not required to be one; selection is what turns it into one.
     * @param gestureVelocity the speed at release, in [from]'s units per second. It reaches the
     *     spring, not the policy: which target is chosen does not depend on it, and how fast the
     *     motion arrives there does.
     *
     * @throws IllegalArgumentException if the selected target is exactly [from] — a snap already
     *     resting on its nearest target has no travel, and `SpringFactory` refuses it for the same
     *     reason it refuses a spring with nowhere to go. Whether a caller in that position should
     *     animate at all is the caller's question, and it is better asked than silently answered
     *     with a division by zero.
     */
    fun snapTo(
        name: String,
        from: Float,
        candidate: Float,
        gestureVelocity: Float,
        spec: SnapSpec,
    ): Animation {
        val selected = TargetSelectionPolicy.nearest(candidate, spec.targets)
        return SpringFactory.springTo(
            name = name,
            from = from,
            to = selected,
            gestureVelocity = gestureVelocity,
            spring = spec.spring,
            completionThreshold = spec.completionThreshold,
        )
    }
}
