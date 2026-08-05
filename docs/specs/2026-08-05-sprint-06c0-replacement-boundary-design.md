# Sprint 06C.0 — Replacement Boundary

**Status:** design in review · 2026-08-05 · an investigation, not a family

This sprint adds no motion, no solver and no layer. It asks whether one observable asymmetry in the
existing engine is a decision or a leftover, and it is allowed to end by writing a named gap and
nothing else.

**Question 0 is answered inside this document, by Task 0, and both of its horns fail.** Two predictions
were written to be tested by the sprint and both were refuted before it opened — one by `git log`,
one by a file nobody had read in this context. What replaced them is a smaller and more specific
subject, stated at the end of §3, and the live question moved from Question 0 to Question 2.

The asymmetry is stated in §2. Everything before it exists to keep the sprint from re-verifying
what Sprint 06A already closed, and everything after it exists to keep the sprint from building an
abstraction before the evidence asks for one.

### The principle this sprint is bound by

> **Responsibility only exists if a production subject exists.**

It sounds like a truism and it is the sprint's main safeguard. "Who owns continuity?" reads like a
question with three answers — runtime, caller, a layer above — and all three presuppose that
somebody does. The fourth answer is that nobody does yet, and it is **not** a polite way of saying
*the caller*:

| answer | what it asserts | what it obliges |
|---|---|---|
| runtime / caller / continuation layer | the framework promises continuity, and this is where the promise lives | an assertion, a witness, and an ADR |
| **nobody** | the framework has stated an intent it has not assigned, and no shipping artifact can violate it | a named gap, and nothing else |

*Caller owns it* is a decision that makes a caller wrong when it drops the velocity. *Nobody owns
it* says there is no artifact yet that could be wrong. Collapsing the two would let this sprint
close by assigning responsibility to whoever is furthest away, which is how a gap becomes invisible
without being closed.

---

## 1. What is already settled, and may not be re-opened as evidence

Sprint 06C's obvious framing — *"how does the runtime manage many animations?"* — is mostly
answered already, and a sprint that re-observed these would produce green runs carrying no
information, which is the failure mode `docs/evidence-model.md` exists to prevent.

| question | where it was closed |
|---|---|
| what states an animation has, and which transitions are legal | `AnimationStateMachine`, fifty-six cells, each named |
| who owns the handle, who owns the execution | ADR-003 (RULE-012) |
| what happens to structural changes made during a frame | ADR-005 (RULE-013) |
| whether concurrent animations stay in step | RULE-011; `everyAnimationInAFrameAdvancesByTheSameElapsedTime` |
| whether irregular frame delivery is handled | the engine consumes timestamps and never counts frames; `aFrameIndexMayJumpForwardBecauseFramesGetDropped` |
| whether an idle engine stops asking for frames | `AnimationRegistry.onWake`; the four driver tests |
| what cancellation does to a live execution | `cancel()` leaves the value where it stood, leaves the registry, is idempotent |

**Only one thing about a running animation has never been observed by any assertion: what happens
across the boundary where one execution is replaced by another.** That is this sprint's subject,
and it is smaller than "orchestration".

---

## 2. The asymmetry

At the moment a live execution is cancelled and a new one takes its place, the engine carries a
position and drops a velocity. Both quantities *survive* — neither field is cleared by `CANCEL` —
but only one of them is promised, asserted, or consumed by anything.

| at the replacement boundary | position | velocity |
|---|---|---|
| survives `cancel()` | yes | yes |
| is **promised** | yes — `AnimationHandle.cancel`: *"should stay where the user last saw it, or the interface appears to teleport"* | nothing, anywhere |
| has an **assertion** | yes — `aCancelledAnimationStaysWhereItWasRatherThanJumpingToTheEnd` | none |
| has a **consumer** | yes — `handle.value` | none |

This is not an opinion about how motion ought to feel. Every row is a fact about the repository,
and the right-hand column is empty in a way that can be checked by anyone.

