# Sprint 06A.5 — Sampler API Migration (Design)

**Status:** proposed 2026-08-03
**Supersedes:** parts of the Sprint 06A frozen API
**Precedes:** 06B (closed-form samplers), 06C (orchestration), 06D (simulation samplers)

---

> **Animation describes behaviour. Execution describes time. Sampler maps time to motion.**
>
> Read that before anything else here. Every decision below follows from it, and the API only
> reads sensibly to someone holding it. Recorded in full as
> [ADR-007](../adr/ADR-007-animation-model.md).

---

## Goal

Change the shape of the animation engine's API — not its behaviour — before any solver or any
consumer is written against it. No solver ships in this sprint. No physics. No new capability.

The 267 tests that pass today must pass again afterwards. That is the whole success criterion.

## Why now, and why alone

Sprint 06A froze an API that turns out to describe timed animation well and physics badly. The
mismatch is not a detail; it is in the vocabulary:

```kotlin
strategy.progress   // a spring reports 1.18, then 0.95, then 1.03
```

That is not progress. Progress increases. This is a normalised *position* that oscillates, and
calling it progress would make every reader of `AnimationHandle` learn an exception, forever.

Doing it as its own sprint is the same discipline 06A used to separate timing from physics.
After a migration with no new behaviour, 267 green tests mean the API is right. When 06B's first
spring then fails, it can only be the solver — nobody has to ask whether the API broke it.

Doing it *now* is the cheapest it will ever be. There are zero consumers. After 06B there are
five samplers; after 06C, an orchestration layer; after Sprint 09, Volume Overlay and Dynamic
Island. The same change then is not a migration, it is a rewrite.

## Non-goals

Any solver — spring, decay, snap, bezier, fling. Any orchestration. Any change to
`AnimationState`, `AnimationStateMachine`, `AnimationRegistry`, `AnimationDriver` or
`DefaultAnimationController`. Any behavioural change at all.

`AnimationSpec` is touched, in one narrow way: it gains `isFinished(elapsedNanos, sample)`. Its
existing shape — the sealed hierarchy, the subtypes, their fields and validation — is untouched.
That method is added here rather than in 06B because it is where the rest policy has to live once
the sampler stops carrying it, and adding it now is what keeps 06B additive.

---

## The principle this sprint is built on

Three things that have been tangled under one word are separated, and each is given a name that
says what it is:

> **Animation describes behaviour. Execution describes time. Sampler maps time to motion.**

| | Holds | Mutable? | Knows about |
|---|---|---|---|
| `Animation` | what to animate, from where to where | no | nothing |
| `ExecutionTimeline` | elapsed, pause, seek | yes | no animation, no sampler |
| `MotionSampler` | how time becomes motion | implementation's business | no execution |

None of the three knows the other two. That is what makes each testable alone, and it is the
reason a simulation sampler in 06D can hold whatever state it likes without any of it reaching
the SDK.

Recorded as [ADR-007](../adr/ADR-007-animation-model.md).

---

## The SDK after this sprint

### `MotionSample` — new

```kotlin
/**
 * Where a motion is and how fast it is going, at one instant.
 *
 * @param value normalised position. May leave 0..1 — an overshooting spring is supposed to —
 *     and may decrease, which is why it is not called progress.
 * @param velocity rate of change of [value] with respect to time, in normalised units per
 *     second. Each sampler supplies this by whatever method suits its model.
 */
data class MotionSample(
    val value: Float,
    val velocity: Float,
)
```

**There is deliberately no `finished`.** Whether a motion has ended is a *policy*, not a
measurement. A spring is done when it is within `restDelta` of its target and slower than
`restVelocity` — and both of those live in `SpringSpec`, not in the sampler. A sampler that
reported `finished` would be reading a rule it does not own, and every alternative spring solver
in 06D would have to re-implement the same rule identically. See below for where the rule lives.

### `MotionSampler` — replaces `AnimationStrategy`

