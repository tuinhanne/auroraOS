# Sprint 06A — Animation Architecture (Design)

**Status:** approved 2026-08-02
**Supersedes:** nothing
**Precedes:** Sprint 06B (physics solvers), Sprint 08 (Android bridge)

---

## Goal

Build the animation engine that Volume Overlay, Dynamic Island, Launcher, Notification,
Quick Settings and Gesture will all use — and fix its API and its lifecycle now, so that
none of those features, and no later sprint, has to modify the engine to be added.

The deliverable of this sprint is therefore **an API and a state machine**, not motion.
No solver is written. Nothing moves on screen.

## Non-goals

Explicitly out of scope, and any of them appearing in the diff is a defect:

Spring solver · cubic Bézier solver · decay · fling · snap · any physics ·
`Choreographer` · any `android.*` · `View` · overlay · rendering.

---

## Why the engine is designed before the motion

An animation engine has two halves that fail in completely different ways. Timing bugs
(a transition that starts a frame late, a pause that loses 16ms, a repeat that skips its
boundary) and physics bugs (a spring that overshoots, a decay that never settles) look
identical from the outside: something on screen moved wrong.

Separating them is what makes either debuggable. This sprint builds the half that can be
proven exactly — given this sequence of frames, the engine is in this state and reports
this progress — so that when Sprint 06B introduces physics, a wrong pixel can be attributed
to the solver rather than searched for across both halves.

It is also the half that is expensive to change later. A solver is replaceable: it is one
class behind an interface. A lifecycle is not: every caller written against it encodes its
assumptions, and by Sprint 12 there will be six subsystems doing so.

---

## Architecture

### Layer split

Animation follows exactly the three-tier shape that Sprint 05.5b established for time, for
the same reasons and with the same boundaries.

```
aurora.sdk.animation          the language: data, interfaces, contracts
        ↓
aurora.runtime.animation      the machine: implementations, portable, host-testable
        ↓
aurora.platform.animation     the Android bridge — Sprint 08, NOT created in 06A
```

This is **RULE-010**. The decisive consequence is that `aurora.sdk.service.AnimationService`
can name `AnimationHandle` and `AnimationState` in its signatures. Had the whole engine
lived in `aurora.runtime`, it could not — `aurora.sdk` may not import `aurora.runtime`
(RULE-001) — and every service contract from Sprint 07 onward would have had to either
duplicate the types or breach the layering. See [ADR-001](../adr/ADR-001-animation-layer-split.md).

### File inventory

```
aurora/sdk/java/aurora/sdk/animation/
    Animation.kt              what is being animated (data)
    AnimationSpec.kt          sealed: TimedSpec | PhysicsSpec (data)
    AnimationState.kt         the seven states (enum)
    AnimationStrategy.kt      elapsed → progress (interface)
    AnimationHandle.kt        the public control surface (interface)
    AnimationListener.kt      callbacks, all defaulted (interface)
    Animator.kt               what feature code calls (interface)
    AnimationController.kt    where time enters the engine (interface)
    Interpolator.kt           progress → shaped progress (fun interface + LINEAR)

aurora/runtime/java/aurora/runtime/animation/
    DefaultAnimator.kt
    DefaultAnimationController.kt
    AnimationStateMachine.kt  pure: (state, event) → state
    ExecutionTimeline.kt         execution-time accounting
    TimedStrategy.kt          the only AnimationStrategy in 06A
    AnimationRegistry.kt      insertion-ordered, deferred mutation
    AnimationDriver.kt        one FrameCallback → one FrameTime → registry
    AnimationHandleImpl.kt    binds the five above into one handle

aurora/tests/java/aurora/
    sdk/animation/AnimationApiTest.kt
    runtime/animation/AnimationStateMachineTest.kt
    runtime/animation/AnimationLifecycleTest.kt
    runtime/animation/AnimationRegistryTest.kt
    runtime/animation/AnimationDeterminismTest.kt
```

### What is deliberately not created