**The two ends of the missing link already agree on units.** `AnimationHandleImpl` publishes
`velocity = sample.velocity * range`, in value units per second. `SpringFactory.springTo` takes
`gestureVelocity` in value units per second and normalises it itself. Nothing would have to be
converted, adapted or wrapped for one to feed the other. The link is absent, not blocked.

**And a replacement cannot reuse the handle.** ADR-003 records that `restart()` begins a new
execution of the *same* `Animation`, whose `from` and `to` are immutable, so redirecting mid-flight
means a new `Animation` and a new handle. `restart()` is therefore not the boundary in question —
it explicitly resets `elapsedNanos`, `value` and `velocity` to a fresh execution, and does so
deliberately.

So the boundary this sprint is about is always **two handles**, and the state that crosses it
crosses through the caller.

---

## 3. Question 0 — is the asymmetry intentional, or an accidental remnant?

> **Is the position–velocity asymmetry at the replacement boundary intentional, or an accidental
> remnant of the pre-`PhysicsSpec` model?**

The question assumes no bug, no feature, and no new layer, and it can be refuted from either side.

### Horn B is refuted before any production task opens

There was no pre-`PhysicsSpec` model to be a remnant of.

```
ffa2535  Sprint 04   AnimationService.springTo(from, to, initialVelocity, …)
                     "Passing the velocity the gesture ended with is what makes a release
                      feel continuous rather than restarted."

ddd2931  Sprint 06A  AnimationHandle.cancel() — "or the interface appears to teleport"
                     AnimationSpec.kt      — PhysicsSpec.initialVelocity, same commit
```

`git log -S initialVelocity` puts velocity-as-an-input in Sprint 04, two sprints before a handle
existed. `PhysicsSpec.initialVelocity` and `cancel()`'s positional promise were written **in the
same commit**. The type hierarchy has never been without a family that accepts a velocity; 06A
implemented only `TimedSpec`, but that is a statement about which solver shipped, not about what
the model could express.

**So the asymmetry did not appear. It was born**, beside a sentence stating the intent it fails to
carry, and the two were never reconciled. That is a different finding from a remnant, and a more
uncomfortable one: a remnant is explained by history, and this is not.

### And horn A does not fit either

*Intentional* would mean someone weighed the two quantities and kept one. The service layer says
something stronger and stranger than that: it states the intent **and states it as the point**.

```
aurora/sdk/service/AnimationService.kt:30

  ## Interruption is the point

  Every method here takes a current value rather than assuming a start of zero. Gesture-
  driven motion is interrupted constantly — a swipe reverses, a second touch lands mid-flight
  — and an animator that restarts from a fixed origin makes the interface visibly snap. The
  springTo overload that takes an initial velocity exists for exactly this reason: it lets a
  release continue the motion the finger was already making.
```

`GestureService` says the same from the other end: *"velocity is carried because it is the input a
spring needs to continue the motion after the finger leaves."* And "a second touch lands
mid-flight" is not gesture-to-animation continuity — it is one animation being replaced by another,
which is precisely this sprint's boundary.

### So neither horn holds, and the third reading is the finding

Not intentional, because nobody chose to drop the velocity. Not accidental, because the intent is
written down twice and named as the reason a method signature has the shape it has.

> **The framework states a continuity intent, has never given it a mechanism, and has no assertion
> that could tell whether it holds.**

`AnimationService` is an interface with no implementation. The sentence declaring interruption to
be the point sits on a facade nothing has ever satisfied, and below it `Animator` takes the
opposite position — *"keeping their lifecycles in step is the caller's job"* — without either
document acknowledging the other.

That is a sharper subject than the one this sprint opened with, and it is smaller. It also moves
the live question from Question 0 to Question 2.

---

## 4. Question 1 — where does the intent live, and what stops it descending?

History answered *when*. §3 answered *whether anyone decided it*. What remains is the shape of the
disagreement between the layers, and that is a reading rather than an argument:

