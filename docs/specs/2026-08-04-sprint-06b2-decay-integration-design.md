# Sprint 06B.2 — Decay integration

**Status:** design in review · 2026-08-04 · opens the integration evidence layer

> **Sprint 06B.2 does not prove a new motion property. It proves, for the first time, that a
> property survives the transition across an inferred-unit boundary between caller and sampler.**

06B.1 asked *does this spring satisfy the contract?* This asks *does the contract still hold after
a derivation that happens outside it?* — a different question, and the reason this is a sprint
rather than a task.

---

## 1. The six questions §7 requires answering before any code

### 1.1 What is the production subject?

Not `AnimationService.fling`. That is where it will live; if the class is renamed the answer must
not change.

> **The first pipeline in Aurora whose target is *inferred* rather than supplied by its caller.**

Every family before this received `to` as an input. A decay computes it, and that computation is
the subject.

#### Amended 2026-08-04, during Task 2

`AnimationService` has **no implementing class**. It is an interface; `springTo` was declared in
06A and never implemented, and `AuroraRuntime.animation()` resolves it from a registry nothing
populates. So `fling` had nowhere to live, and building somewhere would have meant a
`DefaultAnimationService` — an animator, a controller, callback wiring, a service lifecycle. That
is a different sprint, and it is not what this one is about.

The definition above survives it, which is why it was written without a class name. The derivation
lives in `runtime/animation/FlingFactory` instead: still the caller, still on the value-unit side
of the boundary, and `IntegrationContract` needs no change at all. `AnimationService.fling` is
declared alongside `springTo` and stays unimplemented until a sprint that builds the service.

Worth noting how this was caught: not by a red test, but by asking whether the named subject could
be built. The evidence model says plainly that it cannot generate that question.

### 1.2 Where does the pipeline begin and end?

```
gesture velocity, in value units          ← begins here
        │
        │  ◄── the unit boundary: to = from + v₀/friction
        ▼
Animation(from, to) + DecaySpec
        │
        ▼
DecaySampler, in normalised progress
        │
        ▼
AnimationHandle.value, in value units     ← ends here
```

Both contract tiers observe only the third box. Everything the sprint is about happens at the
arrow above it.

### 1.3 Where is the unit boundary?

At `to = from + v₀/friction`, and nowhere else. It is a boundary rather than a step because two
different unit systems meet there and the conversion has to be an exact inverse in both
directions.

### 1.4 What is the first integration assertion?

An earlier draft phrased it as *"the target the caller inferred equals the target the sampler
assumes"*. That is **trivially true and cannot fail**: a sampler has no target, it runs normalised
progress to 1, and `valueAt(1) == to` was made exact in 06A. An invariant that cannot go red is
not an invariant.

The operational form was already derived in Sprint 06B.0 and not recognised as an integration
statement:

```
(to − from) · friction  =  v₀          both sides in value units
```

Written as a product rather than as `v₀/(to − from) = friction`, on three grounds. It introduces
no division, so `to == from` needs no special case. It reads as a conservation law — *the inferred
travel, scaled by friction, must return exactly the velocity the gesture supplied*. And a witness
that forgets the division lands off by exactly a factor of `friction`, which is a signature rather
than a discrepancy.

It uses only quantities each side genuinely owns, and it does not restate `v₀/friction`, so it
does not reproduce what it verifies (RULE-016).

#### What it checks, and what it does not

If `fling` derives `to` by calling `DecaySpec.restingDisplacement(v)`, then
`(to − from)·f = (v/f)·f = v` is an **identity**, true for every friction. So the assertion does
not prove the friction model correct. It proves that **`fling` routes through the single
implementation of that model** — which is exactly what the witness violates, and exactly what the
integration layer exists to observe.

Stated so nobody reads a green run as more than it is. That `v₀/friction` is the right formula was
established separately in Sprint 06B.0, by `aDecaysNormalisedInitialVelocityIsAlwaysItsFriction`
in the SDK tests, and is not re-proved here.

#### Premise: one source of friction

The assertion assumes the friction used to derive `to` and the friction on the `DecaySpec` handed
to the sampler are **the same value from the same place**. That holds in the design as it stands:
`fling` receives one `DecaySpec` and reads `friction` from it once.

