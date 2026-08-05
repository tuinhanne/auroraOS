# After 06C.0 — where the animation subsystem stands, and what reopens it

**Status:** decisions recorded 2026-08-05, after Sprint 06C.0 closed at `16559ff`.

Descriptive. This adds no rules and constrains no sprint; it records what was decided so a later
sprint does not have to reconstruct it from the commit log. Where it looks normative, the norm is
elsewhere — RULE-015 to RULE-018 in `frameworks/base/aurora/README.md`, and the sprint-local
constraint in `docs/plans/2026-08-05-sprint-06c0-replacement-boundary.md`.

---

## 1. How done is the animation subsystem?

| layer | state | what closed it |
|---|---|---|
| **runtime** | done | 06A: lifecycle, ownership, registry, deferred mutation, frame delivery, cancellation |
| **solvers** | done | 06B.0–06B.3: spring, decay, and snap — which turned out to be a selection followed by a spring (ADR-009) |
| **evidence: solver layer** | done | `SamplerContract`, `PhysicsContract`, witnesses per family |
| **evidence: integration layer** | done | one invariant over supplied, derived and selected targets |
| **evidence: endpoint layer** | done | 06A's lifecycle and API tests |
| **evidence: policy layer** | done | `TargetSelectionPolicy`, three properties |
| **evidence: its own boundary** | done | 06C.0 — the model can now say *this is not a question evidence answers* |

**Three gaps are open on purpose**, and none of them blocks anything:

| gap | where | why it is open |
|---|---|---|
| velocity at a replacement boundary | contract §7.0 | no production subject carries it (06C.0) |
| unit boundary crossing for `TimedSpec` | contract §7.0 | `TimedSpec` has no velocity to normalise; nobody has asked whether it needs a different invariant |
| engine-restart timestamp discontinuity | `DefaultAnimationController.stop()` KDoc | blocked on a frame-source decision that does not exist yet — see §4 |

An open gap is not a to-do list item. It is a question recorded so nobody has to think of it again.

---

## 2. What is frozen

- **06C.1 is not on the timeline.** It becomes *eligible* if production later creates a
  replacement subject. It may never open. That is a valid end state, not an unfinished one.
- **No RULE-019.** Deferred not for want of instances but because two generalisations compete;
  recorded open in `docs/evidence-model.md`.
- **The motion runtime is not modified unless a feature requires it.** No refactor, no new family,
  no API added because a gap exists.
- **No feature is obliged to close a named gap.** If the first shipped features never replace a
  live motion, 06C.0's conclusion stands unchanged and the gap stays open, correct.

---

## 3. What would reopen animation

Exactly two things:

1. **A production feature creates a replacement subject** — something that ships, replaces a live
   motion, and can get it wrong. Then 06C.1 has an object and opens.
2. **A named gap becomes a production problem** — a shipped feature that is visibly wrong because
   of it.

In both cases the trigger is production. Neither is triggered by the gap continuing to exist.

### What does not reopen animation

- a named gap continuing to exist
- a wish to complete the contract
- an assertion with no subject
- an API nothing calls
- an implementation opportunity

Each of these is a reason to build something so that evidence has an object, which is the one move
06C.0 exists to have refused. If a later sprint reopens animation, its reason should be findable in
a feature, not in this list.

---

## 4. A correction to the obvious next step: a feature cannot be the next sprint

The natural plan is *07.0 first feature → 07.1 integrate the animation runtime → 07.2 observe*.
It does not start, and the reason is mechanical rather than a matter of taste.

**Aurora cannot receive a frame or draw anything on a device today.**

```
git grep "^import android"                       →  no matches anywhere in the tree
git grep ": FrameScheduler {"                    →  2 implementations, both in TestClock.kt
git ls-files frameworks/base/aurora/platform/    →  1 file, AuroraServiceRegistry.java
```

`QueuedFrameScheduler` and `ImmediateFrameScheduler` are test infrastructure. There is no
`ChoreographerFrameScheduler`, no window, no surface, and no implementation of any interface in
`aurora.sdk.service` — the only implementer in the tree is a `FakeThemeService` inside a test.

So the two paths that looked like alternatives are ordered: **making the engine device-runnable is
a prerequisite of the feature, not a substitute for it.** A feature sprint that began now would
spend itself building a frame source and would report a feature it could not run.

### The name matters, because the obvious one is too wide

Call it **runtime host**, not *platform binding*. The subject is one sentence:

> take the engine from **test-runnable** to **device-runnable**.

*Platform binding* invites Binder, `WindowManager`, `Context`, `Resources`, a `Looper` and a service
stack, and a sprint that accepts that scope has stopped having a subject. None of those is needed to
put a real frame into `AnimationController.tick`, and whatever the minimum turns out to be is a
question for that sprint's inventory rather than for this file.

**The repository already anticipated this and numbered it.** `DefaultAnimationController.stop()`
defers its unresolved question to *"Sprint 08's `ChoreographerFrameScheduler` decision"*. The
numbering is off by one sprint; the dependency is not.

### What that sprint gets for free

It is not plumbing done for tidiness. It has a production reason of its own — the engine cannot run
on a device — and it retires a question that has been deferred in prose since 06A: whether a frame
source that keeps running while the engine is stopped hands every live execution one enormous
elapsed step on the first frame back. That question is undecidable until a real scheduler exists,
which is precisely §1's third open gap.

This is the shape 06C.0 argued for, arriving immediately: **production creates subjects for its own
reasons, and evidence follows.**

---

## 5. The sequence, revised

```
Sprint 07.0   runtime host — the engine becomes device-runnable, and nothing wider
                  ↓  closes the engine-restart gap as a side effect of having a real frame source

Sprint 07.1   the first feature that ships (Volume overlay is the cheapest candidate,
              and ADR-003 already names it)
                  ↓

Sprint 07.2   observe what production actually needed
                  ↓
              does a replacement subject now exist?

                  yes  →  06C.1 opens, with an object
                  no   →  the named gap stays open and correct
```

**07.0 opens with an inventory, not a design.** One table — what exists, what is missing, what is
merely assumed — answering *which artifacts are missing before the engine can run on a device*.
Without it the sprint's scope is set by whoever writes the first file.

**06C.1 is not a goal in this diagram.** It is a branch that production may or may not reach, and
the *no* branch is not a failure to reach it.

---

## 6. What this document is not

It is not a commitment to build any of these features, and not an argument that Aurora should ship
one now. Whether the project continues into product work is a decision outside this file. What is
recorded here is: **if it does**, this is the order the repository's current state permits, and
these are the questions that stay shut until production asks them.