| layer | what it says about a replacement | has an assertion |
|---|---|---|
| `service/AnimationService.kt` | interruption is the point; velocity is passed so a release continues the motion | no implementation exists |
| `service/GestureService.kt` | velocity is carried for exactly this purpose | none |
| `sdk/animation/Animator.kt` | keeping lifecycles in step is the caller's job | none |
| `sdk/animation/AnimationHandle.kt` | `cancel()` leaves the position where it stood | yes, since 06A |
| ADR-003 | a handle cannot be pointed at a new target; redirecting means a new handle | n/a |
| `motion-sampler-contract.md` | nothing; no clause has a pair of executions as its subject | n/a |

**Preliminary result, recorded so Task 1 can overturn it rather than repeat it.** The intent
appears only at the service layer, which is the one layer with no implementation and no test. Every
layer that ships either says the opposite or says nothing. No ADR reconciles them: ADR-002's *"if a
spring is interrupted, the new velocity implies a new settle time"* is an argument about why
`AnimationSpec` is sealed, made in passing, and ADR-003 rules out the mechanism a handle would use
without mentioning what should carry across instead.

**Task 1's remaining work is therefore narrow:** confirm that no document reconciles
`AnimationService`'s claim with `Animator`'s, and record which of the two the framework actually
means. That is a question about intent between two SDK documents, and it can be answered by reading
them.

### One piece of evidence pulls further, and it is a RULE-017 trap

ADR-008 states the physics contract's domain:

> "It binds every solver whose entire dynamical state is `(value, velocity)`."

If the entire dynamical state is the pair, then carrying position alone across a boundary carries
half a state, and the asymmetry looks like a defect rather than a scoping decision.

**That inference is not available yet, and no task in this sprint may make it.** ADR-008's claim
binds *solvers*, in the normalised domain, about what a sampler's internal state is. Whether it
says anything about an endpoint-layer boundary between two handles is exactly the domain overreach
RULE-017 exists to refuse — the same move that kept the spring envelope off a hand-built
oscillation for three sprints.

**Hypothesis, never premise.** The direction of use is the whole safeguard, and it runs one way
only:

```
allowed    if continuity turns out to be a promise, ADR-008 may explain why the pair is indivisible
forbidden  ADR-008 says the state is the pair, therefore continuity must be a promise
```

Taken as a premise it would settle Question 2 before Task 2 ran, and settle it by an argument about
solvers — deciding what ships from what integrates. It is recorded here with its defect attached so
that nobody rediscovers it later and mistakes it for an argument.

---

## 5. Question 2 — does continuity have a production subject?

Sprint 06B.3's lesson, stated as a criterion rather than recalled as a story: **an assertion is
worth having only if something that ships can violate it.** Snap's solver rows dissolved because
snap had no subject distinct from the spring's, and no amount of design would have produced one.

So before any assertion about continuity is written, this sprint must name what it would observe.

| candidate | what it would be | what it costs |
|---|---|---|
| `AnimationHandle.replaceWith(animation)` | the runtime performs the handoff; the boundary becomes a transition, possibly a state | contradicts ADR-003's *"a handle cannot be pointed at a new target"*, which would need an ADR amending it |
| `AnimationController.redirect(handle, animation)` | the engine owns replacement; handles stay immutable | puts a two-handle operation on the controller, which today knows only about frames |
| a `Continuation` / `MotionSequence` above the runtime | the caller's convention given a type | the subject sits outside the domain the motion contract observes, so an assertion about it would be born RULE-017-limited |
| **none** | the framework promises position and nothing else; callers that want velocity read `handle.velocity` and pass it to `springTo` | the sprint closes with a named gap and no code |

**The last row is a legitimate outcome and is not a failure.** It is what §7.0 is for, and an empty
row with a reason beside it has already proven more durable in this repository than prose in a
sprint that closed.

**What would disqualify a candidate:** if every way of violating it requires a caller to write code
that no caller would write, it is not a production subject.

**And the callers this framework keeps naming do not exist as code.** `VolumeService`,
`IslandService`, `NotificationService` and `GestureService` are interfaces under
`aurora/sdk/service/`, with no implementations anywhere in the tree. Task 2 therefore cannot find a
replacement that *exists*; the most it can find is one that a declared signature already implies.
That is a weaker kind of evidence and the sprint should not pretend otherwise — it is the reason §7
predicts a named gap.

---

