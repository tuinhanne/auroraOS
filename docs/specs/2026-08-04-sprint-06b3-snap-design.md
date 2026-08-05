# Sprint 06B.3 — Snap

**Status:** design in review · 2026-08-04 · the last motion family

Two questions are answered before the sprint's subject is even named, and both are answered by
contradiction rather than by decision. What follows from them is that snap may need no new
integration invariant at all — a prediction this sprint exists to try to refute.

---

## Question 0 — what unit is `SnapSpec.targets`?

Open since Sprint 06B.0, and it must close first: until the unit is fixed, nobody knows where the
unit boundary is, whether the integration layer is involved, or in which domain an assertion
should be phrased.

**`targets` cannot be normalised progress**, and this is a contradiction rather than an
inconvenience:

```
normalised(x) = (x − from) / (to − from)
                                  ↑
                     does not exist while `to` is being chosen
```

Normalisation is *defined relative to the chosen target*. Interpreting a member of `targets` in
normalised space would require `to`, and `to` is what is being selected from `targets`. The
definition would depend on the result of the selection it feeds.

So **`targets` is in value units**, and that is not a design decision but a consequence of what
normalisation means. It is the same shape of argument that closed the decay question in 06B.0,
with one difference: there the alleged circularity turned out to be false, and here it is real.

---

## Question 1 — where does the integration layer begin?

> **Once `to` exists, however it came to exist.**

Three families produce it three ways, and all three finish **before** the unit boundary:

| family | how `to` is obtained | when |
|---|---|---|
| spring | supplied by the caller | before |
| decay | derived, `from + v₀/friction` | before |
| snap | selected from `targets` | before |

Past that point the pipeline sees one `(from, to)` pair and one velocity in value units to
normalise. The mechanism that produced `to` is not visible there and cannot be.

### Consequence: one integration invariant, not two

An earlier reading split the layer into *derivation invariants* (decay) and *selection
invariants* (snap). Question 1 says that split is wrong: selection happens before the boundary,
so the layer never observes it. What every family shares is the normalisation, and what differs
is a line of arithmetic upstream of it.

**This is the prediction the sprint tests.** If `SnapFactory` and a `SpringFactory` both satisfy
the existing assertion unchanged, one invariant covers supplied, derived and selected targets, and
the integration layer has found its abstraction. If either cannot, that is the evidence for a
second family, and the model grows then rather than now.

### The assertion carries the name of where it was born

`IntegrationContract.assertInferredTravelReturnsTheVelocity` described decay's mechanism, not what
it checks. *Inferred* is one of three ways `to` arrives, and the assertion never sees which.

Renamed to `assertTravelPreservesTheGestureVelocity`, on the same principle that moved this
sprint's predecessor from `AnimationService.fling` to `FlingFactory` and that will rename
`verify-sprint06b0.sh`: **name a thing by its role, not by its origin.**

---

## Task 1's result, recorded 2026-08-04

The stop condition fired, and it separated two claims that had been travelling together.

**Question 1 is confirmed.** Writing the spring case out algebraically:

```
spring:   v_normalised · (to − from)  =  v_gesture
decay:    friction     · (to − from)  =  v_gesture
```

These are the same equation. `friction` **is** a decay's normalised initial velocity — the
identity Sprint 06B.0 proved and `FlingFactory` depends on — so the decay form was a
specialisation of a general law, not a different law. One invariant covers supplied and derived
targets, and there is no reason yet to expect selected ones to differ.

**The byte-identical claim is refuted.** `assertTravelPreservesTheGestureVelocity` takes a
`DecaySpec` and reads `spec.friction`, and a spring has no friction. Its *signature* was written
to decay's special case, which decay itself could never reveal, because there the two quantities
coincide.

The distinction matters and the plan's stop condition exists to force it. Editing the assertion so
a spring passes would have been the failure the plan named. Widening it to
`PhysicsSpec.initialVelocity` — the quantity the law was always about — is the opposite: the
general form was there all along and one family's arithmetic had disguised it.

So the sprint amends the assertion's signature rather than its meaning, and the plan's exit
criterion changes from *byte-identical* to *the invariant unchanged, the signature generalised
with this as its reason*.

### The general lesson, which outlives this assertion

`DecaySpec.friction` was never what the integration layer wanted to check. It was **one family's
way of obtaining a quantity the law is actually about** — the normalised initial velocity — and
because those two coincide for a decay, the first subject could not distinguish them.

That generalises into something worth carrying to the next layer: **an assertion parameterised by
a quantity only one family owns is a specialisation waiting to be found.** It will pass every test
until a second family arrives, and the second family will look like the problem. Here it was not
Spring that was awkward; it was the assertion that had never been asked a second question.

---

## A prediction, recorded before Task 2 rather than after Task 3

Two witnesses now have a blind spot, and they look like different accidents:

| witness | silent at | because |
|---|---|---|
| decay forgets to divide | `friction == 1` | |
| spring forgets to normalise | `travel == 1` | |