Recorded because it is a premise rather than a fact about the invariant. Should a later pipeline
admit two sources — a gesture configuration, a design token, a runtime override — the assertion
stays true but stops being sufficient, and its witness would need to grow a second case in which
the two sources disagree while each is individually plausible.

### 1.5 What is the first witness?

A `fling` that omits the division:

```
to = from + v₀            instead of      to = from + v₀/friction
```

Declared red set:

| | |
|---|---|
| solver layer | **green** — `DecaySampler` is untouched and correct |
| physics tier | **green** — normalised progress still reaches 1 for any friction |
| integration | **red** |

That signature is the entire reason the layer exists. If the first two are not asserted green, the
witness proves only that something is wrong.

### 1.6 Why is it certainly red?

```
correct:   (to − from) · f  =  (v₀/f) · f  =  v₀
witness:   (to − from) · f  =  v₀ · f
```

The two differ by a factor of `f`, so the discrepancy is `v₀(f − 1)` — independent of sampling
rate, of tolerance, of elapsed time, and of how the motion is probed. **Except at `f = 1`, where
it is exactly zero.**

---

## 2. A witness is worthless at a degeneracy point

The `f = 1` case is not a footnote. At that value the correct pipeline and the broken one produce
**identical observations**, so the witness proves nothing there — and would quietly prove nothing
if someone later changed the default friction to 1.

This is the same failure 06B.1 met in a different coordinate. There the envelope witness was
swamped because the property's 10ms grid could not resolve a second-order rise; the witness was
inside the oracle's blind spot. Here the witness is inside a **parameter** blind spot. One is
resolution, the other is degeneracy, and both make a witness silent.

> **A witness has value only where the correct and the incorrect implementation are
> observationally distinguishable.** Its parameters must be chosen away from every point at which
> the two hypotheses predict the same observation, and the spec must name those points rather than
> leave them to be discovered.

For this witness the excluded point is `f = 1`, and the sprint runs it at the default `f = 4.6`,
where the discrepancy is `3.6·v₀`.

This principle now has two instances. It is a **candidate for RULE-019** and is deliberately not
promoted yet: the rules that have held up were forced by subjects rather than invented ahead of
need, and 06B.2 will say whether a third instance exists.

---

## 3. What changes in §7

**No clause moves from pending to verified.** Decay has no pending cell — its clauses were
analytic-verified in 06B.0 by `DecayTrajectory`.

What changes is qualification. Decay's `domain` moves from *normalised* to *end-to-end*, and the
`integration` row of the layer table stops being empty. The sprint delivers **a new column of
qualification, not a new green cell**, which is what makes it a different kind of sprint from
06B.1 rather than the same kind applied to a second family.

`DecaySampler` itself adds nothing to `provenance`: its closed form is the same three lines as
`DecayTrajectory`, so moving it from `tests/` to `runtime/` produces no evidence the contract does
not already hold. That is why it is not Task 1.

---

## 4. Task order

| task | stops when |
|---|---|
| 1 | the integration assertion exists and its witness has been shown red, with the solver and physics layers green |
| 2 | `AnimationService.fling` satisfies it |
| 3 | `DecaySampler` ships and `samplerFor` stops refusing `DecaySpec` |
| 4 | §7 records the new domain and layer; gate 5 narrows to snap alone |

Task 1 is not the solver, and that is the structural difference from 06B.1. RULE-018 requires it:
the integration layer has never existed, so its assertion and its subject would otherwise arrive
together and a green run would carry no information at all.

---

## 5. Exit criteria

- [ ] The integration assertion is phrased as `(to − from) · friction = v₀`, with no division
- [ ] Its witness is shown **red** before `fling` is written, with the solver and physics layers
      asserted **green** on the same witness
- [ ] The witness runs at a friction where `f ≠ 1`, and the spec's excluded point is named in the
      test rather than only here
- [ ] `samplerFor` refuses only `SnapSpec`; gate 5 updated with its reason, not deleted
- [ ] §7 shows decay as end-to-end in the domain dimension, and the integration layer non-empty
- [ ] The full suite and both verify scripts green on the VM