## Task 1's result, recorded 2026-08-05 — **C, no production subject**

Executed in the plan's order, and the order mattered: steps 1–3 fixed what a subject would look
like before step 4 was allowed to produce candidates.

### The principle the task was run under

> **Presence is not responsibility.** An API call is evidence of responsibility only if it performs
> the boundary Question 0 is about.

Applied because a grep returns candidates, not conclusions. Five callers of `springTo` that all
pass `0f` are five pieces of evidence for C, not against it.

### Step 1 — both documents *describe* continuity; neither *declares* a replacement

`AnimationService.springTo` takes `initialVelocity` as a **parameter the caller supplies**. It takes
no handle, reads no running animation, and has no method that ends one motion and begins another.
`cancelAll()` cancels; it hands nothing over.

**And the case the SDK actually serves is the other one.** `GestureSample.velocity` is *"the input a
spring needs to continue the motion after the finger lifts"* — finger → spring. Every mechanism that
exists serves that. The sentence naming this sprint's boundary is one clause of one KDoc:

```
AnimationService.kt:33   "a swipe reverses, a second touch lands mid-flight"
                          └── animation → animation. Served by nothing.
```

So the intent covers two cases, and the SDK supplies a carrier for exactly one of them.

### Step 2 — a replacement is expressible, and only as four separate caller-side calls

```kotlin
val v = old.velocity        // a query with no consumer anywhere (step 4)
val x = old.value
old.cancel()
animator.play(SpringFactory.springTo(name, x, target, v))
```

Nothing in the SDK or the runtime composes those four. `Animator` makes handles; `AnimationHandle`
runs one. The sequence is legal, undocumented, and unnamed.

### Step 3 — all three factories name the velocity after the gesture

| factory | parameter | its KDoc |
|---|---|---|
| `SpringFactory.springTo` | `gestureVelocity` | *"continuing a gesture that ended at [gestureVelocity]"* |
| `FlingFactory.fling` | `gestureVelocity` | *"A decay released at [gestureVelocity]"* |
| `SnapFactory.snapTo` | *(delegates)* | builds through `SpringFactory` |

**This is the recurring shape a third time.** `DecaySpec.friction` was one family's way of naming the
quantity a law was about; `class … : MotionSampler` was one layer's way of writing a witness; and
`gestureVelocity` is one *source's* way of naming an input. A velocity arriving from a cancelled
execution is not a gesture velocity, and every signature that could accept it says otherwise.

Presence is not responsibility: three factories accept a velocity, and not one of them contemplates
this boundary.

### Step 4 — the search, and its two surprises

```bash
git grep -l "SpringFactory\|FlingFactory\|SnapFactory" -- '*.kt' | grep -v /tests/
git grep -n "\.velocity" -- '*.kt'
```

**No caller outside `tests/` calls any factory.** The six files the first command returns mention
the names in KDoc; the only cross-file call in the tree is `SnapFactory.kt:80` →
`SpringFactory.springTo`, which composes two factories at construction time and involves no live
execution.

**And nothing anywhere reads `AnimationHandle.velocity`.** Every `.velocity` in the repository —
production and test — is `sampleAt(…).velocity`, a *sample's* velocity, inside the solver domain.
The handle's query has **zero consumers in the entire tree**, and did before this sprint noticed.

That was not predicted. It makes C stronger than *"no caller has been written yet"*: the quantity the
stated intent requires is computed on every frame, published on the public API, and read by nobody.

### The repository already said so, in its own voice

`FlingFactory`'s KDoc, written in Sprint 06B.2:

> "`AnimationService.fling` was the expected home, but **`AnimationService` has no implementing class
> at all — `springTo` was declared in 06A and never implemented**"

### Answer

**C — no production subject exists.** Per the header principle this is recorded as *nobody owns
continuity yet*, and **not** as *the caller owns it*: there is no artifact that could be wrong.

**Task 2 does not open.** The gate requires A or B.

**The §7 prediction held**, and it is the only one this sprint made that did.

---

## 6. Question 3 — who owns continuity? *(gated)*