They are the same point. In both, the normalising quantity equals 1, so the normalisation **is
the identity** — and a caller who skips an operation that does nothing is indistinguishable from
one who performs it.

> **Predicted:** a witness goes silent exactly where its normalisation degenerates to the
> identity. Snap normalises by `selectedTarget - from`, so its witness should be silent when the
> chosen target sits exactly one unit from `from`, and nowhere else.

### Three things the word "silent" was covering

Task 3 found the blind spot where this predicted, and found it wider than a point: a witness at
`travel = 1.0001` is silent too. That is not the prediction failing. It is the prediction being
about one thing while the observation reports another, and the two need separating before any of
this becomes a rule.

| | what it is | belongs to |
|---|---|---|
| **model degeneracy** | the single point where the normalisation *is* the identity — `travel = 1`, `normalisedInitialVelocity = 1` | the model |
| **oracle tolerance** | the finite band around it in which a discrepancy is smaller than the assertion can resolve | the harness |
| **witness silence** | what is actually observed: no red anywhere in that band | neither, on its own |

The degeneracy is exact and has no width. The band does, and its width is a property of the
oracle's tolerance rather than of the physics — tighten the tolerance and the band shrinks
towards the point, but never to it.

**A promoted RULE-019 would be about model degeneracy**, because that is the part a sprint can
reason about before running anything, and the part that stays true when an oracle is later made
more precise. Stating the rule in terms of tolerance would tie a law about models to a constant
in a test harness, which is the same mistake as an assertion parameterised by one family's
quantity — and this sprint has already corrected one of those.

Written down now, before `SnapFactory` exists, because a prediction recorded afterwards is
indistinguishable from a description. If Task 3 finds the blind spot where this says it will be,
that is a third instance and the pattern becomes a candidate for RULE-019. If it is somewhere
else, or absent, the pattern is wrong and two coincidences were being read as a law.

Either outcome is worth having; only one of them is worth having *after* the fact.

---

## Question 2 — does `gestureVelocity` belong to the policy, or only to its caller?

Opened by Task 2 before a line of it was written, and it must close first for the same reason
Question 0 did: the plan gave `TargetSelectionPolicy` a signature — `(from, gestureVelocity,
targets)` — and a property — *it returns what the policy specifies* — without ever saying what the
velocity does. The two candidate answers do not differ in detail. They put the selection rule in
different layers.

### The third reading is refuted before it is weighed

One answer is that the velocity sits in the signature and the rule ignores it — `nearest(from)`,
with the parameter kept for a policy that might want it later. That is not a design one may simply
prefer against; it is unsatisfiable.

The property to be witnessed is *the policy returns the specified target*. Its natural witness is
**a policy that ignores the gesture velocity** — and under this reading that witness **is** the
correct policy. There is nothing left to be red. Property 2 loses its witness set entirely, and
RULE-015 is not merely awkward to satisfy but impossible.

Note the shape: this is not the degenerate point the prediction above describes, where a witness
falls silent at one configuration. It is a witness silent everywhere, because the error it is
supposed to embody is the behaviour under test.

### Velocity inside the policy costs the layer its independence

The remaining readings are that the velocity is used, or that it is not there.

Used means projected: `nearest(from + restingDisplacement(v))`. It reads well, and it reuses the
single implementation of the friction model that `FlingFactory` already depends on. It also defines
the policy in terms of **one family's physics**, which is the specialisation Task 1 just finished
paying to remove from the assertion. And `SnapSpec` has no friction. Supplying one means a spec
carrying two models where only one governs the motion — the second consumed by a single line of
arithmetic that no sampler runs and no contract observes.

### So the projection moves up, and the policy becomes a pure selector

```
TargetSelectionPolicy.nearest(candidate, targets)
```

`from` leaves the signature with the velocity, and for the same reason: nearest is measured from
the candidate, so `from` has no part in the rule either. What remains is a total function of two
arguments with no physics in it.

**A second question follows immediately, and it decides where `candidate` comes from.** If
`SnapFactory` computes it, `SnapFactory` becomes the **owner of the projection rule**. The policy
layer stays pure and the integration layer gains semantics no contract observes: which rule was
used, and why, becomes a property of a factory that no assertion can see.

A draft of this argued it from friction instead — `SnapSpec` has none, so a factory projecting
through `restingDisplacement` would carry a field for arithmetic nothing checks. True, and too
narrow: it holds only while the projection is a decay. `from + v`, `from + αv`, or whatever a
gesture system prefers leaves the friction argument with nothing to say and the ownership argument
untouched. The narrower version was doing work the general one already does, which is how a
conclusion comes to look better supported than it is.

The projection is therefore not pushed into the factory but out of the sprint: `candidate` is an
**input**, in value units, from whoever measured the gesture.

```
gesture  →  candidate  →  policy  →  selectedTarget  →  SpringFactory  →  SpringSampler
             (input)     (new, policy)   (Task 1)        (unchanged)
```