| Not created | Because |
|---|---|
| `Timeline` | `aurora.sdk.time.Timeline` already exists, is stateless, and already does delay, duration, repeat, reverse and seek |
| `AnimationClock` | `AuroraClock` and `FrameTime` already cover this, and the engine takes no clock at all (see RULE-008 below) |
| `AnimationScheduler` | `aurora.sdk.time.FrameScheduler` is the seam; a second one would split the responsibility |
| `BezierSpec` | A Bézier is an `Interpolator`, not a kind of animation. `TimedSpec(timeline, bezierInterpolator)` covers it; a separate spec would give `TimedSpec` two mutually exclusive fields |
| `aurora.platform.animation/` | Sprint 08. An empty package now is a guess about what Sprint 08 needs |

`aurora.runtime.time.TimelineDriver` is **left untouched**. It belongs to the time tier, it
works, it is tested, and it implements one-driver-per-timeline — the opposite of RULE-011.
The animation engine does not build on it. If it becomes dead weight after 06B, it is
deleted in its own commit, not folded into this sprint's diff.

---

## The state machine

The most important artefact of this sprint. Every animation Aurora ever runs passes through
it.

```
                    ┌──────┐
                    │ IDLE │  animator.create(animation)
                    └──┬───┘
                 play()│
                    ┌──▼──────┐
        ┌──────────►│SCHEDULED│  registered, has not yet received a tick
        │           └──┬───▲──┘
        │        tick  │   │ resume()  when pausedFrom == SCHEDULED
        │           ┌──▼───┴──┐
        │           │ RUNNING │◄─────── resume()  when pausedFrom == RUNNING
        │           └──┬───┬──┘                 │
        │      pause() │   │ strategy finished  │
        │           ┌──▼─┐ │                ┌───┴──────┐
        │           │PAUS│ └───────────────►│COMPLETED │
        │           │ ED │                  └───┬──────┘
        │           └──┬─┘                      │
        │      cancel()│                        │
        │        ┌─────▼─────┐                  │
        └────────┤ CANCELLED │                  │
      restart()  └───────────┘                  │
        └────────────── restart() ──────────────┘

        any state ── dispose() ──► DISPOSED      (the only irreversible state)
```

### Legality

|  | IDLE | SCHEDULED | RUNNING | PAUSED | COMPLETED | CANCELLED | DISPOSED |
|---|---|---|---|---|---|---|---|
| `play()`    | ✅ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ |
| `pause()`   | ✗ | ✅ | ✅ | no-op | ✗ | ✗ | ✗ |
| `resume()`  | ✗ | ✗ | ✗ | ✅ | ✗ | ✗ | ✗ |
| `cancel()`  | ✅ | ✅ | ✅ | ✅ | no-op | no-op | ✗ |
| `restart()` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✗ |
| `seek(p)`   | ✗ | ✅ | ✅ | ✅ | ✗ | ✗ | ✗ |
| `dispose()` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | no-op |

Three rules cover the whole table:

1. **Queries never throw.** `state`, `isRunning`, `progress`, `value`, `executionId` and
   `animation` are readable in every state including `DISPOSED`. They read volatile fields,
   take no lock, and trigger no lazy computation.
2. **`dispose()` and `cancel()` are idempotent.** Teardown paths run more than once far more
   often than anyone expects, and a throwing second call turns a harmless duplicate into a
   crash. This is the reasoning `Disposable` already records.
3. **`play()`, `resume()`, `restart()` and `seek()` throw `IllegalStateException`** when the
   current state does not permit them (RULE-003), and every call after `dispose()` throws
   except the queries and `dispose()` itself.

### Invariant: one running execution per handle

> **A handle never has more than one execution in `RUNNING` at the same time.**

Not for a frame, not for an instant. `restart()` on a running handle ends execution N and
begins execution N+1; there is no window in which both are live, because the new execution's
first tick is deferred to the next frame (RULE-013) and the old one leaves `RUNNING` in the
same call that creates the new `executionId`.

