# Sprint 06B.0 — Physics semantics

**Status:** design agreed · 2026-08-03 · precedes Sprint 06B.1 (Spring)

Sprint 06B.0 writes no solver. It answers what a solver *is*, so that 06B.1 can begin by making
a spring satisfy a stated contract rather than by asking what a correct spring would be. That
second question is open-ended, and Sprint 06A hit its failure mode three times: a test invented a
promise the design had never made, and the first instinct each time was to change the code.

Nothing here needs a line of Kotlin to decide. It needs the physics named.

---

## 1. The question this sprint answers

Sprint 06A.5 settled what language the engine and a solver speak: `MotionSampler.sampleAt`
returns a `MotionSample` of value and velocity. It did not settle what those numbers *mean* for
a physical motion, and the two questions ADR-002 left open are both that question wearing a
disguise:

- **What does a decay normalise against?** It has no target, so `(to - from)` — the factor the
  spring argument relies on cancelling — appears to have nothing to be.
- **What happens when `from == to`?** The caller-side velocity conversion divides by the range.

Neither can be answered before the prior question: *which physical quantity is a sampler
reporting?* This sprint answers that first, and both fall out.

---

## 2. Decision — the contract has a stated domain

> **The Aurora Physics Contract applies to every solver whose entire dynamical state is
> represented by `(value, velocity)`.**

This is a design boundary, not an accidental limitation, and it is what makes the rest of the
contract possible.

Every convergent system has a monotone Lyapunov function; existence constrains nothing. The
binding requirement is that such a function be **readable from a `MotionSample`**. That holds
exactly when the state *is* position and velocity — second-order and autonomous. Spring, decay
and snap all are. That is the real reason they form one family, and it is a stronger reason than
"they are all physics".

A PID controller is not. Its state is `(x, v, ∫x)` and its Lyapunov function carries the
integral term, so two frames with identical `(value, velocity)` and different accumulated error
have different distances-to-rest. No function of `MotionSample` can tell them apart. The same
applies to delay systems and to a target that moves during the animation.

Such a solver does not break this contract. It falls **outside its domain**, and the extension it
would require is a wider `MotionSample`, not another threshold. Writing the boundary down now
means that arrival is a known extension point rather than a surprise that makes the contract look
wrong.

---

## 3. Decision — completion is a metric, a threshold, and a comparison

### 3.1 Why the current rule cannot stand

`PhysicsSpec.isFinished` today is `|1 - value| < restDelta && |velocity| < restVelocity`, read
from the instantaneous sample. For a decay that is sound. For a spring it is not, and the proof
is short.

An underdamped spring is `p(t) = 1 - A·e^(-ζωt)·cos(ω_d·t + φ)`. At every turning point
`velocity = 0`, so the velocity term is satisfied there whatever the amplitude — which is
precisely why the conjunction exists. But once the envelope has dropped below `restDelta`, the
rule reports **finished** at that turning point; a moment later the motion sweeps through its
target with `|velocity| ≈ amplitude·ω_d`, and the rule reports **not finished** again. With
`restDelta = 0.001` and `restVelocity = 0.01` that flip needs `ω_d > 10 rad/s`.

Against the shipped tokens, where `ω_d = √stiffness · √(1 - ζ²)`:

| token | stiffness | ζ | ω_d | flips? |
|---|---|---|---|---|
| `SPRING_BOUNCY` | 500 | 0.60 | 17.9 | yes, comfortably |
| `SPRING_GENTLE` | 400 | 0.85 | 10.5 | yes, marginally |
| `SPRING_SNAPPY` | 800 | 1.00 | — | no: critically damped, no turning points |

So the defect is real for two of the three and absent for the third, and `SPRING_GENTLE` sits
close enough to the boundary that a token tweak could hide or expose it. That is its own argument
against a rule whose correctness depends on where a design token happens to land.

The engine does not break, because it stops at the first frame that reports true and never asks
again. The consequence is subtler and worse: **the instant a spring finishes depends on which
frame lands near a turning point.** The same spring settles at different times at 60 Hz and at
120 Hz. That is not a RULE-009 violation — different frame times are not the same input — but it
is a property nobody would choose.