This keeps the three-family table of Question 1 honest. Snap's target is still *selected* rather
than supplied — a candidate is not a target, and the selection is what turns one into the other.

### Ties

`nearest` needs a rule for exact ties — `targets = [0, 10]`, `candidate = 5` — and cannot use the
gesture direction to break them, having just given up the velocity. **The lower target wins.**

The goal is determinism **across equivalent target sets**, not that *lower* means anything: two
callers holding the same targets in different orders must select the same target, and *lower* is
the cheapest intrinsic rule that guarantees it. Deliberately not list order, which is the caller's
accident — a policy whose result depends on it is not a function of the set it claims to select
from, and that is the property the witness checks.

### Named gap: projection provenance

> **Projection provenance.** No assertion verifies that `gesture → candidate` implements the
> intended projection rule. Layer: **none of the three** — the step sits above integration, in a
> caller that owns a gesture, which this sprint does not have and does not open.
> Opened by Question 2, 2026-08-04.

Given a name rather than a paragraph, because §7 already draws the distinction this depends on: a
clause with no assertion must say so, or it is an unverified claim that happened to be true. Prose
saying "upstream" is exactly the form in which the 06B.0 convergence claim survived three sprints
without anyone noticing it observed nothing.

This is the real price of the answer, and the alternative was worse: pulling a physics model into
the policy layer purely so that something would be there to check. Task 4 carries the row into §7
rather than leaving it here.

**The prediction is unaffected.** Snap still normalises by `selectedTarget - from`, so the blind
spot recorded before Task 2 remains exactly where it was written.

---

## What is *not* integration

```
selectedTarget ∈ targets
selectedTarget == nearest(candidate, targets)
```

Both are properties of the selection function. Neither crosses a unit boundary, needs a pipeline,
or involves a sampler, so by the layer definition in §7 of the motion contract they are **policy
properties** — ordinary unit tests of a pure function. They do not extend `IntegrationContract`,
and putting them there would dissolve the distinction the layer was created to make.

---

## What snap is, if the above holds

Not a solver. After a target is chosen it **is** a spring, and `SpringSampler` is already
production-verified. So:

```
TargetSelectionPolicy  →  SpringFactory  →  SpringSampler
   (new, policy layer)     (new, integration)   (unchanged)
```

Sprint 06B.3 would then be the first sprint in which the **solver layer does not change at all** —
which is what 06B.0 predicted when it declined to design snap in advance, and evidence that the
abstraction was found rather than imposed.

---

## Task order

| task | stops when |
|---|---|
| 0 | `SnapSpec.targets` documents its unit; the assertion is renamed |
| 1 | `SpringFactory` satisfies the renamed assertion — testing Question 1 on a *supplied* target |
| 2 | Question 2 is closed; `TargetSelectionPolicy` exists, with its own witnesses; not integration |
| 3 | `SnapFactory` composes them and satisfies the same assertion |
| 4 | §7, gates, `verify-motion-evidence.sh`, docs |

Task 1 before Task 2 is deliberate: applying the invariant to **spring** is the cheapest test of
Question 1, because spring's target is supplied and involves no new machinery. If one invariant
cannot cover the simplest case, nothing later will rescue it.

---

## Exit criteria

- [ ] `targets` documented as value units, with the circularity argument, not the conclusion alone
- [ ] The assertion renamed to describe what it checks
- [ ] `SpringFactory` and `SnapFactory` both satisfy the same **invariant**; any change to the
      assertion's signature carries the argument that it states the same law in a quantity no
      single family owns
- [ ] Policy properties live outside `IntegrationContract`, with their own witnesses (RULE-015)
- [ ] `TargetSelectionPolicy` takes no velocity and no `from`, with the argument recorded — a
      signature that keeps a parameter the rule does not use is refuted by RULE-015, not merely
      untidy, and the unverified provenance of `candidate` is named rather than papered over
- [ ] `samplerFor` refuses nothing; gate 5 retires with its reason recorded rather than deleted
- [ ] `verify-motion-evidence.sh` renamed to `verify-motion-evidence.sh`, confirmed by a full VM run
- [ ] §7 shows every family end-to-end, or names precisely which is not

---

## Question 3 — opened here, answered elsewhere

Task 4 opened a question before its first line: does RULE-015 identify a witness by its syntactic
form, or by its role? It is recorded in this spec's history as the thing that stopped Task 4, but
it is not a question about snap. It asks what RULE-015 *means*, and its answer binds every layer
these rules reach.

Relocated unchanged to [`docs/evidence-model.md`](../evidence-model.md), which is where a question
about the evidence model belongs and where any later sprint will look for it, and **answered there
on 2026-08-05: by role.** The counterexample was Task 2's policy witnesses, which have no
syntactic form to identify at all.

What that leaves for Task 4 is smaller than what opened it. Neither repair this spec listed is
taken: gate 4 stops identifying a witness by shape, and stops identifying an assertion by which
file it sits in. `IntegrationContract` being invisible to the gate was a symptom of the second,
not a gap to be patched.