This follows from RULE-013, but is stated separately because it is the form a test can assert
directly: drive a handle through `restart()` mid-frame and check that no frame ever produced
two `onUpdate` callbacks for the same handle. Without it, the consequence of RULE-013 that
matters most — a value can never be computed twice for one handle in one frame — would have
to be re-derived by whoever writes that test.

### Three decisions inside the table

**`pause()` is legal from `SCHEDULED`.** If it were not, whether `handle.play(); handle.pause()`
threw would depend on whether a frame happened to arrive in between — API behaviour varying
with machine load, which is precisely what RULE-009 exists to forbid. `PAUSED` records
`pausedFrom` so `resume()` returns to the right state.

**`seek()` is illegal from `COMPLETED` and `CANCELLED`.** `seek` positions a *live* execution;
a finished one has no position to move. Scrubbing a finished animation is spelled
`restart(); pause(); seek(p)`. If a real need for editor-style scrubbing appears, it gets its
own `scrub()` method rather than widening this one.

**`restart()` is legal from `COMPLETED` and `CANCELLED`.** They are terminal states of an
*execution*, not of the handle. Volume, Dynamic Island and Control Center re-run the same
animation constantly; forcing a fresh `animator.play()` each time would allocate on the
hot path and make `restart()` nearly useless. See [ADR-003](../adr/ADR-003-execution-identity.md).

---

## Public API

### `aurora.sdk.animation`

```kotlin
enum class AnimationState {
    IDLE, SCHEDULED, RUNNING, PAUSED, COMPLETED, CANCELLED, DISPOSED;

    /** Receiving, or about to receive, ticks. */
    val isActive: Boolean get() = this == SCHEDULED || this == RUNNING
    /** The execution ended; the handle is still usable (RULE-012). */
    val isResting: Boolean get() = this == COMPLETED || this == CANCELLED
    /** DISPOSED only. The handle is dead. */
    val isTerminal: Boolean get() = this == DISPOSED
}

fun interface Interpolator {
    fun transform(progress: Float): Float

    companion object {
        /** The identity element. Returns its argument; it computes nothing. */
        @JvmField val LINEAR = Interpolator { it }
    }
}

sealed interface AnimationSpec

/** Time decides progress. The only branch Sprint 06A implements. */
data class TimedSpec(
    val timeline: Timeline,                                // aurora.sdk.time.Timeline
    val interpolator: Interpolator = Interpolator.LINEAR,
) : AnimationSpec

/** Energy decides progress. Declared in 06A, implemented in 06B. All three properties are
 *  normalised progress (0..1), not value units -- see ADR-002 Consequences. */
sealed interface PhysicsSpec : AnimationSpec {
    val initialVelocity: Float   // progress/second
    val restVelocity: Float      // progress/second
    val restDelta: Float         // progress
}
// each overrides initialVelocity / restVelocity / restDelta with defaults
data class SpringSpec(val spring: Spring = MotionTokens.SPRING_GENTLE, …) : PhysicsSpec
data class DecaySpec (val friction: Float = 0.5f,                     …) : PhysicsSpec
data class SnapSpec  (val targets: List<Float>,                       …) : PhysicsSpec

data class Animation(
    val name: String,
    val spec: AnimationSpec,
    val from: Float = 0f,
    val to: Float = 1f,
) {
    /** Pure and exact at both endpoints. The entire progress→value mapping. */
    fun valueAt(easedProgress: Float): Float = from * (1f - easedProgress) + to * easedProgress
}
```

### Amendment, 2026-08-02: `TimedSpec.elapsedForProgress`

`handle.seek(p)` has to move `ExecutionTimeline` to a specific elapsed time, and
`AnimationStrategy.seekTo` returns nothing, so the progress→elapsed conversion needs a home.
It goes on `TimedSpec` — pure arithmetic over `Timeline`, so it belongs on the data:

