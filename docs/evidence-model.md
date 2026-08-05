# How Aurora Establishes Evidence

**Descriptive, not normative.** This document adds no rules. It collects what Sprints 06B.0 and
06B.1 arrived at into one shape, so a later sprint can apply it without reading their history.

Everything here was derived from work already done. Where it seems to prescribe, the prescription
is RULE-015 to RULE-018 in `frameworks/base/aurora/README.md`; this only says how they fit
together.

---

## The chain

```
Expectation  ──►  Named gap  ──►  Witness  ──►  Assertion  ──►  Verified evidence
     ▲                                                                  │
     └──────────────────────────────────────────────────────────────────┘
```

**Expectation** — a stated belief about what should happen. *"Task 1 will make gate 5 fail."*
Not a test. It is evaluated by watching, and it is refuted by an observation that does not match.

**Named gap** — an expectation written down so it outlives the moment. §7 of the motion contract
is a list of these. An empty row with a reason beside it is not a shortcoming; it is a question
the framework cannot yet answer, recorded so nobody has to think of it again.

**Witness** — a subject built to violate an assertion, proving the assertion can reject.

**Assertion** — a check that runs, persists, and refuses.

**Verified evidence** — a green run that carries information, because everything upstream of it
was established first.

### Why the loop matters more than the chain

Verified evidence produces new expectations. Sprint 06B.1 overturned five stated beliefs, and
each one became either an assertion or a named gap before the sprint closed.

### Assertions persist; expectations are consumed

An assertion sits in the repository and runs on every build. An expectation exists for as long as
someone holds it, catches what it catches, and evaporates.

Two of 06B.1's five findings — a gate that could never fire, and a normative claim nothing
enforced — were caught by expectations and by nothing else. No harness could have found either,
because both reported exactly what "nothing is wrong" reports.

So: **when an expectation catches something, convert it before it evaporates.** Into an assertion
if it can be made to run; into a named gap if it cannot. Gate 5 became an assertion. The unit
boundary became a named gap. Neither depends on anyone thinking of it a second time.

---

## What the rules govern

| rule | governs | fails how |
|---|---|---|
| **RULE-015** | a contract property must have a witness | the manifest by machine, gate 4; the pairing by review |
| **RULE-016** | a property must not reproduce, or share a derivation with, what it verifies | review |
| **RULE-017** | a property may only be asserted where its justification holds | review |
| **RULE-018** | a new evidence layer must be calibrated before a pass counts | review |

They sit at different points on the chain. RULE-015 governs *witness → assertion*: no assertion is
trusted without one. RULE-016 governs the relationship between an assertion and its subject —
independent enough that a green run means something. RULE-017 governs where an assertion may be
pointed: `dE/dt = -2ζωv²` holds along solutions of a particular equation and nowhere else, so a
property resting on it may only be asked about subjects that satisfy its premises. RULE-018
governs the chain's *first* pass through a layer that has never existed, where the assertion and
its subject arrive together and nothing independent remains.

Three are enforced entirely by review, and say so. Whether two computations are the same idea,
whether a subject satisfies a theorem's premises, and whether a layer is new are not things a
script can decide, and a gate pretending otherwise would be worse than none.

RULE-015 is the mixed case. A script can check that a pairing has been *declared* for every
assertion; it cannot check that the named artifact does any witnessing. Where that line falls, and
why it falls there rather than where gate 4 first drew it, is the subject of Question 3 below.

---

## Three ways evidence is qualified

"Verified" alone hides more than it says. Three orthogonal dimensions:

| dimension | question | values |
|---|---|---|
| **layer** | what does the assertion observe? | solver · integration · endpoint |
| **provenance** | where did the evidence come from? | analytic · production |
| **domain** | where was it gathered? | normalised · end-to-end |

None substitutes for another. A clause can be production-verified in the solver layer and never
have been observed in the end-to-end domain — which is the state the motion contract is in today,
and what Sprint 06B.2 exists to change.

Provenance matters most where its two values coincide. A decay's closed form is three lines, so
its production sampler will be the same expression as the analytic subject that already verified
the contract: moving it from `tests/` to `runtime/` adds nothing. A spring's was not, and its
production subject contradicted the spec four times.

---

## Two failure modes this model exists to prevent

**A property that checks nothing.** It passes, it looks like coverage, and it is indistinguishable
from a property everything satisfies. RULE-015's witness is the answer.

**A pass that carries no information.** The assertion and the subject were built together, or
derive from the same algebra, or the harness was tuned until the result came out. RULE-016 and
RULE-018 are the answer, and the discipline is always the same: *establish that the check can
refuse before letting it approve.*

---

## What this model cannot do

It standardises how evidence is gathered, how it is read, and when it is strong enough.

**It cannot generate the hypotheses to test.** Nothing here would have produced the question
*"what is 06B.2's real subject?"*, and that question is what exposed a normative claim no
assertion could keep. Deciding what to ask remains design work, and the named gaps in §7 are the
only mechanism that carries a question forward once someone has thought of it.

---

**The question below was relocated from the Sprint 06B.3 spec unchanged; the answer was written
here.** It was opened by that sprint's last task, but it is not a question about snap: it asks
what RULE-015 means by *witness*, and the answer binds every layer these rules reach rather than
one motion family.

## Question 3 — does a witness have identity by syntactic form, or by role?

Opened by Task 4 before its first line, and it stands in front of a choice that looked like the
whole problem.