```kotlin
interface MotionSampler {

    /**
     * Where the motion is at [elapsedNanos] since this execution began.
     *
     * A sampler is created per execution and discarded when it ends, so it never has to be
     * reset and never carries anything from a previous run.
     *
     * Callers sample in non-decreasing order of elapsed time. A closed-form sampler ignores
     * that and could be called in any order; a stepped one integrates forward and cannot go
     * back. Stating it here means 06D can add simulation samplers without the contract
     * changing.
     */
    fun sampleAt(elapsedNanos: Long): MotionSample
}
```

One method, two numbers out. No `advance`, no `reset`, no properties, no policy.

**Why `Motion` and not `Animation`.** ADR-007's own sentence ends *"Sampler maps time to
motion"*, and this is that mapping — a position and a velocity, which is what a motion model
produces and what an animation is built out of. The codebase already uses the word this way:
`aurora.sdk.design.MotionTokens` is where springs and easing curves live. `Animation`,
`AnimationSpec` and `AnimationHandle` keep their names, because those are the animation; this is
the mathematics underneath it.

**`deltaNanos` is gone**, and its absence is the point: no sampler has ever used it. `TimedSampler`
derives from elapsed. A closed-form sampler evaluates a function of elapsed. A fixed-step sampler
derives its step count from elapsed rather than accumulating deltas, which is what keeps 60Hz and
120Hz bit-identical at the same instant. A parameter nobody uses is an invitation to accumulate
something, and accumulation drifts.

**`reset()` is gone**, replaced by constructing a fresh sampler for each execution. This is an
architectural decision, not an optimisation: it makes a sampler's internal state — a fixed-step
integrator's position, velocity and step count — entirely the implementation's business, with
nothing for the engine to remember to clear. It costs one allocation per execution, which is a
human-scale event, not a per-frame one.

**`seekTo(progress)` is gone.** Seeking is now expressed in elapsed time, and elapsed belongs to
`ExecutionTimeline`. That deletes an unsolvable problem rather than working around it: seeking by
progress needs `elapsed = f⁻¹(progress)`, and an overshooting spring reaches progress 0.9 at three
different times, so no inverse exists.

### `AnimationHandle` — changed

```kotlin
    val elapsedNanos: Long              // NEW. The engine's canonical quantity.
    val value: Float                    // = animation.valueAt(sample.value)
    val velocity: Float                 // NEW. Value units per second.
    val hasNormalizedPosition: Boolean  // NEW.
    val normalizedPosition: Float       // NEW. Replaces progress.

    fun seekToElapsed(nanos: Long)      // Replaces seek(progress).

    // REMOVED: progress, seek(progress)
```

**`normalizedPosition` is a convenience for callers, and nothing inside the engine may read it.**
`elapsedNanos` is the canonical quantity. Every calculation in `AnimationHandleImpl`, in a
sampler, in `ExecutionTimeline` and in whatever 06C builds is expressed in elapsed time;
`normalizedPosition` exists only so that a scrollbar, a slider or a scrubber has a 0..1 number
to draw with.

This is stated so plainly because the failure mode is easy to reach and hard to see: someone
arrives, finds a property that looks like the natural way to express "how far through", and uses
it in engine code. It works for every timed animation they test with, and it is meaningless for
every physics animation they do not. The zero-diff gate below is what catches it — engine files
that start reading a UI convenience would have to change to do so.

**Why it survives at all.** Without it, a scrollbar computes `elapsed / duration`, which is right
for a `TimedSpec` and wrong for a `PhysicsSpec` — so the caller has to know which spec it holds,
and the abstraction has leaked.

**Why a boolean and not `Float?`.** `Float?` boxes on every write: `Float.valueOf` has no cache,
unlike `Integer`, so a nullable position allocates once per animation per frame. Around 58KB/s at
120Hz with twenty animations — not a performance problem, but it buys nothing, and it puts a
nullable primitive in an API that is meant to read cleanly from Java too.

