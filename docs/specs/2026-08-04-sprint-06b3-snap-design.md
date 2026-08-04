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

## What is *not* integration

```
selectedTarget ∈ targets
selectedTarget == policy(from, gestureVelocity, targets)
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
| 2 | `TargetSelectionPolicy` exists, with its own witnesses; not integration |
| 3 | `SnapFactory` composes them and satisfies the same assertion |
| 4 | §7, gates, `verify-motion-evidence.sh`, docs |

Task 1 before Task 2 is deliberate: applying the invariant to **spring** is the cheapest test of
Question 1, because spring's target is supplied and involves no new machinery. If one invariant
cannot cover the simplest case, nothing later will rescue it.

---

## Exit criteria

- [ ] `targets` documented as value units, with the circularity argument, not the conclusion alone
- [ ] The assertion renamed to describe what it checks
- [ ] `SpringFactory` and `SnapFactory` both satisfy it, **unchanged** — or the sprint records why
      a second invariant family became necessary
- [ ] Policy properties live outside `IntegrationContract`, with their own witnesses (RULE-015)
- [ ] `samplerFor` refuses nothing; gate 5 retires with its reason recorded rather than deleted
- [ ] `verify-motion-evidence.sh` renamed to `verify-motion-evidence.sh`, confirmed by a full VM run
- [ ] §7 shows every family end-to-end, or names precisely which is not