`verify-motion-evidence.sh` gate 4 does not know `IntegrationContract` exists, so RULE-015 is
enforced by review at that layer and by machine at the other two. The obvious fixes are to make
the integration witnesses classes in `BrokenSamplers.kt`, or to teach the gate about functions.
**Both are wrong for the same reason:** each keeps identifying a witness by the shape it is
written in, and each will need doing again when 06C produces a witness that is a table, a
generated fixture or a property source.

> **The question to close first:** does RULE-015 identify a witness by its syntactic form, or by
> its role — *an artifact deliberately constructed to violate one assertion*? Under the second
> reading, class, function, object and generated fixture are all representations, and a gate that
> recognises one of them has confused a representation for the thing.

### The manifest already half exists

Worth knowing before the question is answered, because it shortens the answer. The RULE-015
pairing block at the top of `ContractSelfTest` **is** an assertion-to-witness registry, and gate 4
already reads it. What it then does is add a *second*, syntactic check — that the right-hand name
is a `class` in one particular file.

So the gate does not trust its own manifest. If Question 3 answers *by role*, the work is mostly
subtraction: keep the declaration as the single source of the pairing, drop the assumption about
where and in what shape a witness is written, and check what a manifest can actually check —
that every assertion appears, and that nothing is declared which does not exist.

Recorded rather than answered. Choosing between the two repairs before deciding what a witness
*is* would repeat what this sprint spent Task 1 undoing: an implementation detail of the first
subject mistaken for the law.

### Answered 2026-08-05 — by role

Task 2 committed the refutation three days before Task 4 asked the question.

**Syntactic form is not necessary.** Four artifacts satisfy every test this model applies to a
witness — constructed to violate exactly one assertion, red set declared, exercised, and shown
green on every other layer — and not one is a class in `BrokenSamplers.kt`:

| witness | form | layer |
|---|---|---|
| `flingForgettingFriction` | `private fun` | integration |
| `springForgettingToNormalise` | `private fun` | integration |
| `snapForgettingToNormalise` | `private fun` | integration |
| `selectionReturningTheCandidate` | a `val` holding a SAM conversion | policy |

The last one decides it, because it has no declared form at all:

```kotlin
private val selectionReturningTheCandidate = Selection { candidate, _ -> candidate }
```

Written instead as `object : Selection { override fun select(…) = candidate }`, the source text
changes, every grep's answer changes, and what it witnesses does not: the same red set —
membership alone — the same test, the same proof. **Two texts, one witness.** A criterion that
tells them apart is telling apart something this model does not care about.

**Syntactic form is not sufficient either**, and gate 4 already says so:

> `note "whether each pair is actually exercised is enforced by review; grep cannot see it"`

A correctly shaped class in `BrokenSamplers.kt`, declared in the pairing block and violating
nothing, passes gate 4c today. It has the form and does no witnessing.

Neither necessary nor sufficient, so it is not the identity. **A witness is an artifact
deliberately constructed to violate one named assertion, together with the declaration of which
assertion that is.** Class, function, object, lambda, table and generated fixture are
representations of that, and a gate recognising one of them has confused a representation for the
thing.

#### The rule never said otherwise

Worth stating plainly, because it decides what has to be repaired. RULE-015 reads *"one property,
one deliberately wrong subject, and no orphans in either direction: no assertion without a fixture
that can make it fail, and no fixture that nothing uses."* Every term in it is a role. It names no
file and no shape, and it never did.

So **nothing normative changes here, and the rule's text stands as written.** What the
counterexample found is that the gate had drifted from the rule it claims to enforce: it enforces
a constraint RULE-015 never contained, while under-enforcing a requirement RULE-015 states
outright — *no fixture that nothing uses*, which its own note concedes grep cannot see. The
divergence ran in both directions at once, which is why neither side of it was visible from the
other.

#### What the answer costs

The `class` check caught one real thing: a declared name that is a typo. *By role* does not
restore that for free, and the honest replacement is the weakest check that is representation-free
— **the declared name resolves to some declaration under `tests/`**. That catches phantoms and
typos and claims nothing beyond them.

Everything else RULE-015 wants — that the artifact is exercised, that its red set is what it
claims — was never machine-checkable, and answering this question does not make it so. It moves to
review explicitly rather than by omission, which is the same trade RULE-016 and RULE-017 already
made.

#### The same confusion, one column to the left

Gate 4 builds its set of assertions by grepping `fun assert*` in `SamplerContract.kt` and
`PhysicsContract.kt`, so `assertTravelPreservesTheGestureVelocity` and the three policy assertions
do not exist as far as it is concerned. **An assertion is being identified by which file it sits
in** — the same mistake as identifying a witness by the shape it is written in.

That is why the repair is subtraction on both sides rather than an addition on one, and why the
integration layer being invisible to gate 4 was a symptom rather than the problem.

#### Why this shape keeps recurring

`DecaySpec.friction` was one family's way of obtaining the quantity the law was about, and Task 1
found it. `class … : MotionSampler` in `BrokenSamplers.kt` is one *layer's* way of writing an
artifact whose role is refutation — the solver tier's way, because there a subject is a
`MotionSampler` and a `MotionSampler` is a class. Both times an implementation detail of the first
subject was mistaken for the law, and both times a second subject was needed before anyone could
see it.

> **An assertion parameterised by a quantity only one family owns is a specialisation waiting to
> be found.** The same sentence holds for a rule enforced through a shape only one layer uses.