**Why NaN and not an exception.** 06A's first rule for `AnimationHandle` is that queries never
throw, in any state including `DISPOSED`, because a teardown path reading a value to log it must
not become the thing that crashes teardown. `hasNormalizedPosition` is the check a caller is told
to make; NaN is what reading without checking gets, and NaN propagates visibly rather than
impersonating zero.

### `AnimationSpec` — gains the rest policy

```kotlin
sealed interface AnimationSpec {

    /**
     * Whether a motion described by this spec has ended.
     *
     * The rule belongs here and not on the sampler, because it is made of this spec's own
     * numbers: a spring rests inside [SpringSpec.restDelta] of its target and below
     * [SpringSpec.restVelocity], while a timed animation ends when its timeline runs out and
     * its value says nothing about it.
     *
     * Putting it here also keeps the engine free of a `when` over spec kinds. A spec added in
     * 06D brings its own rule with it, and `AnimationHandleImpl` never learns that it exists.
     */
    fun isFinished(elapsedNanos: Long, sample: MotionSample): Boolean
}
```

- `TimedSpec` — `timeline.isFinishedAt(elapsedNanos)`. Infinite timelines never finish, which is
  already true today.
- `SpringSpec`, `SnapSpec` — `abs(1f - sample.value) < restDelta && abs(sample.velocity) < restVelocity`.
  The target is 1 because everything here is normalised (ADR-002).
- `DecaySpec` — `abs(sample.velocity) < restVelocity`. A decay has no target to be near; it ends
  when it stops moving.

This is why `restDelta` and `restVelocity` were given to `PhysicsSpec` in 06A and validated as
positive there. They now have a caller.

### `AnimationListener` — changed

```kotlin
    fun onUpdate(
        handle: AnimationHandle,
        executionId: Long,
        elapsedNanos: Long,   // was: progress
        value: Float,
    ) {}
```

Everything the callback receives is still captured into locals before the dispatch loop, for the
reason 06A found the hard way: a listener may restart or seek the handle it is observing, and a
later listener in the same dispatch must not be handed one execution's id beside another
execution's numbers.

### `TimedSpec.elapsedForProgress` — kept, repurposed

No longer called by the engine. It survives as a caller convenience —
`handle.seekToElapsed(spec.elapsedForProgress(0.5f))` — and it lives only on `TimedSpec`, the one
type where the inverse is well defined. The round-trip test that guards it stays as it is.

---

## The runtime after this sprint

`TimedStrategy` becomes `TimedSampler`. Nothing else is renamed, because nothing else exists yet.

From 06B onward, a sampler's name states its method: `ClosedFormSpringSampler`,
`SimulationSpringSampler`. That is runtime naming and it does not contradict the SDK's silence on
how a spring is solved — the spec says *which spring the designer chose*, the sampler name says
*how this build computes it*, and the two can diverge without either lying.

`AnimationHandleImpl` changes in three places: `samplerFor(spec)` replaces `strategyFor(spec)`,
`restart()` constructs a new sampler instead of resetting one, and `seek()` becomes
`seekToElapsed()` and stops asking what kind of spec it holds.

### `velocity` for a timed animation

The contract states only that velocity is the rate of change of value with respect to time. How a
sampler produces it is the sampler's business — an analytic derivative where one exists, something
else where it does not.

*Implementation note for this sprint:* `TimedSampler` uses a central finite difference around the
sampled instant. It is a pure function of elapsed, so determinism is unaffected, and it works for
any `Interpolator` including an arbitrary Bézier that has no closed-form derivative to hand.

---

## Rules

No new rules. Two existing ones are re-examined and both survive unchanged:

**RULE-009** already reads *"independent of wall clock, thread and frame rate"*. Nothing here
weakens it. `sampleAt(elapsed)` strengthens it in practice by removing the parameter a solver
could have accumulated.

**RULE-008** — the engine consumes time rather than acquiring it — is unaffected. No sampler
takes a clock; `AnimationDeterminismTest.noAnimationClassTakesAClock` is updated for the renamed
classes and keeps checking it.