### 3.2 The rule

Completion is defined by a single monotone scalar:

```kotlin
sealed interface PhysicsSpec : AnimationSpec {
    val initialVelocity: Float
    val completionThreshold: Float

    /**
     * How far this motion still is from rest, in progress units.
     *
     * Must never increase while the sampler evolves. The contract states observable behaviour,
     * not a formula: an implementation is free to compute it from energy, from a decay envelope
     * or from anything else, provided a later sample never reports more than an earlier one.
     */
    fun completionMetric(sample: MotionSample): Float

    override fun isFinished(elapsedNanos: Long, sample: MotionSample): Boolean =
        completionMetric(sample) < completionThreshold
}
```

Three responsibilities that used to share one method now separate cleanly:

| | owns |
|---|---|
| `completionMetric` | the physics — what "distance from rest" means for this family |
| `completionThreshold` | the UX — how close is close enough |
| `isFinished` | a comparison, and nothing else |

No spec overrides `isFinished`, so no family can quietly redefine what finishing means.

### 3.3 Why "never increases" rather than "monotonic"

The contract states what the engine can observe: `metric(sampleAt(t₂)) ≤ metric(sampleAt(t₁))`
for `t₂ > t₁`, while the animation is still running.

The mathematical phrasing would be worse in a way that matters. "Monotonic" is a claim about the
whole domain, so a harness enforcing it literally must assert the inequality even where the metric
has fallen to 1e-9 — and there float32 rounds in both directions and the assertion flakes. Sprint
06A met this exact family of failure when `0.048f - 0.032f` came out as `0.015999999`.

Bounding the claim to *while the sampler evolves* confines it to the region above the threshold.
At 0.001 and above, float32 still carries seven significant digits and one ULP is around 1e-10,
far below any accumulated error. The observable phrasing is also the checkable one, and it needs
no tolerance constant that would have to be justified.

### 3.4 Spring's metric

For a damped oscillator with natural frequency `ω = √k`, write `x = 1 - value`, `v = velocity`:

```
metric = √( x² + (v/ω)² )
```

This is the amplitude the oscillation would settle at were damping to stop now, and its
monotonicity is proven rather than assumed. With `E = ½(v² + ω²x²)`, `ẋ = v` and
`v̇ = -ω²x - 2ζωv`:

```
dE/dt = v·v̇ + ω²x·ẋ = -2ζωv²  ≤ 0   for all t
```

Zero exactly at the turning points, negative everywhere else. This is what repairs the hole in
§3.1: at a turning point `v = 0` so the metric equals the true envelope, and between turning
points the `(v/ω)²` term supplies what the instantaneous displacement no longer accounts for.

`ω` comes from `SpringSpec.spring`, which the spec already holds. No new state, and no change to
`MotionSample`.

### 3.5 The units work out, and one threshold reads better than two

`v/ω` has units of `(progress/second) / (1/second)` = **progress**. The combined metric is
therefore in progress, as is a decay's, so one threshold covers every family and means the same
thing in each:

> `completionThreshold = 0.001` — the residual motion is under 0.1% of the animation's travel.

That sentence is intelligible on its own. `restVelocity = 0.01f` is not: nobody can say what it
implies without first being told the friction.

### 3.6 This does not disturb Sprint 06A.5

06A.5 deliberately moved completion off the sampler and onto the spec, on the grounds that every
alternative spring solver would otherwise reimplement the same rule. The envelope is derived from
the **model**, not from the method of solution — a closed-form spring and an RK4 spring share it —
so that reasoning holds unchanged and the metric stays on the spec.

---

## 4. Decay semantics — **not locked**

> The algebra below is settled. What is not is whether deriving `to` for a decay is a reasonable
> projection of that family onto `Animation`'s `(from, to)` model, or a sign that 06A unified one
> level too early. §2 appears to answer it — see §4.6 — but the question gets its own session
> before this section is treated as closed.

### 4.1 A decay does have a target; it is derived rather than supplied

