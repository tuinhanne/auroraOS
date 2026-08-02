# ADR-005 — Registry mutations during a frame are deferred to its end

**Status:** accepted · 2026-08-02 · Sprint 06A
**Introduces:** RULE-013

## Context

Listeners run inside `tick()`, and listeners call back into the engine. A completion listener
starts the next animation; a gesture listener cancels one and restarts another. Each of those
mutates the collection the registry is iterating.

Thread safety is the obvious concern and the least interesting one. The real question is
**determinism**: if `play(B)` from inside a listener injected B into the frame already in
progress, whether B advanced on frame N or frame N+1 would depend on where in the listener
order the call happened — and listener order is not something the caller controls.

## Decision

**RULE-013: an execution advances at most once per frame.**

Structural changes made during a tick are queued and applied when the frame ends:

| During frame N | First tick of the new execution |
|---|---|
| `play(B)` from a listener | frame N+1 |
| `restart()` from a listener | frame N+1 |
| `dispose()` from a listener | never |

The registry iterates a snapshot taken at the start of the frame **and** re-checks
`state.isActive` before each handle, so a handle cancelled or disposed by an earlier listener
in the same frame is skipped rather than ticked and then discarded. Listener lists are
dispatched over a copy.

### The `dispose()` contract is separate from its implementation

The **contract**, which is frozen and belongs in the spec:

> After `dispose()` returns, the handle never receives another `tick()`.

The **implementation** is free: removing immediately when called outside a tick, queueing a
`pendingRemove` when called inside one. Neither `pendingRemove` nor `removeImmediately` is
API. The registry can be optimised later — or replaced entirely — without the spec changing,
as long as that one sentence stays true.

## Alternatives considered

**Mutate immediately, always.** Requires a concurrent or copy-on-write collection on the hot
path, and still leaves the ordering question unanswered: an animation added mid-frame either
runs this frame (order-dependent) or does not (deferred by another name).

**Defer removals but apply additions immediately.** Inconsistent: `play()` and `restart()`
would behave differently despite both beginning an execution, and callers would have to know
which.

## Consequences

- Replay is exact. Two engines fed the same frame sequence produce the same output even when
  listeners create and destroy animations, because listener order cannot change *when*
  anything starts.
- A one-frame latency on animations chained from a completion callback. At 60Hz that is
  16ms, invisible, and the price of not having order-dependent behaviour.
- The registry needs `active`, `pendingAdd` and `pendingRemove`, and a `commit()` at frame
  end. This is the standard entity-system shape and is worth the extra state.