**This question may not be opened unless Questions 0–2 answer that continuity is a promise the
framework makes and has a production subject to make it about.** It is written down so that its
premature answer is visible as premature, not so that it can be started early.

Three branches, none weighed:

- **Runtime owns it.** Handoff becomes semantics rather than politeness; a fourth resting state
  (`REPLACED`, distinct from `CANCELLED`) becomes askable. Requires amending ADR-003.
- **Caller owns it, contract names the gap.** Consistent with `Animator`'s existing position that
  *"keeping their lifecycles in step is the caller's job"*. Produces no persisted assertion.
- **A continuation layer above the runtime.** Consistent with ADR-003 and ADR-007 both. Places the
  subject outside what the motion contract observes.

Recorded rather than ranked. Ranking them now would repeat what §3 just found — assuming an answer
to the question in front of it.

**None of the three is the default if the gate does not open.** Per the principle in the header, the
outcome of a closed gate is *nobody owns it yet*, which is a named gap and not the second bullet.
The second bullet is a decision that makes a caller wrong; the closed gate says there is no artifact
that could be wrong. If this section is ever reached by a sprint that skipped Question 2, that is
the error to look for first.

---

## 7. A prediction, recorded before the sprint opened

> **If the asymmetry is a historical remnant, its boundary will coincide with where `PhysicsSpec`
> appears.**

The prediction is stated in this form because it can be wrong without taking anything else with it,
does not depend on any solution, and is checkable against blame and ADRs rather than against taste.

**It is already refuted, and the refutation is recorded here rather than in a task's result.**
`PhysicsSpec.initialVelocity` shares a commit with `cancel()`'s positional promise, and the
continuity sentence predates both by two sprints. The boundary and the family arrived together, so
there is no line for the asymmetry to sit on.

Its replacement was refuted the same afternoon:

> ~~If the asymmetry was never decided, no document written before this sprint will mention both
> quantities at the same boundary.~~

`AnimationService`'s *"Interruption is the point"* mentions both, at this exact boundary, and calls
it the reason a signature exists. Two predictions, two refutations, and neither survived contact
with `git log` and one unread file.

**Worth stating plainly, because it is the sprint's method working rather than failing.** Both
predictions were about *history*, and both were checkable cheaply, which is why they died before a
task was opened rather than after one closed. What they cost was an afternoon; what they bought is
that the sprint's subject is no longer the one it was named for.

### The prediction that is left, and it is about Question 2

> **No production subject for continuity exists yet, and this sprint ends in a named gap rather
> than an ADR.**

The reason to expect it: the four callers this framework keeps naming — Volume, Dynamic Island,
Notification, Control Center — exist only as service *interfaces*. Nothing implements them. A
production subject must be something that ships and can be got wrong, and an unimplemented
interface cannot be got wrong.

It can fail, and Task 2 is what would fail it: an existing caller that already performs a
replacement, or a signature on a shipping type that would obviously carry the velocity if anyone
wrote it. Either would make the subject real and open Question 3.

---

## 8. What this sprint will not do

- **No new motion family.** 06B closed the abstraction question; another family would test nothing.
- **No solver change.** Nothing here reaches below the endpoint layer.
- **No new evidence layer created before it is needed.** An assertion whose subject is a *pair* of
  executions would be a new layer, and RULE-018 would then require it to be shown able to reject
  before any pass counts. That obligation is triggered by Question 2 answering *yes*, and by
  nothing earlier.
- **No fix to the engine-restart timestamp discontinuity.** `DefaultAnimationController.stop()`
  documents it and defers it to Sprint 08's `ChoreographerFrameScheduler` decision. It is a real
  gap and it is blocked on a decision belonging to another sprint.

---

## 9. If an assertion does get written, what its witness is

Recorded now so that the shape is fixed before anyone is invested in a green run.

A continuity assertion's witness is a replacement that drops the velocity while doing everything
else correctly — the same shape as the integration layer's three:

```kotlin
private fun springForgettingTheHandoffVelocity(…)   // replacement built with gestureVelocity = 0f
```

