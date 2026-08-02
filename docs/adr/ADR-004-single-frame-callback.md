# ADR-004 — One frame callback drives the whole engine

**Status:** accepted · 2026-08-02 · Sprint 06A
**Introduces:** RULE-011

## Context

`aurora.runtime.time.TimelineDriver`, written in Sprint 05.5b, gives each running timeline
its own `FrameCallback`, which re-posts itself from inside its own callback. The animation
engine could have been built the same way.

RULE-008 already states why frame time is handed to an animation rather than fetched by it,
and lists *coherence* as one of the three reasons: every animation in a frame must get the
same timestamp, or long parallel transitions drift apart. Per-animation callbacks weaken
that. Each animation builds its own `FrameTime` from its own view of the sequence, so two
animations started one frame apart disagree about `deltaNanos` and `frameIndex` within the
same display frame.

## Decision

`AnimationDriver` posts **exactly one** `FrameCallback` per frame, builds **exactly one**
`FrameTime`, and passes that same instance to every animation through
`AnimationController.tick(frameTime)` → `AnimationRegistry.tick(frameTime)`.

Stated as **RULE-011: exactly one `FrameTime` is built per frame, and it is an immutable
value object shared by reference — never cloned, never mutated.** Its companion,
**RULE-014**, forbids a callback from mutating it; `FrameTime` is a `data class` of `val`s,
so this is enforced by a reflection test asserting every field is `final`.

`FrameScheduler` is reused unchanged — it is already the right seam. No `AnimationScheduler`
is created.

`TimelineDriver` is left untouched in Sprint 06A. It implements the rejected model, but it
works, it is tested, and it belongs to the time tier. Deleting it would drag
`TimeInfrastructureTest` into this sprint's diff for no benefit. If it is still unused after
06B, it is removed in its own commit.

## Alternatives considered

**Per-animation callbacks** (the `TimelineDriver` model). Less code and no registry needed,
but the coherence problem above, plus `cancelAll()` and `activeCount` become bookkeeping
across N independent objects.

**One callback with per-scope registries** keyed by `EventScope`, so closing a window cancels
its animations in one call. Genuinely better once windows exist — but no window exists yet,
so the scope keys would be guessed. Deferred until a caller needs it.

## Consequences

- Twenty animations in a Dynamic Island transition cannot drift apart, by construction.
- `cancelAll()` and `activeCount` are trivial.
- One place builds `FrameTime`, so one place is responsible for frame-index monotonicity.
- The driver must **stop posting when the registry empties** and resume when an animation is
  added, or an idle engine wakes a core every refresh forever. Per-animation drivers got this
  free; a batched driver has to do it on purpose.
