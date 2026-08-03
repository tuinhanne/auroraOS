# Sprint 06B.1 — Spring

**Status:** design in review · 2026-08-03 · first solver against the Aurora Physics Contract

The sprint does not ask what a correct spring is. Sprint 06B.0 closed that question, so the
opening question here is **"does this spring satisfy the contract?"** — which has a stopping
condition, unlike the one 06A answered wrongly three times.

It therefore opens at §7 of `docs/contracts/motion-sampler-contract.md`, not in an editor. Every
row of that table is a proposition waiting for its first real subject, and the sprint's value is
measured in how many move from *unverified* to *verified* or to *revised with cause* — not in the
existence of a spring.

---

## 1. Four tasks

| task | risk retired |
|---|---|
| 1 | Is the closed form right, and does it survive the sampler tier? |
| 2 | Can the physics properties **fail** on a spring, or do they pass by construction? |
| 3 | Does the real spring satisfy them, and if not, what does the diagnostic rule say? |
| 4 | Which §7 rows moved, and to what? |

**Tasks 1 and 2 of the proposed roadmap are one task.** The exit criterion for "analytic model
done" was *SamplerContract green*, and every assertion in that harness takes a `MotionSampler` —
so there is no observable state in which the model is finished but no sampler exists. Splitting
them would leave the first task with no criterion that could be checked. One task: the solution,
its adapter, and the sampler tier.

The order matters and is the same principle 06B.0 established: **Task 2 comes before Task 3.** A
property is not evidence until it has been shown able to reject something. Running the real
spring first and finding green would leave the sprint unable to say whether the contract holds or
the property is vacuous.

---

## 2. Critical damping is not a special case — and it need not be a branch

`SPRING_SNAPPY` is `Spring(stiffness = 800f, dampingRatio = 1f)`. **Exactly ζ = 1.** The
critically damped solution is not an edge case handled for completeness; it is a shipped design
token, and the most common one in the system.

The obvious implementation is three branches selected on ζ, and it is a trap. The underdamped
solution divides by `ω_d = ω√(1-ζ²)` and the overdamped one by `ω_h = ω√(ζ²-1)`; **both diverge
as ζ → 1**, so a naive branch on `ζ == 1f` is correct only at the exact float value and loses
significant digits either side of it. The failure is not an exception — it is silent precision
loss, visible only to `assertVelocityMatchesDerivative` and its 5% tolerance.

### The singularity is removable, so write it that way

Let `y = 1 - value` be the displacement still to cover, so `y'' + 2ζω y' + ω² y = 0` with
`y(0) = 1` and `y'(0) = -v₀`. For ζ < 1:

```
y(t) = e^(-ζωt) · [ cos(ω_d t) + (ζω - v₀) · sin(ω_d t)/ω_d ]
```

Every ζ-dependent denominator appears **only** inside `sin(ω_d t)/ω_d`, and that quantity has a
finite limit: as `ω_d → 0` it tends to `t`. Substituting it gives

```
y(t) = e^(-ωt) · [ 1 + (ω - v₀)·t ]
```

which is exactly the critically damped solution `(A + Bt)e^(-ωt)`. **The critical case is not a
separate formula; it is the limit of the underdamped one.** For ζ > 1 the same expression holds
with `cos → cosh` and `sin(ω_d t)/ω_d → sinh(ω_h t)/ω_h`, which has the same limit.

So the decision the spec makes, rather than leaving to an implementer:

> Two branches on `ζ < 1` versus `ζ > 1`, each evaluating `sin(z)/z` (respectively `sinh(z)/z`)
> through one helper that switches to its Taylor series `1 - z²/6 + z⁴/120` below a stated `|z|`.
> There is **no third branch for ζ = 1**, because there is no third formula.

This turns an architectural question — where to put the band, and what happens inside it — into a
standard numerical one: the Taylor cutoff, chosen where the series and the direct evaluation
agree to within float32 precision. That cutoff is a constant with a derivation, not a guess, and
Task 1 states it.

