# ADR-001 — Split animation across `sdk` and `runtime`

**Status:** accepted · 2026-08-02 · Sprint 06A
**Introduces:** RULE-010

## Context

The Sprint 06A brief placed every animation class in `aurora.runtime.animation`. But
`aurora.sdk` may not import `aurora.runtime` (RULE-001), and `AnimationService` — the
contract that Volume, Island, Notification and Gesture will be written against — lives in
`aurora.sdk.service`.

The time tier had already faced and answered the same question in Sprint 05.5b:
`aurora.sdk.time` holds `Duration`, `Timeline`, `FrameTime` and the `AuroraClock` and
`FrameScheduler` seams; `aurora.runtime.time` holds `RealtimeClock`, `TestClock` and the
schedulers.

## Decision

Animation is split the same way.

```
aurora.sdk.animation        data, interfaces, contracts    — the language
aurora.runtime.animation    implementations                — speaking it
aurora.platform.animation   the Android bridge, Sprint 08  — connecting it
```

This is stated as **RULE-010: SDK defines the language, Runtime speaks the language,
Platform connects the language to Android.**

## Alternatives considered

**Everything in `aurora.runtime.animation`** (the brief as written). `AnimationService`
could not then name `AnimationHandle` or `AnimationState` in any signature. Its methods
would have to return `Unit` and be widened later, or the types would be duplicated in
`aurora.sdk`, or RULE-001 would be breached. All three are debt paid in a later sprint at a
higher price.

**Everything in `aurora.sdk.animation`.** `ExecutionTimeline`, `AnimationRegistry` and
`DefaultAnimator` are executable code that Sprint 06B and 06C will change substantially.
Freezing them into the public stable surface is exactly the mixing that RULE-004 separates
tokens from solvers to avoid, and the surface would grow without bound as composition,
physics and gesture integration arrive.

## Consequences

- `aurora.sdk.service.AnimationService` can and does name `AnimationHandle`.
- Sprint 06B adds `SpringStrategy` and friends to `aurora.runtime.animation` with no change
  to the SDK surface.
- Sprint 08 adds `aurora.platform.animation` without either lower layer moving.
- The layout is symmetric with `time`, so a reader who has understood one has understood
  both.
- Cost: two packages instead of one, and a discipline call on each new file about which side
  it belongs to. The test is whether the file executes anything.