```kotlin
data class TimedSpec(…) : AnimationSpec {
    /** Inverse of Timeline.progressAt. Positions are per iteration, matching it. */
    fun elapsedForProgress(progress: Float): Long {
        val p = progress.coerceIn(0f, 1f)
        // An iteration boundary is ambiguous: progressAt resolves it as the START of the
        // next iteration, not the end of this one. On a timeline with another iteration to
        // come, land one nanosecond inside instead, so progressAt still reports 1.
        if (p == 1f && timeline.durationNanos > 0L &&
            (timeline.isInfinite || timeline.repeatCount > 0)
        ) {
            return timeline.delayNanos + timeline.durationNanos - 1L
        }
        return timeline.delayNanos + (timeline.durationNanos * p.toDouble()).toLong()
    }
}

/** elapsed → progress. The seam Sprint 06B plugs solvers into without touching the engine. */
interface AnimationStrategy {
    /** Unshaped progress, 0..1. For a timed animation, linear in elapsed time. */
    val progress: Float
    /** Progress after shaping. Equal to [progress] when the strategy applies no curve. */
    val easedProgress: Float
    val isFinished: Boolean

    fun advance(elapsedNanos: Long, deltaNanos: Long)
    fun reset()

    /**
     * Optional operation.
     *
     * A physics strategy may reject seeking with UnsupportedOperationException. That is a
     * deliberate part of this design, not a gap: a spring's position is the result of
     * integrating from its previous state, so there is no elapsed time to jump to. See
     * ADR-002. Callers that must support both kinds should offer seeking only when the
     * animation's spec is a TimedSpec.
     */
    fun seekTo(progress: Float)
}

interface AnimationHandle : Disposable {       // isDisposed == (state == DISPOSED)
    val animation: Animation
    val state: AnimationState
    val executionId: Long                       // RULE-012
    val progress: Float                         // == strategy.progress, unshaped, 0..1
    val value: Float                            // == animation.valueAt(strategy.easedProgress)
    val isRunning: Boolean get() = state == AnimationState.RUNNING

    fun play()
    fun pause()
    fun resume()
    fun cancel()
    fun restart()
    fun seek(progress: Float)
    fun addListener(listener: AnimationListener): Disposable
}

interface AnimationListener {
    fun onStateChanged(handle: AnimationHandle, executionId: Long,
                       from: AnimationState, to: AnimationState) {}
    fun onUpdate(handle: AnimationHandle, executionId: Long,
                 progress: Float, value: Float) {}
}

interface Animator {
    /** Returns an IDLE handle, so listeners can be attached before the first frame. */
    fun create(animation: Animation): AnimationHandle
    /** create() + play(). */
    fun play(animation: Animation): AnimationHandle
    fun cancelAll()
    val activeCount: Int
}

interface AnimationController {
    val animator: Animator
    val isRunning: Boolean
    fun start()
    fun stop()

    /** The only legal entry point of time into the animation engine. */
    fun tick(frameTime: FrameTime)
}
```

Both `AnimationListener` methods have empty default bodies. Adding a callback in 06B or 06C
therefore breaks no implementor — the listener interface can grow without the engine's
consumers changing.

`tick()` is public contract rather than a runtime detail because both drivers must enter
through the same door:

```
        Host test  ──►  AnimationController.tick(FrameTime)  ◄──  Android Choreographer
```

That is what turns RULE-009 from a convention into something a test can assert. The
controller rejects a `FrameTime` whose `frameIndex` does not increase — RULE-006's
monotonicity, applied to frames.

---

## Runtime

```
FrameScheduler.postFrame(driver)          one call per frame, only while the registry is non-empty
        │
   AnimationDriver                        builds EXACTLY ONE FrameTime
        │
   DefaultAnimationController.tick(ft)
        │
   AnimationRegistry.tick(ft)             insertion-ordered, iterates a snapshot
        │
   ┌────┴──────┬──────────┐
AnimationHandleImpl  …    …               same frameTimeNanos, same deltaNanos
        │
   ExecutionTimeline.advanceTo(ft)  →  elapsedNanos
        │
   AnimationStrategy.advance(elapsed, delta)  →  progress
        │
   AnimationStateMachine.next(state, event)   →  new state, if any
        │
   dispatch listeners (over a snapshot)
```

