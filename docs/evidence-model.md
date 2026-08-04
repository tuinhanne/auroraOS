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
| **RULE-015** | a contract property must have a witness | machine-checked, gate 4 |
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

Three are enforced by review, and say so. Whether two computations are the same idea, whether a
subject satisfies a theorem's premises, and whether a layer is new are not things a script can
decide, and a gate pretending otherwise would be worse than none.

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
