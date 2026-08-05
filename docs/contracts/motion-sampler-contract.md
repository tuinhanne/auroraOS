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

*Enforced by* `PhysicsContract.assertConvergesToOne`.

> **Correction, 2026-08-04.** This clause claimed that assertion also catches a caller whose
> computed travel disagrees with the sampler's shape. **It does not, and cannot.**
> `assertConvergesToOne` takes a `MotionSampler` and checks that `value` reaches 1 — but `value`
> is normalised progress and the sampler never sees `to`. A decay converges to 1 for *any*
> friction, so a caller computing `to = from + v0/friction` with the wrong friction, the wrong
> formula, or no division at all still produces a green run.
>
> The two halves of the friction model are separated by a unit boundary, and **both contract
> tiers live entirely on the normalised side of it**. Neither can host an oracle for the crossing.
> Catching it needs an assertion whose subject is the pipeline rather than the sampler: a fling
> released at a measured velocity in value units must come to rest at a stated position in value
> units. That check does not exist yet; `AnimationService.fling` is where it will live, and Sprint
> 06B.2 is where it is built.
>
> Until then this hazard is guarded by **nothing**, which is what §7 now records.

---

## 3. `MotionSample.velocity`

> The derivative of `value`, in progress per second.

*Enforced by* `SamplerContract.assertVelocityMatchesDerivative`, which compares against a central
difference **at a step different from any sampler's own** (RULE-016). Checking a central
difference with a central difference at the same step passes for every input.

### The oracle's own accuracy is a criterion, and it is not fixed forever

The comparison uses a numerical approximation, so the approximation's error must stay well below
the 5% tolerance **across every subject the contract admits** — otherwise the property stops
measuring the sampler and starts measuring the oracle.

Central difference truncation error grows as `(h·ω)²/6` in the motion's own frequency. The step
was 10ms when this harness was written in Sprint 06B.0, chosen when it had exactly one subject:
`TimedSampler`, whose timescale is a whole timeline. Sprint 06B.1 supplied the first subject with
a high natural frequency — `SPRING_SNAPPY` at `ω = √800 ≈ 28.3` — and there the oracle's own error
reached roughly 6%, above the tolerance it was being compared against. The sampler was correct;
its analytic derivative agreed with the closed form to four digits.

The step is therefore **1ms**, which puts the approximation error near 0.01% for that spring and
around 1.7% even at a stiffness of 10 000. It remains distinct from `TimedSampler`'s internal
0.5ms, so RULE-016 still holds.

The question a future solver raises is *"is the oracle still accurate enough?"* — not *"does this
solver need a different step?"*. Widening the 5% tolerance would answer the wrong question: the
tolerance is the standard of acceptance and the step is the quality of the measurement, and it
was the measurement that was inadequate.

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

## 7. Verification backlog

Not a list of shortcomings. It is the set of clauses that have a fixture proving they *can* fail
but no production subject proving they *hold* — propositions waiting for their first real
subject to confirm or refute them. Narrowing this table is the first work of the sprint that
adds a solver, and it comes before writing one: each row that moves from unverified to verified
makes the contract stronger, and each row that has to be **changed** in the process means the
contract learned something from its first implementation.

**Verified is a property of a pair, not of a clause.** It holds of `(clause, subject family)`, so
the backlog is a matrix. Sprint 06B.1 gave the spring a trajectory subject; snap had no sampler and
its rows could not move on the spring's evidence, even though it shared the formula. A matching
formula is not a formula verified along a trajectory.

**Sprint 06B.3 dissolved that pair rather than completing it.** Snap turned out not to be a solver
family at all: once a target is selected a snap *is* a spring, `SnapFactory` hands the engine a
`SpringSpec`, and `SnapSpec` left the spec hierarchy (ADR-009). So snap's column does not move from
pending to verified — it stops being a column, because there is no snap subject distinct from the
spring one to verify. The duplicate the old note spoke of is gone rather than guarded, and the test
that watched it for drift retired with it.

Worth keeping the distinction that made the old note right. It was *not* correct to let snap
inherit the spring's evidence while snap had its own formula; it *is* correct now, because it has
no formula of its own. What changed is the subject, not the standard.

### Three things qualify "verified", and conflating any two costs a column its meaning

**Layer** — what the assertion observes. This is an observation about the assertions that exist,
not a claim about how a motion system must be built; a later family may well add a fourth.

| layer | subject | examples |
|---|---|---|
| solver | a `MotionSampler` | everything in `SamplerContract` and `PhysicsContract` |
| integration | a pipeline including its caller and its unit conversion | `IntegrationContract`, one witness per family |
| endpoint | the animation API as a caller observes it | 06A's endpoint-exact `valueAt`, the lifecycle tests |
| policy | a pure rule upstream of the unit boundary | `TargetSelectionPolicy`'s three properties |

The middle row was empty and that was the finding. Sprint 06A produced endpoint evidence and 06B.1
produced solver evidence; nothing had ever observed the two joined. It went unnoticed because a
spring does not need it — `to` is an input, `valueAt(1) == to` was proven in 06A and the trajectory
in 06B.1, so the chain closed with no unobserved link. A decay inserted one:
`to = from + v₀/friction` is *derived*, and no assertion had ever crossed it.