### `AnimationStateMachine` — pure

```kotlin
enum class AnimationEvent { PLAY, TICK, PAUSE, RESUME, CANCEL, RESTART, FINISH, DISPOSE }

object AnimationStateMachine {
    fun canTransition(from: AnimationState, event: AnimationEvent): Boolean
    fun next(from: AnimationState, event: AnimationEvent,
             pausedFrom: AnimationState = AnimationState.RUNNING): AnimationState
}
```

No clock, no registry, no listener, no handle — no fields at all. The 7 × 8 transition table
is therefore testable as a pure function, which is why it is the piece with the most
exhaustive test in the sprint.

### `ExecutionTimeline` — execution time only

The runner owns the elapsed-time accounting of **one execution** and nothing else. It does
not compute progress; that belongs to `AnimationStrategy`, so Sprint 06B adds
`SpringStrategy` without touching this class. See [ADR-006](../adr/ADR-006-strategy-owns-progress.md).

- `elapsed = frameTimeNanos − origin`. No clock is read. `origin` comes from the
  `frameTimeNanos` of the execution's first tick, never from `AuroraClock`.
- **A pause is not measured with a clock.** `pause()` records the last `frameTimeNanos` it
  saw. The first tick after `resume()` shifts `origin` by exactly
  `thisFrameNanos − pausedAtFrameNanos`. The animation therefore does not jump, and the
  length of the pause is measured in delivered frames rather than in wall time.
- `pause()` from `SCHEDULED` (no frame seen yet): there is nothing to shift, so the first
  tick after `resume()` simply becomes the execution's first tick.
- `seek(p)` only moves `origin` so that `elapsed == TimedSpec.elapsedForProgress(p)`. Because
  `Timeline.progressAt` is already stateless, seeking needs no further machinery and seeking
  twice to the same value always gives the same result.

**Invariant: one home for the elapsed↔progress mapping.** Both directions belong to `TimedSpec`
— `elapsedForProgress` on it, `progressAt` reached through `spec.timeline`. `ExecutionTimeline`
deals only in elapsed nanoseconds, `AnimationStrategy.seekTo` takes a progress it does not
convert, and `AnimationHandleImpl.seek` orchestrates without computing. A round-trip test
asserts `progressAt(elapsedForProgress(p)) == p`.

The invariant earns its place immediately: a first draft defined progress as spanning the whole
repeated sequence, which reads naturally and is wrong, because `progressAt` counts per
iteration and resets each time round. `seek(0.25f)` on a three-times timeline gave a progress of
0.75. Positions are therefore per iteration, and `progress == 1f` means the end of the first
iteration rather than the end of the sequence.

A second draft got the boundary itself wrong: an iteration boundary is both the end of one
iteration and the start of the next, and `progressAt` resolves it as the start, so landing
exactly on it read back as progress 0 rather than 1 whenever a repeat followed. `elapsedForProgress(1f)`
resolves this by landing one nanosecond inside the iteration when another repeat is still to
come, which `progressAt` reports as 1 within the round trip's `1e-5f` tolerance.

### `AnimationRegistry` — deferred mutation

Structural changes made *during* a tick are queued and applied when the frame ends:

| During a frame | Effect |
|---|---|
| `play(B)` from inside a listener | B is scheduled; its first tick is frame N+1 |
| `restart()` from inside a listener | new `executionId`; the new execution's first tick is frame N+1 |
| `dispose()` from inside a listener | not ticked again, in this frame or any later one |

The reason is determinism, not thread safety. If a listener could inject an animation into
the frame currently being processed, the result would depend on where in the listener order
the injection happened — and listener order is not something a caller controls. This is
**RULE-013**.