Under exponential friction, which is what `DecaySpec.friction` describes:

```
v(t) = v₀·e^(-f·t)          d(t) = (v₀/f)·(1 - e^(-f·t))
```

Total travel is `D = v₀/f` — finite, and available in closed form at t = 0. Normalising against
it:

```
d(t)/D = 1 - e^(-f·t)
```

**`v₀` cancels completely.** Initial velocity decides how far a decay travels, not how it
travels; the shape is a function of friction and time alone. That single sentence is the whole
content of this section, and it is what makes the rest fall out.

So a decay's target is not absent, only derived:

```
to = from + v₀ / friction
```

ADR-002 called this reading circular — "where it stops is an output of the physics, so it cannot
be an input". That rested on an unstated assumption: that finding the resting position requires
simulating to it. It does not. For exponential friction it is one division, known before the
first frame.

### 4.2 What this buys

`MotionSample.value` keeps **one meaning across every family**: normalised displacement from
`from` toward the resting point, running 0 → 1. The contract states it once rather than per
family.

A decay's completion metric is then simply the fraction of travel remaining:

```
metric = 1 - value  = e^(-f·t)
```

which is monotone by inspection, and the existing `isFinished` override on `DecaySpec` disappears
along with the special case it encoded.

### 4.3 `from == to` stops being a hazard

Consistency check on the normalised velocity. The derivative of `1 - e^(-ft)` at t = 0 is `f`;
the caller-side conversion gives `v₀/(to - from) = v₀/(v₀/f) = f`. They agree.

The division by zero therefore occurs **if and only if `v₀ = 0`** — a fling released at rest,
which travels nowhere. That is not an edge case needing a rule; it is a precondition with a
name, and `DecaySpec` can reject it the way it already rejects non-positive friction.

### 4.4 The cost, stated plainly

`v₀/f` moves outside the sampler, to whoever constructs the `Animation`. The friction model then
lives in two places — the caller computing `to`, and the sampler computing `1 - e^(-ft)` — and if
they disagree the animation still runs, still looks smooth, and stops in the wrong place. Nothing
would report it.

Two mitigations, and the second is the real one:

1. The formula lives on the spec as `DecaySpec.restingDisplacement(v₀) = v₀ / friction`, so there
   is one implementation and the caller calls it.
2. ~~"The two agree" is exactly the statement that **`value → 1` as `t → ∞`**, which is a contract
   property the harness checks. It is enforced, not commented.~~
   **Retracted 2026-08-04, before Sprint 06B.2 began.** `assertConvergesToOne` takes a sampler and
   checks normalised progress, and a decay reaches 1 for any friction — the sampler never sees
   `to`, so the caller's half of the model does not pass through the property at all. The
   argument confused *the shape arriving* with *the shape arriving where the caller said it
   would*. Mitigation 1 stands; mitigation 2 was never real, so this hazard has been unguarded
   since 06B.0 and building its guard is the content of 06B.2.

### 4.5 The default is wrong, and this is the first sprint able to say so

`DecaySpec`'s defaults were documented as plausible rather than measured. Measured:

```
finished when e^(-ft) < 0.001  →  t = -ln(0.001)/0.5 ≈ 13.8 s
```

Nearly fourteen seconds for a fling to stop. Sprint 06B.0 replaces it with a value derived from a
stated settle time rather than chosen to look reasonable.

### 4.6 Open analysis — is `(from, to)` universal, or is it Spring's shape?

**Not part of the contract. Recorded so the reasoning is not lost, and so promoting it later is a
decision someone makes rather than a step someone skips.**

The worry is that deriving `to` for a decay hides an abstraction fixed one level too early: a
sampler needs only the travel `D`, an `Animation` needs only a target, and the two are stored as
one fact in two shapes.

**The halves are symmetric, so `(from, to)` is not Spring's shape.** `(from, to)` and `(from, D)`
carry identical information — `to = from + D`. Each family supplies one and derives the other:

| | caller supplies | derived |
|---|---|---|
| Spring | `to` | `D = to - from` |
| Decay | `v₀` | `D = v₀/f`, then `to` |

