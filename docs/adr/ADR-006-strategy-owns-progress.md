# ADR-006 — `ExecutionTimeline` owns elapsed time; `AnimationStrategy` owns progress

**Status:** accepted · 2026-08-02 · Sprint 06A

> **Amended by Sprint 06A.5.** `AnimationStrategy` is now `MotionSampler` and it returns a
> `MotionSample` rather than exposing `progress` and `easedProgress`. The split this ADR
> describes — `ExecutionTimeline` owns elapsed time, the sampler owns motion — is unchanged and
> is what 06A.5 makes clearer. Read `progress` below as `MotionSample.value`.

## Context

The sprint brief named a single class `TimelineRunner` and gave it the whole job: take a
`FrameTime`, work out elapsed time, and return progress.

That works only while every animation is time-driven. A spring has no
`elapsed → progress` mapping at all — its progress is the result of integrating a force from
the previous state. Leaving progress inside `ExecutionTimeline` meant Sprint 06B would have to
open and modify it, which is the outcome Sprint 06A exists to prevent.

## Decision

Split the responsibility at the point where the two models diverge:

```
FrameTime ──► ExecutionTimeline ──► elapsedNanos ──► AnimationStrategy ──► progress
              (execution time)                    (Timed | Spring | Decay | Snap | Fling)
```

`ExecutionTimeline` owns the elapsed-time accounting of one execution — origin, pause shifting,
seek — and nothing else. `AnimationStrategy`, declared in `aurora.sdk.animation`, turns
elapsed time into progress:

```kotlin
interface AnimationStrategy {
    val progress: Float        // unshaped, 0..1
    val easedProgress: Float   // after shaping; equal to progress when no curve applies
    val isFinished: Boolean
    fun advance(elapsedNanos: Long, deltaNanos: Long)
    fun reset()
    fun seekTo(progress: Float)
}
```

Two progress values rather than one, so the interpolator is applied **inside** the strategy.
The alternative — the handle applying `spec.interpolator` itself — would force it to branch
on the spec kind, since a `PhysicsSpec` has no interpolator. That branch is exactly what
ADR-002 removed one level up. `value` is therefore always
`animation.valueAt(strategy.easedProgress)`, with no `when` anywhere in the engine.

Sprint 06A implements exactly one strategy: `TimedStrategy`, where `progress` is
`timeline.progressAt(elapsed)` and `easedProgress` is `interpolator.transform(progress)`.
Sprint 06B adds
`SpringStrategy`, `DecayStrategy`, `SnapStrategy` and `FlingStrategy` as new files.

The interface is allocation-free by design: `advance()` returns nothing and the results are
read from properties, rather than returning a result object that would be allocated once per
animation per frame.

## Alternatives considered

**Keep progress in `ExecutionTimeline` and branch inside it.** Every new solver edits the same
class, which becomes a `when` over animation kinds — the shape that ADR-002 rejected one
level up.

**Return a `StrategyResult` data class from `advance()`.** Cleaner to read, but allocates per
animation per frame. At 120Hz with twenty animations that is 2400 short-lived objects a
second on a path that runs during every gesture.

## Consequences

- Sprint 06B adds files and touches no existing engine class.
- The engine never knows which kind of animation it is running.
- The class was called `TimelineRunner` in the sprint brief and is renamed here. After this
  split it neither holds a `Timeline` nor computes progress, so `TimelineRunner.advanceTo()`
  would leave a reader six months from now asking *which timeline?* — the honest answer being
  *none*. `ExecutionTimeline` says what it is: the time base of one execution. Renaming costs
  nothing today because no code depends on it, and stops being free the moment any does.
- Mutable state now has exactly one legitimate home. RULE-009 says so explicitly: state lives
  in an `AnimationStrategy`, never in an `Interpolator` and never in a design token, so
  `MotionTokens` and `Interpolator` stay immutable data.
