# ADR-002 — `AnimationSpec` is sealed, with a timed and a physics branch

**Status:** accepted · 2026-08-02 · Sprint 06A

## Context

Sprint 06A must not write a solver, yet it must not have to be modified when Sprint 06B
writes five of them.

The obstacle is that spring, decay and fling animations have **no duration**. They run until
their energy is spent, and how long that takes depends on the velocity they were handed.
`Timeline` — duration, delay, repeat, reverse — cannot describe them.

## Decision

`AnimationSpec` is a sealed interface with two branches:

```kotlin
sealed interface AnimationSpec

data class TimedSpec(timeline: Timeline, interpolator: Interpolator) : AnimationSpec

sealed interface PhysicsSpec : AnimationSpec {
    val initialVelocity: Float
    val restVelocity: Float
    val restDelta: Float
}
```

Sprint 06A implements only `TimedSpec`, through `TimedStrategy`. `PhysicsSpec` and its
subtypes (`SpringSpec`, `DecaySpec`, `SnapSpec`) are declared but have no strategy; the
engine rejects them with a message naming Sprint 06B.

`BezierSpec` is **not** created. A Bézier curve is an `Interpolator`, not a kind of
animation: `TimedSpec(timeline, bezierInterpolator)` expresses it. A separate spec would give
`TimedSpec` two mutually exclusive fields and two code paths doing one job.

## Alternatives considered

**Give everything a `Timeline`,** estimating a spring's settle time up front. When such a
spring is interrupted, the new velocity implies a new settle time, so the `Timeline` must be
replaced mid-flight — which means `ExecutionTimeline` must accept a replacement and the state
machine needs a `RETARGETING` state. Sprint 06B would be modifying the engine, which is the
one outcome this sprint exists to prevent.

**Make `Interpolator` the only abstraction,** with a spring as an interpolator holding
internal velocity. That makes `Interpolator` stateful, so `seek()` and `restart()` stop being
pure and `seek(0.5)` twice can return two different values. It breaks RULE-009 directly.

## Consequences

- 06B adds solver classes and one `when` branch; no existing type changes.
- The state machine, handle, registry and driver never learn which branch they are running.
- Physics specs are visible in the SDK before they work. This is deliberate: it fixes their
  shape while it is still free to change, and the failure is loud and names the sprint.
- `seekTo()` on `AnimationStrategy` may throw `UnsupportedOperationException` for physics
  strategies. Seeking a spring is not well defined and 06B will decide what, if anything, it
  should mean.
- `PhysicsSpec`'s three properties are expressed in normalised progress (0..1), not in the
  animation's value units. A damped spring integrated in progress space has the same stiffness,
  damping and settle time as one integrated in value units — substituting
  `x = from + (to - from) * p` into `x'' = -k(x - target) - c * x'` cancels the `(to - from)`
  factor, leaving only velocity to be scaled. That is what lets a solver be built from the spec
  alone, with no knowledge of `from` and `to`, which keeps `strategyFor(spec)` single-parameter
  and Sprint 06B additive rather than a change to `AnimationHandleImpl`.
- **Superseded in part by Sprint 06A.5.** This ADR justified physics rejecting `seekTo` by saying
  a spring's position is the result of integrating from its previous state. For a closed-form
  spring that is false — its position is a function of elapsed time. The real obstacle was that
  progress is not injective for an overshooting spring: 0.9 occurs at three different times, so
  `seek(0.9f)` has no single answer. Sprint 06A.5 removes the question by seeking in elapsed
  time, which always has one. The decision this ADR records — a sealed spec with a timed and a
  physics branch — is unaffected.

## Left open for Sprint 06B

Two questions this decision raises and does not answer. Both were found in review of Sprint 06A
and are recorded here rather than in someone's memory, because they are cheap to answer now and
expensive to discover halfway through a solver.

**What does a decay normalise against?** The cancellation argument above needs a `(to - from)`
to cancel. A spring has one: `to` is a real target the caller already holds. A decay has no
target at all — where it comes to rest is an *output* of the physics, a function of the initial
velocity and the friction, not an input. So `to` cannot mean "where this will stop" without
being circular. The workable reading is that `to` becomes an arbitrary reference distance and the
true resting position falls out wherever `easedProgress` lands, which the design already permits
because `Animation.valueAt` does not clamp and overshoot survives. That reading is plausible but
untested, and it is not what `PhysicsSpec`'s documentation currently describes: the worked
example is a spring, and only a spring.

**What happens when `from == to`?** Nothing forbids it. `Animation` validates only that the
bounds are finite, and the caller-side conversion `PhysicsSpec` prescribes — divide a measured
velocity by the distance the animation spans — is a division by zero for a zero-range animation.
No code performs that division yet, so nothing is broken today. `AnimationService.springTo` is
where it will first matter.