It must be green on every other assertion in the repository and red on exactly one, and it must be
declared in the RULE-015 pairing block. Per the answer recorded in `docs/evidence-model.md`, its
form — function, class, lambda or table — is not a criterion; the declaration is.

**The calibration this needs is stronger than usual**, and for the reason 06B.1 already met: a
replacement built by the same helper the assertion uses to describe a correct one will agree with
it by construction. The witness must be built from the caller's side, out of the public API, or the
pass carries nothing.

---

## 10. Task order

0. **Task 0 — kill or keep the two horns.** Short by construction, and discharged in §3 of this
   document rather than after it. Its exit criteria are three lines:

   - [x] Horn A (*historical accident*) survives **or** is refuted — **refuted**, by
         `git log -S initialVelocity`: `PhysicsSpec.initialVelocity` and `cancel()`'s positional
         promise share commit `ddd2931`, and the intent predates both by two sprints.
   - [x] Horn B (*intentional omission*) survives **or** is refuted — **refuted**, by
         `AnimationService.kt:30`, which states the intent under the heading *"Interruption is the
         point"* and names it as the reason a signature exists.
   - [x] If both are refuted, record the third reading **before opening any production task** —
         recorded at the end of §3: *the framework states a continuity intent, has never given it a
         mechanism, and has no assertion that could tell whether it holds.*

   Both horns died inside Task 0, and that is the sprint's framing becoming accurate rather than
   the sprint failing. Nothing below may proceed on either refuted horn.

1. **Task 1 — reconcile the two SDK positions.** `AnimationService` says interruption is the point;
   `Animator` says lifecycles are the caller's job. Record which the framework means, or record
   that it has never said. Overturn §4's preliminary result if it is wrong.
2. **Task 2 — the declared call sites.** Answer Question 2 from the service interfaces, since no
   implementations exist. Name the production subject, or establish that there is none yet and say
   which sprint would create one.
3. **Task 3 — write the outcome.** Either a named gap in §7.0 of the motion contract, or an ADR
   opening Question 3 with the branches of §6 weighed against the subject Task 2 found.

Task 3 has two shapes and the sprint does not decide which until Task 2 reports. Both are complete
outcomes.

---

## 11. Exit criteria

- [x] Question 0 answered, with the answer resting on quoted documents rather than on inference.
      Both horns refuted; the third reading recorded. See Task 0.
- [ ] §7's surviving prediction tested, and its result recorded whichever way it fell.
- [ ] Question 2 answered by naming a production subject or by establishing there is none. If there
      is none, the answer is recorded as **nobody owns it yet** — not as *the caller owns it*.
- [ ] ADR-008 used as a hypothesis and nowhere as a premise. A task that reasons *from* it to a
      conclusion about the replacement boundary has broken §4's rule and RULE-017 with it.
- [ ] Exactly one of: a named gap added to `motion-sampler-contract.md` §7.0, **or** an ADR opening
      Question 3. Not both, and not neither.
- [ ] No file under `frameworks/` modified. This sprint produces documents; the per-sprint verify
      script has nothing new to run and is not extended.
- [ ] If Question 2 answered *yes*, RULE-018's obligation is written into the next sprint's spec
      before this one closes.

---

## 12. Draft named gap, if that is the outcome

Written now so that Task 3 has something to sharpen rather than something to invent, and so the
sprint's result is legible even if it produces nothing else.

| gap | what is unobserved | why it is not merely missing |
|---|---|---|
| **velocity at a replacement boundary** | what carries from a cancelled execution to the one replacing it. Position is promised by `cancel()` and asserted at the endpoint layer; velocity survives on the handle, is expressible as `PhysicsSpec.initialVelocity`, has agreeing units at both ends, and is promised by nothing that ships. | It cannot be closed by strengthening an existing assertion, because no assertion has a *pair* of executions as its subject. The intent is not missing — `AnimationService` states it under the heading *"Interruption is the point"*, and `GestureService` carries a velocity for that stated purpose — but it lives on the one layer with no implementation, while `Animator` below it assigns the same job to the caller and no document reconciles the two. A replacement that drops the velocity passes every layer this contract reaches and is visibly wrong on a device. |