**Sprint 06B.2 opened the layer and 06B.3 filled it.** One assertion covers all three families:

```
(to − from) · v_normalised  =  v_gesture
```

Sprint 06B.2 wrote it as `(to − from) · friction`, which is the same equation — `friction` **is** a
decay's normalised initial velocity — but parameterised by a quantity one family happens to own. A
spring has no friction, and that is what revealed it. Generalising the signature to
`PhysicsSpec.initialVelocity` states the same law in a quantity no family owns alone.

That one invariant now covers a **supplied** target (spring), a **derived** one (decay) and a
**selected** one (snap), which was the prediction 06B.3 existed to try to refute and did not.

**A fourth layer arrived without being planned.** `TargetSelectionPolicy` is a pure function
upstream of the unit boundary: it crosses no boundary, needs no pipeline and touches no sampler, so
it is neither solver nor integration. It is listed because the layer definition above is an
observation about the assertions that exist, and one more now exists.

> **A layer that has never existed has no calibrated assertion in it.** The first sprint to open
> one must therefore show its assertion can *reject* before treating a pass as evidence — the
> assertion and its subject are both new, so nothing independent remains otherwise. This is
> RULE-015 applied to the birth of a layer rather than to a single property, and it holds for any
> fourth layer as much as for the third.



**Provenance** — what the evidence came from. *Analytic* means a trusted closed form written into
the tests; *production* means the sampler that actually ships.

The distinction bites hardest where the two coincide. A decay's closed form is three lines, so
`DecaySampler` will be **the same expression** as `DecayTrajectory` — moving it from `tests/` to
`runtime/` produces no evidence the contract does not already have. A spring's was not: two
branches, a removable singularity and a cancellation, so its production subject could contradict
the spec, and did, four times.

**Domain** — where the evidence was gathered. Both tiers take a `MotionSampler` and work in
**normalised progress**, so every row below is verified on the normalised side of the unit
boundary and nowhere else. A clause can be production-verified and still never have been checked
where value units are converted, which is exactly the state §2's correction describes.

That is why Sprint 06B.2 is about the pipeline rather than about a solver: the solver adds nothing
to the first column, and the second column has never been touched at all.

| clause | Spring | Decay | Snap | witness |
|---|---|---|---|---|
| §4 completion | **verified 06B.1** | verified 06B.0 | *no distinct subject* | `IncreasingEnvelopeSampler`, `UndampedEnvelopeSpring` |
| §2 convergence | **verified 06B.1** | verified 06B.0 | *no distinct subject* | `NonConvergingSampler`, `WrongBranchSpring` |
| travel preserves the gesture velocity | **verified 06B.3** | **verified 06B.2** | **verified 06B.3** | `springForgettingToNormalise`, `flingForgettingFriction`, `snapForgettingToNormalise` |

**Every family is now end-to-end**, in the sense the exit criterion asked for: each has a solver
subject and an integration subject, and no row is waiting on a sprint. Snap's two solver cells are
italicised rather than green because they are not claims about snap — the spring cells beside them
are the evidence, and snap has no separate thing to verify.

The third row is the layer 06B.2 opened. Its three witnesses are the three ways a target is
obtained, which is the whole content of the claim that one invariant covers them.

Sprint 06B.1 closed the spring column, and the green means something because the properties were
shown able to reject a spring **before** the real one ran. That mattered more than usual here:
`SpringSpec.completionMetric` and a spring trajectory both derive from the same `ω`, so a correct
closed form makes the metric exactly `A·e^(-ζωt)` — monotone by construction, and a property could
have passed having examined nothing.

**Snap's envelope has no trajectory subject and never will get one.** What follows was written in
Sprint 06B.0, when the sentence read "still has none" and named 06B.3 as the sprint that would
supply it. RULE-017 in force rather than an oversight. Their metric is never-increasing because
`dE/dt = -2ζωv²` along solutions of `ẍ + 2ζωẋ + ω²x = 0`; a hand-built oscillation is not a
solution, so the metric need not fall along it and a failure would not distinguish a wrong metric
from a wrong fixture. A genuine solution is the closed form, which is Sprint 06B.1.

Sprint 06B.3 did not supply the missing subject. It removed the need for one: a snap becomes a
`SpringSpec` before anything samples it, so the spring's trajectory is the only trajectory there
is. The gap closes by the question being wrong rather than by the work being done — which is a
different outcome from the row being filled, and is recorded as such so nobody later reads a
dissolved gap as a discharged one.

What the envelope does have is `PhysicsContractTest`, which checks the **formula** algebraically
on hand-built samples: zero only at rest on target, symmetric in overshoot and undershoot,
counting speed as `v/ω` of remaining travel, and shrinking with stiffness at a fixed speed. Those
pin the properties the monotonicity argument needs in order to be about the right quantity. They
are not a substitute for running it along a trajectory.

**Sprint 06B.1 was the first real test of the spring envelope, and it passed.** The algebraic
checks stay. They are what pins the formula to the right quantity, and that job does not end when a
trajectory subject arrives — the monotonicity argument needs both halves.