`Animation` holds an affine map, and every family needs one. §2 goes further: a solver in the
domain converges to rest, so its total travel is finite and the affine span exists. On that
reading §2 already answers §4, and no restructuring is called for.

**The real asymmetry is elsewhere, and it is smaller.** It is not that `to` is derived — it is
that a decay's `Animation` cannot be built without reading its spec, since `D` depends on `v₀`.
Sprint 06A kept `Animation` and `AnimationSpec` independent deliberately; that independence is
what keeps `samplerFor(spec)` single-parameter and ADR-002's additivity argument standing. A
spring's `Animation` can be built alone. A decay's cannot.

That is containable without touching either type. The place that legitimately knows both is the
factory — `AnimationService.fling(from, velocity, spec)` — which is already where `springTo`
performs the velocity conversion. The types stay independent; one convenience function knows both.

**Where the argument stops.** §2 says a solver in the domain converges. It does not say the
resting point can be *written down* at t = 0, and an earlier draft of this section proposed adding
that as a second clause. It should not be added, for a reason stronger than caution: as a
mathematical statement it is either vacuous or ill-posed. For any autonomous system with unique
solutions, the initial state determines the entire trajectory and therefore its limit — so the
resting point is *always* determined at t = 0. What can fail is having a closed form for it, and
that is a property of our notation, not of the system.

So the clause could never become a theorem of the contract. If it belongs anywhere it is an
engineering requirement — *Aurora needs the resting point computable before the first frame* —
and it should be written as one rather than dressed as a consequence of §2.

**Two findings that sharpen the search for a counterexample.**

Quadratic drag, `v̇ = -k·v²`, is not one. It integrates to `v = v₀/(1 + k·v₀·t)` and
`x = (1/k)·ln(1 + k·v₀·t)`: the velocity decays like `1/t` but the position diverges like `ln t`.
It never comes to rest, so §2 excludes it already, on convergence rather than on computability.

A resting point arises two different ways, and only one of them needs integrating:

| family | resting point is | found by |
|---|---|---|
| spring-like | an isolated equilibrium of the ODE | algebra — solve `ẋ = 0, v̇ = 0` |
| decay-like | wherever the trajectory stops | integrating the trajectory |

Every spring-like system therefore has a closed-form target, nonlinear ones included: the rest
point of `ẍ + cẋ + kx + βx³ = 0` is still the origin. The question only bites for decay-like
systems, where the answer depends on the path taken.

The strongest candidate found so far is **position-dependent friction**, `v̇ = -f(x)·v` — a scroll
whose resistance varies along its travel. Its state is `(x, v)`, it is autonomous, and it
converges, so §2 admits it. But `v(x) = v₀ - ∫₀ˣ f`, and the resting point is the root of
`∫₀ˣ f = v₀`: determined, and needing a root-find rather than a formula.

This is not yet a proof either way. It is enough to say the observation must not be promoted into
§2 in its current form, and that if it is ever promoted it will be as a stated engineering
constraint, not as a theorem.

---

## 5. What changes in existing code

Confined to two files, which is why now is the moment. `restDelta` and `restVelocity` appear only
in `AnimationSpec.kt` and `AnimationApiTest.kt`, and `AnimationService.springTo` is declared with
no implementation, so no caller performs the conversion yet.

- `PhysicsSpec` — `restDelta` and `restVelocity` out, `completionThreshold` and
  `completionMetric` in, `isFinished` derived
- `SpringSpec` — envelope metric; `ω` from its existing `spring` token
- `DecaySpec` — `1 - value` metric; `isFinished` override deleted; `restingDisplacement` added;
  `require(initialVelocity != 0f)`; default reconsidered
- `SnapSpec` — same threshold change, spring envelope metric against its chosen target
- `AnimationApiTest` — thresholds renamed, per the 06A.5 rule: *translate names and shapes, never
  translate expected values*

`initialVelocity` stays. For a decay it no longer shapes the motion, but it still determines `to`,
so it is not redundant — only relocated in meaning.

---

## 6. The harness