The three shipped tokens then exercise all of it: `SPRING_BOUNCY` (ζ = 0.6) and `SPRING_GENTLE`
(ζ = 0.85) take the sin branch, `SPRING_SNAPPY` (ζ = 1.0) lands exactly on the removable
singularity, and a test spring above 1 takes the sinh branch.

---

## 3. The properties must be shown to fail on a spring first

This is the change that restructures the sprint, and it comes from a hazard 06B.0's rules do not
cover as written.

`SpringSpec.completionMetric` is `√(x² + (v/ω)²)` with `ω` from `spring.stiffness`. The spring
trajectory will take `ω` from **the same token**. So the metric and its subject are not
independent — they rest on one derivation. A correctly written closed form makes the metric
exactly `A·e^(-ζωt)`, monotone **by construction**, and `assertMetricNeverIncreases` goes green
without having examined anything.

Marking a §7 row *verified* on that run would be the exact failure 06B.0 built RULE-015 to
prevent, arriving through a door RULE-015 does not watch.

### This is a distinct hazard from RULE-016, and the rule should say so

RULE-016 forbids a property **reproducing the implementation** it verifies — a central difference
checked by a central difference at the same step. Here the metric does not reproduce the
trajectory; it consumes its output. What they share is a **derivation**: the expected behaviour
and the subject fall out of the same piece of algebra.

The distinction matters for how the rule ages. *Shared modelling assumption* would be too wide:
`trajectory → energy decay` and `trajectory → phase continuity` rest on the same oscillator and
are entirely independent derivations, and a rule that caught both would forbid most useful
physics testing. What is disqualifying is narrower — that the expected outcome is
**mathematically entailed** by the construction that produced the subject, so the property cannot
come out any other way.

Proposed amendment, for review rather than applied here, since RULE-016 shipped in Sprint 06B.0:

> **RULE-016 (extended).** A property must not reproduce the implementation it verifies, nor
> derive its expected behaviour from the same mathematical construction that produced its
> subject — unless it has first been validated against a subject built to violate it.

The last clause is the escape hatch this sprint needs, and the reason Task 2 exists: a spring's
metric and a spring's trajectory *must* share `ω`, so the entailment cannot be designed away. It
can only be paid for, with a subject that breaks the entailment and makes the property speak.

### Task 2: property validation on the Spring family

Not a negative test suite. Its subject is the **property**, not the spring. It ships at least:

| deliberately wrong spring | targets | expected red set |
|---|---|---|
| envelope decays at `ω_d` where it should decay at `ω_n` | the metric's monotonicity | physics tier only |
| velocity omits `ζ` from the derivative | the derivative property | **both tiers** — see below |
| overdamped branch taken for `ζ < 1` | branch selection | **both tiers** |
| Taylor cutoff set absurdly wide | §2's numerical constant | sampler tier only |

Each lives in the test tree beside the 06B.0 fixtures, each is declared in the RULE-015 pairing
block, and each must be shown red **before** Task 3 runs the real spring. A property that has
never been red *on this family* has not been shown to check anything *for this family*.

The last row is worth its own note: it is the only check on §2's Taylor cutoff, and it is what
stops that constant from being widened later to make something else pass.

### Orthogonality is required where it is achievable, and named where it is not

A fixture that breaks three things at once proves only that *something* is wrong, which defeats
the point of validating properties one at a time. So each wrong spring must **name the single
assumption it targets** — and, where it is achievable, fire exactly one property.

Achieving it takes more than changing one line: the fixture has to stay **internally consistent
in every dimension except the one under test**. The first row is the model. If it uses `ω_d` in
the exponent *and* reports the true derivative of that wrong position, then velocity agrees with
value, the sampler tier passes, and only the metric objects. Change the position without fixing
the velocity and both tiers fire, and the attribution is gone.