### 7.0 Named gaps: what no assertion in this contract observes

A row here is a question the framework cannot yet answer, recorded so nobody has to think of it
again. An empty row with a reason beside it is not a shortcoming — a gap that stays in a sprint's
prose is how the 06B.0 convergence claim survived three sprints unexamined.

| gap | what is unobserved | why it is not merely missing |
|---|---|---|
| **projection provenance** | where `candidate` comes from — the position a gesture is predicted to land on, which `SnapFactory` takes as an input | Sprint 06B.3 Question 2 established the projection is the *caller's*, because a factory owning it would hold semantics no contract observes and the policy below it would be defined in terms of one family's physics. So the rule is correct and the quantity is unverified: nothing in this contract can say whether the caller's projection is any good, and a snap onto the nearest target of a badly projected candidate is precisely accurate about the wrong place. |
| **unit boundary crossing for `TimedSpec`** | the timed family has no integration-layer subject | `TimedSpec` carries no velocity to normalise, so the invariant that covers the other three has nothing to say about it. Whether that means it needs none, or needs a different one, has never been asked. |
| **velocity at a replacement boundary** | what carries from a cancelled execution to the one replacing it. Position is promised by `cancel()` — *"should stay where the user last saw it"* — and asserted at the endpoint layer since 06A. Velocity survives on the handle, is published in value units per second, is expressible as `PhysicsSpec.initialVelocity`, and is promised by nothing that ships. | It cannot be closed by strengthening an existing assertion, because no assertion has a *pair* of executions as its subject. The intent is not missing: `AnimationService` states it under the heading *"Interruption is the point"* and `GestureService` carries a velocity for that stated purpose — but that layer has no implementation, `Animator` below it assigns the job to the caller, and no document reconciles the two. **Sprint 06C.0 established that no production subject carries it**: no caller outside `tests/` invokes any factory, and `AnimationHandle.velocity` has zero readers in the entire tree. So the carrier is built and unread, and the consumer is built and unfed. A replacement that drops the velocity passes every layer this contract reaches and is visibly wrong on a device. |

All three are gaps in *coverage*, not in the assertions that exist. None can be closed by making an
existing property stricter, which is what distinguishes them from the backlog matrix above.

**The third row is broken at exactly one link, and that is what raises it above *nobody has written
this caller yet*.** Both ends exist, in agreeing units, with nothing between them:

```
AnimationHandleImpl        velocity = sample.velocity * range        computed every frame
        │
        ▼
AnimationHandle.velocity   public API, value units per second        carrier exists
        │
        ✗                  zero readers in the entire tree           ← the break
        │
        ▼
SpringFactory.springTo(…, gestureVelocity, …)                        consumer exists, unfed
```

**It states that no production subject exists, and nothing about who should own one.** Runtime,
caller and a layer above the runtime all remain open; Sprint 06C.0 asked *does a subject exist* and
never reached *who owes anything about it*. Reading this row as *the caller owns it* would convert
an observation into a decision the evidence does not support.

One observation is recorded with it, and is not a proposal. The signature that could accept such a
velocity names it `gestureVelocity`, after the source that produced the only case anyone had in
view. That is correct for every caller which exists today, and it is the third time this repository
has found a name taken from an origin obscuring a role — after `DecaySpec.friction`, which named a
law after one family's quantity, and `class … : MotionSampler`, which named a witness after one
layer's shape. If a replacement ever acquires a subject, this is the name that will be under
pressure.

### 7.1 Order of investigation when a backlog clause first goes red

Stated here, before any solver exists, because when it is written matters more than what it says.
The same sentence added after a failure reads as a rescue of whatever implementation was in front
of the author; written beforehand it favours nothing, because there is nothing yet to favour.

> **If an implementation satisfies the sampler tier's independent invariants (§3, §5, §6) but
> fails a physics-tier property (§2, §4), investigate the contract and its metric before changing
> the implementation.** The implementation is internally consistent, and what is rejecting it is
> the metric. Reverse the presumption only on independent evidence against the implementation.

This states an **order of investigation**, not a verdict. The contract is not presumed right and
the implementation is not presumed wrong; the sampler tier may simply be too weak to catch some
class of error, and that possibility is exactly why the rule points at what to examine first
rather than at what to conclude.

**The rule holds only while the sampler tier is an independent oracle.** Its value is its
independence, not its assertion count. The day one of its properties reads `completionMetric`,
both tiers can share a wrong assumption, they will fail and pass together, and this rule silently
becomes worthless — while still looking like it works. Nothing detects that automatically; it is
a review obligation on anyone extending `SamplerContract`.

The pressure this exists to resist is structural rather than personal. Whoever writes the first
solver is also the only person positioned to notice the contract is wrong, and they have the
opposite incentive: a green run means the sprint is finished. Deciding in advance what evidence
counts is the defence. An instruction to stay open-minded is not.

Demoting or amending a property in this situation is progress in the contract, not a failure of
the implementation — the same move the README records as expected rather than exceptional.

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