The harness does not check that completion is correct. It checks that **an implementation obeys
the contract**. Each property traces to one clause:

```
completionMetric must never increase   →   assertMetricNeverIncreases()
value → 1 as t → ∞                     →   assertConvergesToOne()
velocity is d(value)/dt                →   assertVelocityMatchesDerivative()
no sample is NaN or infinite           →   assertFinite()
same input, same output                →   assertDeterministic()
```

If a clause changes, its property changes with it. The harness tracks the contract, never a
solver. That is Sprint 06A's lesson stated as a rule: a test may not promise more than the design
does.

### 6.1 Two tiers

A **contract property** runs against every sampler; when it fails on all of them the semantics are
wrong, not the solver. A **solver property** — a spring oscillates around its target — runs
against one. Mixing them turns the harness into a demand that every solver behave like a spring.

Mis-filing is not symmetric. A solver property filed as contract fails loudly at the second
solver. A contract property filed as solver-only fails silently and forever: decay and snap simply
go unchecked and the harness stays green. So an unclear property goes in the contract tier, and
the harness reports which samplers each contract property actually ran against.

06B.0 assigns tiers with no solvers in existence, by argument alone. **Demoting a property to
solver-only later is the expected outcome, not a defect in this sprint.**

### 6.2 A harness that has never been red proves nothing

Every property here is *satisfiable* by a correct implementation, but no property is trusted until
a deliberately incorrect one is shown to violate it.

The distinction is the whole point. Saying a property holds "by construction" would make it a
consequence of the interface, and a consequence proves nothing when it passes. These are not
consequences: a monotone metric, a velocity that is the derivative of the value, convergence,
determinism — every one is a **requirement**, something an implementation can fail while still
compiling and still looking plausible on screen. A suite that only ever runs against correct
implementations cannot tell *property right, implementation right* from *property vacuous*. Both
are green.

Worse, 06B.0 ships no solver, so at the moment of delivery the harness has no subject at all.

So the sprint ships deliberately broken samplers in `tests/`, one per property, each proving its
property can go red for the stated reason:

| fixture | breaks |
|---|---|
| `IncreasingEnvelopeSampler` | metric never increases |
| `WrongDerivativeSampler` | velocity matches derivative |
| `NaNAfterConvergenceSampler` | finite |
| `NonConvergingSampler` | value → 1 |
| `TimeSeededSampler` | deterministic |

These are not solvers. They are wrong on purpose, live only in tests, and never enter `runtime/`,
so the sprint's "no solver" boundary holds. This is the same discipline every checker in 06A and
06A.5 was held to — the HashSet ban, the `kotlin.random.Random` ban and the zero-diff gate were
each shown to fail deliberately before being trusted.

---

## 7. Deliverables

1. `docs/contracts/motion-sampler-contract.md` — normative, prose, spanning sprints. A new
   directory: ADRs record why a decision was made, specs describe one sprint, and
   `contracts/*.contract` are layer rules `arch-test.sh` parses. None of those is "what every
   implementation of this kind must satisfy". The name avoids collision with the machine-read
   files.
2. ADR-002 amended: the circularity argument retracted, with the reason.
3. A new ADR for the domain boundary and the completion decomposition.
4. `PhysicsSpec` and its three subtypes reshaped.
5. The property harness, plus the broken fixtures that prove it can fail.

Every property in the contract document maps to a named assertion. Any property that cannot be
asserted says so plainly — a contract nobody checks is prose, and this sprint sequence has been
bitten twice by exactly that.

---

## 8. Deferred

**Snap.** If snap turns out to be target selection followed by a spring, then 06B.3 is a
`TargetSelectionPolicy` and not a solver at all — which would be evidence the abstraction was
found rather than imposed. That is knowable only once the spring exists and its generality can be
seen, so snap waits until after 06B.1. It receives the threshold change here and nothing more.

**Whether any family needs two thresholds.** The argument so far shows spring and decay do not.
It does not show that no future solver does. §2's domain boundary is the answer for state-space
reasons; whether a second scalar is ever wanted *within* that domain stays open, and the harness
would be where it first showed up.
