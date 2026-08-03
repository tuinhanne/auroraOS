# The Aurora Motion Sampler Contract

**Normative. Spans sprints.** Established Sprint 06B.0, 2026-08-03.

This document says what every Aurora motion sampler must do. It is not an ADR: `docs/adr/`
records why a decision was made and what was rejected, and `docs/specs/` describes one sprint's
work. This describes what any implementation of this kind must satisfy, for as long as the kind
exists.

It is also not one of `frameworks/base/aurora/contracts/*.contract` — those are layer rules that
`arch-test.sh` parses. This is prose, and the name keeps the two apart.

**Every clause below names the assertion that enforces it.** A clause with no assertion says so
in the same breath. A contract nobody checks is prose, and this project has been bitten twice by
an unverified claim that happened to be true.

---

## 1. Domain

> The contract binds every solver whose **entire dynamical state is `(value, velocity)`**.

Every convergent system has a monotone Lyapunov function, so existence constrains nothing. The
binding requirement is that such a function be **readable from a `MotionSample`**, and that holds
exactly for second-order autonomous systems. Spring, decay and snap all are; that is the real
reason they form one family, and a better one than "they are all physics".

A PID controller is not. Its state is `(x, v, ∫x)` and its Lyapunov function carries the integral
term, so two frames with identical value and velocity sit at different distances from rest and no
function of `MotionSample` separates them. The same applies to delay systems, to hysteresis, and
to a target that moves during the animation.

Such a solver does not break this contract. It falls **outside its domain**, and the extension it
needs is a wider `MotionSample` — not another threshold.

*Enforced by review.* Nothing can check at compile time that a solver's state is what it claims.

---

## 2. `MotionSample.value`

> Normalised displacement from `from` toward the resting point, running 0 → 1. **One meaning for
> every family.**

A spring's resting point is supplied by the caller as `to`. A decay's is derived: under
exponential friction the total travel is `v₀/friction`, in closed form before the first frame, so
`to = from + DecaySpec.restingDisplacement(v₀)`. A decay has a target after all; it is an output
of the model rather than an input from the caller, which is a different thing from being absent.

`value` is not clamped. An overshooting spring passes 1 and must be allowed to.

*Enforced by* `PhysicsContract.assertConvergesToOne`, which is also the only thing that notices
when a caller's computed travel disagrees with a sampler's shape — a decay that stops in the
wrong place still runs and still looks smooth.

---

## 3. `MotionSample.velocity`

> The derivative of `value`, in progress per second.

*Enforced by* `SamplerContract.assertVelocityMatchesDerivative`, which compares against a central
difference **at a step different from any sampler's own** (RULE-016). Checking a central
difference with a central difference at the same step passes for every input.

---

## 4. Completion

> A single scalar in progress units that **never increases while the sampler evolves**, compared
> against one threshold.

```kotlin
fun completionMetric(sample: MotionSample): Float
val completionThreshold: Float
// isFinished(elapsed, sample) = completionMetric(sample) < completionThreshold
```

Three responsibilities, separated: `completionMetric` owns the physics, `completionThreshold`
owns the UX, and `isFinished` is a comparison no spec overrides.

The contract states **observable behaviour, not a formula**. An implementation may compute the
metric from energy, from a decay envelope, or from anything else, provided a later sample never
reports more than an earlier one. The wording is deliberate: "monotonic" is a claim over the whole
function and would have to hold where the metric has fallen to 1e-9, where float32 rounds in both
directions. Bounding it to the running region keeps it above the threshold, where one ULP is
around 1e-10 and no tolerance constant has to be invented.

Because the metric is in progress units for every family, one threshold means the same thing
everywhere: `0.001` is "the residual motion is under a thousandth of the travel", whether that
motion is a spring settling or a fling coasting.

*Enforced by* `PhysicsContract.assertMetricNeverIncreases` — **subject to RULE-017**, see §7.

---

## 5. Determinism

> Two samplers built the same way and driven through the same schedule agree at every step.

*Enforced by* `SamplerContract.assertDeterministic`, which takes a factory rather than an
instance. That is the only way to state determinism without also demanding replay: a stepped
sampler legitimately holds instance state. What is never legitimate is state that outlives the
execution it belongs to.

Determinism is not the same as avoiding `Random`. A sampler with no random source at all can be
non-deterministic through shared state, and one with a fixed seed can be perfectly deterministic.
RULE-009's bans are a heuristic; this property is the thing itself.

---

## 6. Finiteness

> No sample is NaN or infinite.

*Enforced by* `SamplerContract.assertFinite`. Cheap, and worth more than it looks: a NaN reaches
the screen as a view that silently stops drawing, with no exception anywhere to point at.

---

## 7. What is **not** yet verified

Sprint 06B.0 delivers this contract with no solver in existence, so two clauses have fixtures that
prove they can fail but no production subject that proves they hold.

| clause | violating fixture | trajectory subject |
|---|---|---|
| §4 completion | `IncreasingEnvelopeSampler` | decay only — `DecayTrajectory` |
| §2 convergence | `NonConvergingSampler` | decay only — `DecayTrajectory` |

**The spring and snap envelopes have no trajectory subject.** This is RULE-017 in force rather
than an oversight. Their metric is never-increasing because `dE/dt = -2ζωv²` along solutions of
`ẍ + 2ζωẋ + ω²x = 0`; a hand-built oscillation is not a solution, so the metric need not fall
along it and a failure would not distinguish a wrong metric from a wrong fixture. A genuine
solution is the closed form, which is Sprint 06B.1.

What the envelope does have is `PhysicsContractTest`, which checks the **formula** algebraically
on hand-built samples: zero only at rest on target, symmetric in overshoot and undershoot,
counting speed as `v/ω` of remaining travel, and shrinking with stiffness at a fixed speed. Those
pin the properties the monotonicity argument needs in order to be about the right quantity. They
are not a substitute for running it along a trajectory.

**Sprint 06B.1 is the first real test of the spring envelope, not a regression check on it.**

---

## 8. Open

**Is `(from, to)` universal, or is it Spring's shape wearing a general name?** A sampler needs
only the travel; an `Animation` needs only a target; the two are one fact in two shapes. The
halves are symmetric — each family supplies one and derives the other — and §1's domain implies a
finite travel, so `(from, to)` appears to be a consequence rather than a constraint. Recorded as
open analysis in `docs/specs/2026-08-03-sprint-06b0-physics-semantics-design.md` §4.6. It blocks
nothing here: if it resolves the other way the change lands in `Animation` and its factories, not
in `PhysicsSpec` or in this contract.

**Does any family need two thresholds?** Spring and decay do not. That no future solver does is
not shown. §1 answers it for state-space reasons; whether a second scalar is ever wanted *within*
the domain stays open, and the harness is where it would first appear.