The registry iterates a snapshot taken at the start of the frame *and* re-checks
`state.isActive` before each handle, so a handle cancelled or disposed by an earlier
listener in the same frame is skipped rather than ticked and then discarded. Listener lists
are dispatched over a copy, so `addListener` inside `onUpdate` cannot raise
`ConcurrentModificationException`.

**The `dispose()` contract and its implementation are separate.** The contract, which is
frozen, is one sentence: *after `dispose()` returns, the handle never receives another
`tick()`*. Whether the registry removes it immediately or queues a `pendingRemove` is
internal, and may be optimised later without touching this spec.
See [ADR-005](../adr/ADR-005-deferred-registry-mutation.md).

### `AnimationDriver` — one callback, one FrameTime

`AnimationDriver` posts exactly one `FrameCallback` per frame, builds exactly one
`FrameTime`, and hands the same instance to every animation. It **stops posting when the
registry is empty** and resumes when an animation is added, so an idle engine wakes no core
between refreshes. `TimelineDriver` got this for free because each run stopped posting for
itself; a batched driver has to do it deliberately.

---

## Rules introduced

| Rule | Statement | Enforced by |
|---|---|---|
| **RULE-009** | Animation must be deterministic: the same sequence of `FrameTime` values always produces the same result, independent of wall clock, thread and frame rate. All mutable state lives in an `AnimationStrategy`; `Interpolator` and every design token are immutable data with no hidden state. | `arch-test.sh` (`forbid-call-under`) + `AnimationDeterminismTest` |
| **RULE-010** | SDK defines the language, Runtime speaks it, Platform connects it to Android. | `arch-test.sh` (RULE-001 machinery) + review |
| **RULE-011** | Exactly one `FrameTime` is built per frame, and it is an immutable value object shared by reference — never cloned, never mutated. Every animation reads the same instance. | `AnimationDeterminismTest` (coherence check) + `AnimationApiTest` (`FrameTime` immutability) |
| **RULE-012** | Execution identity is not handle identity. The handle is stable; executions are ephemeral. Every callback carries its `executionId`. | `AnimationLifecycleTest` |
| **RULE-013** | An execution advances at most once per frame. | `AnimationRegistryTest` |
| **RULE-014** | An animation callback must never mutate `FrameTime`. | `AnimationApiTest`: reflection asserts every `FrameTime` field is `final` |

**RULE-008 is satisfied structurally, not by discipline.** No class in
`aurora.runtime.animation` accepts an `AuroraClock` through its constructor. What cannot be
reached cannot be read.

### `arch-test.sh` change

RULE-009's hazards are package-specific, while today's `forbid-call` applies to a whole
layer. One new contract key is needed:

```
# runtime.contract
forbid-call-under: Math.random@runtime/java/aurora/runtime/animation
forbid-call-under: java.util.Random@runtime/java/aurora/runtime/animation
forbid-call-under: HashMap@runtime/java/aurora/runtime/animation/AnimationRegistry.kt
forbid-call-under: HashSet@runtime/java/aurora/runtime/animation/AnimationRegistry.kt
```

The hash containers are forbidden **in `AnimationRegistry.kt` only**, because the hazard is
iteration order, not the container. `handleById = HashMap<Long, AnimationHandle>()` in
`DefaultAnimator` is a lookup and is entirely fine. `AnimationRegistry` is the one file where
iteration order becomes observable behaviour, so it is the one file where the ban applies,
and it uses an insertion-ordered list.

**What is honestly not machine-checked:** RULE-010 and RULE-012 rest on tests and review.
A hash container iterated somewhere other than `AnimationRegistry.kt` would not be caught.
This follows the precedent already set in the Aurora README — *"Nothing stops a caller from
writing `16` instead of a token; that part is convention"* — because a checker that
overstates its reach is worse than one that states its limits.

---

## Test plan

