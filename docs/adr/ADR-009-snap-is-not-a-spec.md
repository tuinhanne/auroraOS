# ADR-009 — `SnapSpec` leaves the animation spec hierarchy

**Status:** accepted · 2026-08-05 · Sprint 06B.3

Amends ADR-002, which placed `SnapSpec` in `PhysicsSpec` as one of five expected solver families.

## Context

Sprint 06B.0 declined to design snap in advance. It said instead what would count as evidence that
the motion abstraction had been *found* rather than imposed: that snap turns out to need no solver
of its own.

Sprint 06B.3 produced that evidence. Question 1 established that the integration layer begins once
`to` exists, however it came to exist, and Task 3 built `SnapFactory` on it:

```
TargetSelectionPolicy  →  SpringFactory  →  SpringSampler
   (policy layer)          (integration)      (unchanged)
```

Once a target is selected, a snap **is** a spring. `SnapFactory` returns an `Animation` carrying a
`SpringSpec`, so `AnimationHandleImpl.samplerFor` never receives a `SnapSpec`, and 06B.3 became the
first sprint in the 06B sequence to change the solver layer not at all.

That left `SnapSpec` in `PhysicsSpec` with nothing using what the interface required of it. Task 4
audited the members and found:

| member | callers | runtime paths | contract assertions |
|---|---|---|---|
| `completionMetric` | 0 | 0 | 1 |
| `initialVelocity` | 0 | 0 | 0 |
| `isFinished` | 0 | 0 | 1 |

The two surviving references were `PhysicsContractTest.aSnapMeasuresRestExactlyAsItsSpringWould`
and `AnimationApiTest.aSnapUsesTheSameRestRuleAsASpring`. Both existed for one purpose: to check
that snap's copy of the spring envelope had not drifted from the original. Neither is evidence the
members were used. They are evidence the members were duplicated, and they would retire with them —
a reference that exists only because the thing it references exists keeps nothing alive.

## Decision

`SnapSpec` is not an `AnimationSpec`.

```kotlin
sealed interface AnimationSpec {
    TimedSpec
    sealed interface PhysicsSpec { SpringSpec, DecaySpec }
}

// an input to SnapFactory, not a motion the engine can run
data class SnapSpec(
    targets: List<Float>,
    spring: Spring,
    completionThreshold: Float,
)
```

It keeps `targets`, `spring` and `completionThreshold` — the three things `SnapFactory` reads — and
loses `initialVelocity`, `completionMetric` and `isFinished`, which existed to satisfy an interface
rather than a caller. `completionThreshold` stays a plain property: it is carried through to the
`SpringSpec` selection produces, so a caller sets completion once without knowing that a snap
becomes a spring on the way to the engine.

**What `SnapSpec` describes is a selection problem plus the settling behaviour to apply once the
selection is made.** It describes a motion no more than a gesture does.

### Two consequences that were not the point but are the strongest part

**`samplerFor` refuses nothing, and the refusal is replaced by something stricter.** The `when` is
now exhaustive over a sealed hierarchy with no `else`, so a spec kind arriving without a sampler is
a compile error rather than an exception a caller has to reach. The gate that watched the refused
set retires with its subject.

**A snap measures rest by *being* a spring rather than by two copies being kept in step.** The
duplication ADR-002 and Sprint 06B.0 accepted as a cost is gone, not guarded.

## Alternatives considered

**Keep `SnapSpec` in `PhysicsSpec` and delegate the metric** to a `SpringSpec` built on the fly.
This removes the drift risk and lets the drift test retire, but keeps a member no pipeline reaches
and an `isFinished` nothing evaluates. It treats the symptom — two copies of a formula — while
leaving the cause, which is that the type claims to describe a motion it does not describe.

**Keep everything and record a named gap in §7,** carrying 06B.0's reasoning forward: the
duplication waits for a fourth family rather than being resolved now. Rejected because the fourth
family arrived and is this one. 06B.0's condition was a family that measures rest *differently*;
snap measures it by delegation, which answers the question rather than deferring it again.

**Leave `PhysicsSpec` but stay in `AnimationSpec`.** Then `samplerFor` still needs an `is SnapSpec`
branch that throws, so the engine keeps a refusal for a spec no caller can usefully send it, and the
sprint's exit criterion — that `samplerFor` refuses nothing — becomes unreachable for a reason that
has nothing to do with motion.

## Consequences

- Two tests retire with their subject, each replaced by a comment at its old site saying what it
  asserted and why it no longer can. An assertion that disappears silently is indistinguishable
  from one nobody noticed had stopped running.
- `AnimationLifecycleTest.anUnsolvedPhysicsAnimationIsRejectedWithAMessageNamingTheSprint` is
  replaced by `everySpecTheEngineCanReceiveHasASolver`, its opposite. The refused set is empty, so
  the old assertion has no subject; the new one guards the claim that took its place. It is not a
  formality — exhaustiveness is load-bearing only while no `else` branch exists, and adding one is
  legal Kotlin that silently restores the hole.
- Gates 2 and 5 of `verify-motion-evidence.sh` retire. Gate 2's one surviving check — that a
  deliberately wrong subject never leaves the test tree — moved into gate 4, where it belongs by
  role.
- **This is an SDK-visible change.** `SnapSpec` can no longer be passed to `Animator.create`. That
  is the intended effect: it never worked, and it now fails at compile time in the caller rather
  than at runtime in the engine.
- `PhysicsSpec` now has two subtypes rather than three, and ADR-002's expectation of five solver
  families ends at three. The fourth and fifth were fling and snap; fling turned out to be a decay
  with a derived target (Sprint 06B.2) and snap a selection with a spring. **Two of the five
  families named in advance were not families at all**, which is the clearest evidence available
  that declining to design them up front was right.

## What this does not decide

Whether a future family that genuinely measures rest differently should extract a shared envelope.
Nothing here forbids it, and the question is unchanged: it needs a subject with real variation, and
there is still only one formula.
