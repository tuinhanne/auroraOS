# ADR-003 — Execution identity is not handle identity

**Status:** accepted · 2026-08-02 · Sprint 06A
**Introduces:** RULE-012

## Context

The sprint contract requires `RUNNING → cancel() → resume()` to fail. It also requires
`restart()`. Those two together force a question the state diagram alone does not answer: is
`CANCELLED` the end of the *object*, or the end of *one run of it*?

Volume Overlay, Dynamic Island, Notification and Control Center all re-run the same short
animation constantly, often interrupting it. If a cancelled handle were dead, every volume
key press would allocate a new one on a hot path, and `restart()` would be nearly useless
because the most common case — a gesture interrupted and immediately restarted — is exactly
cancel-then-restart.

## Decision

`COMPLETED` and `CANCELLED` are terminal states of an **execution**, not of the handle.
`DISPOSED` is the only state a handle cannot come back from.

- `resume()` is legal only from `PAUSED`. From `COMPLETED` or `CANCELLED` it throws.
- `restart()` is legal from `COMPLETED` and `CANCELLED`, returning the handle to `SCHEDULED`
  and beginning a new execution.
- Each execution has a monotonically increasing `executionId`, exposed on the handle.
- Every listener callback carries the `executionId` it belongs to.

Stated as **RULE-012: execution identity is not handle identity. The handle is stable;
executions are ephemeral.**

## Alternatives considered

**Both `COMPLETED` and `CANCELLED` terminal.** The simplest rule to state, and impossible to
misread. But it allocates a handle per gesture and makes `restart()` dead API.

**`COMPLETED` restartable, `CANCELLED` terminal.** Forbids precisely the most frequent case
in gesture-driven motion: interrupt, then start again.

## Consequences

- One `AnimationHandle` can be reused indefinitely by a UI component. No allocation on the
  volume/notification hot path.
- `resume()` and `restart()` have genuinely different meanings — continue this execution
  versus begin another — rather than being near-synonyms.
- `executionId` is what makes the distinction checkable rather than merely described: a
  listener registered during execution 3 can see it is being handed an event from
  execution 4 and ignore it. This class of bug (a stale callback landing on a new run) is
  otherwise extremely hard to find, because nothing about the callback says which run it
  came from.
- Cost: every callback carries one extra `Long`, and implementations must remember to check
  it when they hold state across runs.
- **A handle cannot be pointed at a new target.** [restart] begins a new execution of the same
  `Animation`, whose `from` and `to` are immutable. Redirecting mid-flight means building a new
  `Animation` and a new handle.

  That is the right trade for the case this ADR is about. A volume key press or a notification
  arriving is a human-scale event a few times a second, and a small immutable data object there
  costs nothing — the allocation RULE-012 eliminates is the per-*frame* one. Handle reuse is for
  animations whose endpoints do not change between runs, and for those it works.

  The case it does not serve is a target that moves every frame, such as a value chasing a
  dragged finger. ADR-002 already declined that for Sprint 06A on physics grounds — retargeting a
  spring implies a new settle time, a swapped timeline and a retargeting state in the machine —
  and this is the same decision seen from the API side. If Sprint 06B or 07 needs it, the shape
  it takes is `Animator.retarget(handle, animation)` rather than a mutable `AnimationHandle`,
  because a handle whose animation can change underneath a listener would make `executionId`
  insufficient to tell one run from another, which is the thing this ADR exists to guarantee.