Two rows cannot be made orthogonal, and the honest thing is to say so rather than to engineer
around it:

**A wrong velocity necessarily disturbs the metric**, because `completionMetric` reads velocity.
There is no version of "velocity is wrong" that leaves `√(x² + (v/ω)²)` untouched. Tuning the
error to exceed the sampler tier's 5% tolerance while staying inside monotonicity would be
possible and would be worse — a fixture balanced on a tolerance is a fixture that stops working
the day either number moves.

**A wrong branch changes both the position and its derivative**, so it is coupled for the same
reason and more strongly.

For those two, attribution comes from the **shape** of the red set rather than its size, which is
the discriminator §5's diagnostic rule already relies on: both tiers red points at the spring,
physics tier alone points at the metric or the contract. That distinction survives coupling. What
would destroy it is the reverse case — a fixture whose red set is *smaller* than intended, which
is why each expected red set is written down here and asserted rather than observed after the
fact.

---

## 4. §7 becomes a matrix, because verification is a property of a pair

*Verified* is not a property of a contract clause. It is a property of `(clause, subject family)`.
Sprint 06B.1 gives the spring a trajectory subject; snap's sampler does not exist until 06B.3, so
its rows cannot move on the same evidence.

| clause | Spring | Decay | Snap |
|---|---|---|---|
| §2 convergence | 06B.1 | verified 06B.0 | pending its own sampler |
| §4 completion metric | 06B.1 | verified 06B.0 | pending its own sampler |

Snap shares the spring's formula, and `aSnapMeasuresRestExactlyAsItsSpringWould` keeps the
duplicate from drifting — but a matching formula is not the same as a formula verified along a
trajectory, and the table must not blur them. Forcing a binary would mean either marking snap
verified on the spring's evidence or leaving the spring unverified after it was verified. Neither
is true.

---

## 5. If Task 3 goes red

The rule is already in the repository, written before this spec and before any spring existed
(`3014763`, contract §7.1). It is quoted rather than restated so it cannot drift:

> If an implementation satisfies the sampler tier's independent invariants but fails a
> physics-tier property, investigate the contract and its metric before changing the
> implementation. Reverse the presumption only on independent evidence against the implementation.

Task 2 is what makes that rule usable here. Without it, "the property rejected the spring" and
"the property rejects everything" are indistinguishable, and the rule would point at the contract
on evidence that could not support it.

---

## 6. Open questions, untouched

**`(from, to)` universality.** Predicted not to move. A spring receives `to` from its caller and
derives nothing, so it is the family least likely to produce evidence either way. If evidence
comes it will come from 06B.2 or 06B.3.

**Two thresholds.** No spring-side reason to revisit is expected; the envelope is a single scalar
and the spring is what motivated it.

**Snap's existence as a solver.** Answerable only after this sprint shows how general the spring
turned out to be, which is why §4 leaves its rows pending rather than guessing.

---

## 7. Exit criteria

- [ ] One closed form covering ζ < 1 and ζ > 1 with no third branch, and a stated Taylor cutoff
      with its derivation
- [ ] All three shipped spring tokens sampled, including `SPRING_SNAPPY` at exactly ζ = 1
- [ ] Every physics property shown **red** against a deliberately wrong spring, before the real
      one is run
- [ ] Each wrong spring declared in the RULE-015 pairing block
- [ ] Each wrong spring names the **single** assumption it targets, is internally consistent in
      every other dimension, and has its **expected red set asserted** — not observed afterwards.
      Where coupling makes one property unavoidable, §3 records why; a red set larger than
      declared is a defective fixture, and one smaller is a defective property
- [ ] `AnimationHandleImpl.samplerFor` no longer refuses a `SpringSpec`; the refusal count drops
      from one to one-minus-spring, and the verify script's gate 5 is updated to match
- [ ] §7 of the contract restated as a matrix, with the spring column filled and the reason for
      any revision recorded
- [ ] The full suite and both verify scripts green on the VM