| Test | Covers |
|---|---|
| `AnimationApiTest` | Pure SDK types: `AnimationState` predicates, `Animation.valueAt`, `Interpolator.LINEAR` is the identity, spec validation, and the reflection checks for RULE-011 and RULE-014 |
| `AnimationStateMachineTest` | **All 7 × 8 = 56 cells** of the transition table, legal and illegal alike — including the `RUNNING → cancel() → resume() → FAIL` case named in the sprint contract |
| `AnimationLifecycleTest` | play → pause → resume → cancel → restart → dispose; `executionId` increments correctly; a listener from execution N never receives events from execution N+1 (RULE-012); every call after `dispose()` throws; pause loses no elapsed time |
| `AnimationRegistryTest` | RULE-013: `play(B)` inside a listener first ticks on frame N+1; `restart()` mid-frame does not tick twice; `dispose()` mid-frame is not ticked again; **the one-running-execution invariant**: no frame ever produces two `onUpdate` callbacks for the same handle, including across a mid-frame `restart()`; **listener order**: `play(B)` then `play(C)` from callbacks schedule B before C, identically on replay; `cancelAll`, `activeCount` |
| `AnimationDeterminismTest` | RULE-009, six checks: two fresh engines replaying one frame sequence produce **exactly equal** value arrays; 60Hz vs 120Hz vs irregular spacing give the same value at the same elapsed time; a dropped 100ms frame does not drift; three animations started on different frames observe the same `frameTimeNanos` in one tick; seeking twice to the same progress gives the same value; the engine runs with no clock reference in existence |

### Coverage criterion

**Every public declaration in `aurora.sdk.animation` and `aurora.runtime.animation` is named
by at least one host test.** Checked by a grep in the verify script.

A line-count ratio (`test > impl`) was considered and rejected: it passes in 06A and fails in
06B for a physics solver that is correctly dense and thoroughly tested, so it would measure
the wrong thing within one sprint. The grep is a name-presence check and does not measure
branch coverage; it is stated as such rather than dressed up as one.

---

## Collateral change

`aurora.sdk.service.AnimationService` currently declares its own `AnimationHandle`. That
declaration is deleted and the file imports `aurora.sdk.animation.AnimationHandle` instead.
Nothing implements `AnimationService` yet, so this costs nothing today and will never be
this cheap again. `springTo()` remains declared but unimplementable until 06B, which is
harmless for an interface with no implementations.

---

## Exit criteria

Verified by `47-verify-sprint06a.sh`, built on the shape of `46-verify-tiers.sh`.

| Criterion | Check |
|---|---|
| Compile PASS | `m aurora-sdk aurora-runtime aurora-platform` |
| Host Test PASS | `JUnitCore` over the 6 existing classes plus the 5 new ones |
| Animation Lifecycle PASS | `AnimationLifecycleTest` |
| State Machine PASS | `AnimationStateMachineTest`, all 56 cells |
| Timeline PASS | `ExecutionTimeline` elapsed accounting, in `AnimationLifecycleTest` |
| Deterministic PASS | `AnimationDeterminismTest` |
| Architecture PASS | `arch-test.sh`, including the new `forbid-call-under` |
| No Android Dependency | zero `android.` imports under either animation package |
| API coverage | every public declaration named by a test |

---

## Decision records

| ADR | Decision |
|---|---|
| [ADR-001](../adr/ADR-001-animation-layer-split.md) | Split animation across `sdk` and `runtime` rather than placing it in one layer |
| [ADR-002](../adr/ADR-002-sealed-animation-spec.md) | `AnimationSpec` is sealed with a timed and a physics branch |
| [ADR-003](../adr/ADR-003-execution-identity.md) | Execution identity is distinct from handle identity |
| [ADR-004](../adr/ADR-004-single-frame-callback.md) | One frame callback drives the whole engine |
| [ADR-005](../adr/ADR-005-deferred-registry-mutation.md) | Registry mutations during a frame are deferred to its end |
| [ADR-006](../adr/ADR-006-strategy-owns-progress.md) | `ExecutionTimeline` owns elapsed time; `AnimationStrategy` owns progress |