**ADR-002 needs amending.** It currently justifies physics rejecting `seekTo` by saying a spring's
position is the result of integrating from its previous state. For a closed-form spring that is
false. The real reason is that progress is not injective for an overshooting spring, and after
this sprint the question does not arise at all, because seeking is by elapsed.

**ADR-006 needs amending.** It is written in terms of `AnimationStrategy` owning progress. The
split it describes — `ExecutionTimeline` owns elapsed, the sampler owns motion — is unchanged and
is exactly what this sprint makes clearer.

---

## How the migration is proved correct

The 267 existing tests are the safety net, and the rule that makes them one is:

> **Translate names and shapes. Never translate expected values.**

If a test needs its *field names* changed, that is migration. If a test needs its *expected
numbers* changed, the migration has broken something and the work stops until it is understood.
The one exception is arithmetically forced and must be stated in the commit: tests that assert on
`progress` where the animation has an interpolator now assert on `normalizedPosition`, which is
the same number under a new name.

`AnimationDeterminismTest` keeps its tolerance of exactly zero throughout. Its coherence test gets
simpler: it compares `elapsedNanos` directly instead of comparing progress values, which also
removes the float-subtraction cancellation that had to be fixed twice during 06A.

---

## Exit criteria

| Criterion | Check |
|---|---|
| Compile PASS | `m aurora-sdk aurora-runtime aurora-platform` |
| Host Test PASS | 267 tests, none with a changed expected value except the forced rename |
| No behavioural change | every test that passed before passes after |
| Deterministic PASS | `AnimationDeterminismTest`, tolerance still exactly zero |
| Architecture PASS | `arch-test.sh`, 45 checks |
| No Android dependency | zero `android.*` imports under either animation package |
| API coverage | every public declaration named by a test |
| No solver | zero occurrences of spring, decay, snap, bezier or fling in any new source file |
| **No runtime behaviour change** | `git diff` is empty for `AnimationStateMachine.kt`, `AnimationRegistry.kt`, `AnimationDriver.kt`, `DefaultAnimationController.kt` and `ExecutionTimeline.kt` |

The last two are the guard against this sprint quietly becoming 06B, and they catch different
things. The grep catches a solver being added. The empty diff catches the engine being *changed* —
a migration that had to touch the state machine or the registry to make the tests pass would not
be a migration.

Those five files are on the list because none of them ever mentions progress, a sampler or a
strategy: the machine deals in states and events, the registry in tick order, the driver in
frames, the controller in start/stop, and `ExecutionTimeline` in elapsed nanoseconds. If any of
them needs to change, the model is wrong somewhere and the sprint stops.

`AnimationHandleImpl` is deliberately **not** on the list. It is the one place the three concepts
meet, so it is the one runtime file that must change: `samplerFor` replaces `strategyFor`,
`restart()` builds a fresh sampler, and `seek()` becomes `seekToElapsed()`.

---

## Roadmap after this

| Sprint | Adds | Touches the SDK? |
|---|---|---|
| **06B.1** | closed-form *infrastructure*: `samplerFor` dispatch, `TimedSampler` on the new contract, the analytic-derivative helper, the sampler test harness. No solver. | no |
| **06B.2** | the solvers that exercise it: spring, decay, snap, and `BezierInterpolator` | no |
| **06C** | orchestration: sequence, parallel, stagger, retarget, interrupt policy | to be designed; `velocity` exists for it |
| **06D** | simulation samplers: fling, rubber band, overscroll, fixed-step core | no |

06B is split for the same reason 06A.5 exists at all. If the dispatch, the derivative helper or
the test harness is wrong, that is found before a single solver is written; if a spring is wrong
afterwards, the framework underneath it is already known good. The alternative — building both at
once — means a failing test has two possible causes, which is the situation this whole sequence
of sprints has been arranged to avoid.

06C is where `velocity` earns the place this sprint gives it: retargeting a spring mid-gesture
means handing the new spring the velocity the old one had.
