# Sprint 06C.0 — Replacement Boundary: investigation plan

**Spec:** `docs/specs/2026-08-05-sprint-06c0-replacement-boundary-design.md` (frozen).

**No design decisions here.** If one appears — a new API to make an assertion possible, a state to
make a boundary nameable, a tolerance — **stop and amend the spec**. 06B.1 needed that four times
and 06B.2 once, and each stop was worth more than the code that followed.

---

## What is different about this sprint

06B.1 extended solver evidence. 06B.2 opened integration evidence. 06B.3 tested whether an
abstraction could hold. **06C.0 may produce no evidence at all, and that is a planned outcome
rather than a failure mode.**

Where 06B.3 asked *law or implementation?*, this sprint asks:

> **promise, or aspiration?**

The framework states a continuity intent (`AnimationService`: *"Interruption is the point"*), has
never given it a mechanism, and has no assertion that could tell whether it holds. Establishing
that this is an aspiration — and naming the gap so it survives — is a complete result.

**Production code is conditional, not planned.** It becomes possible only if Task 1 finds a subject
that already ships, and it is forbidden in every other branch. See the exit criteria.

---

## Task 0: discharge the premises

Already done, inside the spec. This task exists so the sprint's later tasks have something to check
themselves against, not because there is work in it.

- [x] Horn A (*intentional omission*) refuted — `AnimationService.kt:30` states the intent under
      the heading *"Interruption is the point"* and names it as the reason a signature exists
- [x] Horn B (*historical accident*) refuted — `git log -S initialVelocity`:
      `PhysicsSpec.initialVelocity` shares commit `ddd2931` with `cancel()`'s positional promise,
      and the intent predates both by two sprints
- [x] The third reading recorded **before** any production task — spec §3
- [x] The responsibility principle at the head of the spec, not buried in a task
- [x] ADR-008 fixed as hypothesis with its direction of use stated, never as premise

**Stop condition.** If any later task rests on horn A or horn B, stop: the spec is wrong and the
task is arguing from something already refuted.

---

## Task 1: does a production subject exist?

The sprint's first real subject, and its question is in the present tense:

> **Does any production subject today carry the continuity intent?**

Not *who should carry it*. That is Task 2, and it does not open unless this one answers A or B.

### Method

Read, in this order, and quote rather than summarise:

1. `sdk/service/AnimationService.kt` and `GestureService.kt` — the two documents that state the
   intent. Establish whether either **declares** a replacement, or only describes one.
2. `sdk/animation/Animator.kt` and `AnimationHandle.kt` — the shipping API. Establish whether a
   replacement is expressible today, and by whom.
3. `runtime/animation/SpringFactory.kt`, `SnapFactory.kt`, `FlingFactory.kt` — the three entry
   points that take a velocity. Establish whether any of them is ever called *from* a live
   execution's state.
4. `git grep` for any caller outside `tests/`. There is not expected to be one; record the command
   and its output either way.

### The three outcomes

| outcome | production subject | what it means | what Task 1 produces |
|---|---|---|---|
| **A** | a runtime subject exists | something in `runtime/` already performs a handoff | evidence + a witness that breaks it |
| **B** | a caller subject exists | a shipping caller performs it, or a declared signature obliges one to | evidence + a witness built from the public API |
| **C** | no production subject | the intent is stated on a layer with no implementation and nothing below it is obliged | a named gap, and no code |

**The column says *a subject exists*, not *that layer is responsible*.** The two read alike and are
different claims: Task 1 establishes where a subject is, and who owes anything about it is
Question 3, behind a gate this task cannot open. A finding of A is *"the runtime already does this"*
— never *"the runtime should"*.

**C is a legitimate answer and is not a synonym for B.** B makes a caller wrong when it drops the
velocity; C says there is no artifact yet that could be wrong. The spec's header principle exists to
keep these apart, and this table is where the sprint is most likely to blur them.

### Stop condition

> **If no production subject exists, no assertion may be written.**
>
> **And no placeholder production subject may be introduced.**

Not weakened, not deferred, not written-but-ignored. An assertion whose subject is created by the
same sprint that asserts it is the RULE-018 situation with nothing independent left in it, and here
it would be worse: the subject would exist only so the assertion could.

