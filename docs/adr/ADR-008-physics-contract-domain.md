# ADR-008 — The physics contract states its domain, and completion is a single monotone scalar

**Status:** accepted · 2026-08-03 · Sprint 06B.0

## Context

Sprint 06B was to add five solvers on top of the engine 06A froze. Two questions ADR-002 recorded
as open blocked all of them, and both turned out to be one question in disguise: *which physical
quantity is a sampler reporting?*

- What does a decay normalise against, having no target?
- What happens when `from == to` and the caller's velocity conversion divides by the range?

Neither is answerable before that prior question, and answering it needs no Kotlin.

Reviewing `PhysicsSpec` to answer it turned up a third problem, unrelated to either but fatal to
both: the completion rule could not stand.

## Decision

### The contract states its own domain

> It binds every solver whose entire dynamical state is `(value, velocity)`.

Every convergent system has a monotone Lyapunov function, so existence constrains nothing. The
binding requirement is that one be *readable from a `MotionSample`*, which holds exactly for
second-order autonomous systems. That is the real reason spring, decay and snap are one family.

A PID controller carries an integral term, so two frames with identical value and velocity are at
different distances from rest and no function of `MotionSample` separates them. It does not break
the contract; it falls outside it, and the extension it needs is a wider `MotionSample`.

Writing the boundary down makes that arrival a known extension point instead of a surprise that
makes the contract look wrong.

### Completion is a metric, a threshold and a comparison

`PhysicsSpec` loses `restDelta` and `restVelocity` and gains:

```kotlin
fun completionMetric(sample: MotionSample): Float   // physics; never increases while running
val completionThreshold: Float                      // UX
// isFinished = completionMetric(sample) < completionThreshold, overridden by nobody
```

The old rule was `|1 - value| < restDelta && |velocity| < restVelocity`, read from the
instantaneous sample. An underdamped spring has **zero velocity at every turning point**, so once
its envelope fell below `restDelta` the rule reported finished there and not-finished a moment
later as the motion swept back through its target.

Against the shipped tokens, where `ω_d = √stiffness · √(1 - ζ²)` and the flip needs `ω_d > 10`:

| token | stiffness | ζ | ω_d | flips |
|---|---|---|---|---|
| `SPRING_BOUNCY` | 500 | 0.60 | 17.9 | yes |
| `SPRING_GENTLE` | 400 | 0.85 | 10.5 | yes, marginally |
| `SPRING_SNAPPY` | 800 | 1.00 | — | no — critically damped |

The engine survives it by stopping at the first frame reporting true and never asking again. The
consequence is worse than a crash would be: **the instant a spring settles depends on which frame
lands near a turning point**, so the same spring finishes at different times at 60Hz and 120Hz.
Two of three tokens are affected and one of those sits just over the boundary — which is its own
argument against a rule whose correctness depends on where a design token happens to land.

A spring's metric is `√(x² + (v/ω)²)`, the amplitude the oscillation would settle at if damping
stopped now. It never increases because for `E = ½(v² + ω²x²)`, `dE/dt = -2ζωv² ≤ 0`. At a
turning point `v = 0` so it equals the true envelope; between turning points the `(v/ω)²` term
supplies what instantaneous displacement stops accounting for.

`v/ω` is in progress units, so one threshold serves every family and means the same thing in each.

### A decay's target is derived, not absent

Normalised against its own total travel, a decay's position is `1 - e^(-f·t)` and **`v₀` cancels
completely**: initial velocity decides how far a decay goes, not how it goes. So

```
to = from + v₀ / friction
```

and `from == to` means exactly `v₀ == 0` — a fling released at rest, which travels nowhere. Not
an edge case wanting a rule; a precondition wanting a name, and `DecaySpec` now rejects it.

## Alternatives considered

**Keep two thresholds.** For a decay, `|1-value| < δ` and `|velocity| < ε` both reduce to
`e^(-ft) < c`: the same condition twice, with one of the two always dead. For a spring the
conjunction is necessary but not sufficient, as above. Two numbers that are sometimes one and
sometimes not enough.

**Keep the instantaneous rule and accept frame-dependent settling.** Simpler, and what several
animation engines do. Rejected because the sprint's output is a contract, and a contract that
cannot say when a motion ends is not one.

**Add `restingPoint` to the contract as a required closed form.** Proposed as a second domain
clause and rejected: as a mathematical statement it is vacuous. For any autonomous system with
unique solutions the initial state determines the whole trajectory and hence its limit, so the
resting point is *always* determined at t = 0. What can fail is having a formula for it, and that
is a property of notation. If it belongs anywhere it is an engineering requirement and must say
so rather than borrow this ADR's authority.

## Consequences

- Two files changed: `AnimationSpec.kt` and `AnimationApiTest.kt`. `AnimationService.springTo`
  had no implementation, so no caller performed the conversion yet. The change was free at 06B.0
  and stops being free at 06B.1.
- `DecaySpec.initialVelocity` loses its default; four construction sites now name one.
- `DecaySpec.friction` moves from 0.5 to 4.6. Settle time is `-ln(threshold)/f`, so the old
  default meant **13.8 seconds** for a fling to stop. It had been documented as plausible rather
  than measured, and until completion became a single scalar there was no closed form to solve.
- `SnapSpec` duplicates the spring envelope rather than sharing it. A base class two specs use
  would have to widen the moment a third family measures rest differently — and a test asserts
  the duplicate has not drifted, which is cheaper than the base class.
- **The spring envelope has no trajectory subject in Sprint 06B.0.** Its monotonicity is a
  theorem about solutions of `ẍ + 2ζωẋ + ω²x = 0`; a hand-built oscillation is not a solution, so
  a failure would not distinguish a wrong metric from a wrong fixture. This is RULE-017, which
  this sprint discovered and which is recorded rather than worked around. 06B.1 is the first real
  test of the envelope. The formula is checked algebraically in the meantime.
- One test reversed its expected values. `aDecayFinishesOnVelocityAloneBecauseItHasNoTarget`
  asserted a decay at 0.3 had arrived; under a target-relative metric it has 70% of its travel
  left. The premise was refuted, which the 06A.5 migration rule calls a finding rather than a
  rename.