The second line belongs here rather than only in the exit criteria, because the move it forbids
happens *inside this task* and looks like progress while it happens:

```
no subject  →  add replaceWith()  →  a subject now exists  →  write the assertion
```

Every arrow is small and each one is defensible on its own. By the last one the sprint is verifying
a capability that exists because it was going to be verified, and nothing downstream can tell.

---

## Task 2: responsibility — *gated*

**Opens only if Task 1 answered A or B.** If Task 1 answered C, this task closes unopened and the
sprint proceeds to Task 3 with a named gap.

> **Does responsibility actually exist, and where?**

If yes:

- [ ] an assertion, phrased over a **pair** of executions
- [ ] a witness — the shape is fixed in spec §9: a replacement that drops the velocity while doing
      everything else correctly, built from the caller's side out of the public API
- [ ] RULE-018 calibration, because a pair-of-executions assertion is a new evidence layer and its
      first pass carries no information until it has been shown able to reject
- [ ] an ADR recording which of spec §6's three branches was taken, and what the other two cost

If no:

- [ ] a named gap, and the sprint stops

### Stop condition

> **Responsibility may not be created for a subject that does not exist.**

---

## Task 3: close

- [ ] Exactly one of: the named gap added to `motion-sampler-contract.md` §7.0 (draft ready in spec
      §12), **or** the ADR from Task 2. Not both, not neither.
- [ ] The surviving prediction from spec §7 marked held or refuted, with the evidence beside it —
      the prediction is *no production subject exists yet, and this sprint ends in a named gap*
- [ ] Any question this sprint opened and did not close relocated to where it belongs, not left in
      the plan. 06B.3 moved Question 3 into `docs/evidence-model.md` for exactly this reason
- [ ] Verify (below), squash, push

---

## Verification for a documents-only sprint

**Expected outcome: no VM.** `sync-to-vm.ps1` sends `device/` and `frameworks/` only, so `docs/`
never arrives there, and `verify-motion-evidence.sh` gate 6 says so itself — it is workstation-only
and skips with a note when `docs/adr` is absent. A sprint that touches documents has nothing for
the VM to build.

Stated as an expectation rather than as a rule, because Task 1 may answer A or B and change it. The
workflow follows the outcome; it is not a premise of the sprint.

So the whole verification for the expected outcome runs locally, in Git Bash:

```bash
bash frameworks/base/aurora/tools/verify-motion-evidence.sh    # gate 6 checks the contract exists
git diff --stat main -- frameworks/                            # must be empty
```

The second line is the real gate for this sprint. An investigation sprint that modified a runtime
file has stopped being an investigation, and this catches it without anyone having to remember.

**If Task 2 opens and code lands**, the normal workflow returns and nothing about it is special:
sync to the VM, run the per-sprint script there, and **extend its JUnit class list by hand** — it
does not discover new test classes.

---

## Exit criteria

- [x] Question 0 answered on quoted documents rather than on inference (Task 0)
- [ ] Question 2 answered A, B or **C**, with C recorded as *nobody owns it yet* rather than as
      *the caller owns it*
- [ ] The §7 prediction tested and its result recorded whichever way it fell
- [ ] ADR-008 used as a hypothesis and nowhere as a premise
- [ ] Exactly one of a named gap or an ADR
- [ ] No test added whose subject was created by this sprint

And the criterion this sprint is named for:

```
No production code may exist whose only purpose is to create
a subject for an assertion.
```

**Why it earns a line of its own.** The failure it names is not laziness, it is momentum: a sprint
that has found a real gap wants to close it, and the cheapest way to close it is to build the
subject the assertion needs. The result passes every gate, reads as evidence, and verifies a
capability that exists because it was verified. RULE-018 catches the version of this where a layer
is new; this catches the version where the *subject* is new, which no rule in the README covers
today.

If Task 1 answers C and someone still wants an assertion, the honest sequence is: a later sprint
builds the subject **for its own reasons**, and a sprint after that verifies it.

---

## Commit shape

One commit per task that produces something, in the sprint's existing voice:

```
Sprint 06C.0 Task 1: <what the search found>, and <what it cost>
Sprint 06C.0 Task 3: <named gap|ADR>, and the prediction <held|did not>
```

Task 0 has no commit. It was discharged in the spec, and its commit is the spec's.
