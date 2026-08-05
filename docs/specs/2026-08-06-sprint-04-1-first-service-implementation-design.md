# Sprint 04.1 — First Service Implementation

**Status:** design in review · 2026-08-06 · the first implementation of a shipped SDK service

**Named 04.1 rather than 07 or A**, because the inventory found this is not new work: Sprint 04
shipped seven service interfaces into `aurora.sdk.service` and none of the `aurora.platform` half
it was defined to deliver. This is that half, opened by a feature that wants it rather than by the
inventory noticing it was blank.

The subject is not volume. It is **whether a service the SDK already declares can be implemented at
all**, with volume as the first subject to try it on.

---

## 1. What is already true

Facts, gathered before the questions and carrying no conclusion.

| | |
|---|---|
| `VolumeService` exists | seven methods, per-stream, normalised levels, shipped `ffa2535` |
| implementations of it | none. The only implementer of any service in the tree is `FakeThemeService`, inside a test |
| the wiring point | `AuroraRuntime.volume()` → `findService` → `ServiceProvider.find(type)` |
| `kotlinx` / `StateFlow` | absent from the entire tree. All modules are `sdk_version: core_current`, `static_libs` only aurora + junit |
| the SDK's observer idiom | `addListener`/`subscribe`/`postFrame` each return `Disposable` — three surfaces, one shape |
| `VolumeService`'s observer | `addOnVolumeChangedListener` / `removeOnVolumeChangedListener`, returning nothing |
| Android access | forbidden in `runtime` permanently, and in `platform` until Sprint 03 amends `platform.contract` |

---

## 2. Question 0 — can the shipped `VolumeService` be implemented without changing its SDK?

> **Does the SDK as shipped contain enough abstraction to implement `VolumeService` without
> Android, or does something have to be added first?**

Present tense, and about what is there — not about what a good design would be. The three outcomes:

| outcome | meaning | what it produces |
|---|---|---|
| **A** | yes; the existing abstractions suffice | the implementation, and no new type |
| **B** | no; exactly one seam is missing | that seam, plus an ADR arguing it is the minimum |
| **C** | no; more than one is missing | this stops being an implementation sprint and becomes a design one |

**`VolumeSource` is a hypothesis, not a deliverable.** It is the obvious shape for B and it must not
appear in a single file until Question 0 has been answered — writing it first would make B true by
construction and the question unanswerable. The same discipline 06C.0 spent a sprint on.

### One document already takes a position, and Task 1 has to reconcile with it

`ServiceProvider`'s KDoc, written in Sprint 04:

> "`AuroraRuntime` hands out services, but **the implementations live in `aurora.platform`**, and
> `aurora.runtime` is forbidden from importing that package."

If that is binding, then the first implementation of anything is blocked behind Sprint 03, because
`platform.contract` still forbids `android.` — and Question 0's answer would be *not yet*, for a
reason that has nothing to do with volume.

If instead a seam lets the *logic* live in `runtime` while only the *source of truth* lives in
`platform`, then B is available today and the sentence above describes where a `VolumeService`
implementation is expected to end up rather than where every part of one must be.

**Nothing in this spec chooses between those.** Both readings are consistent with what is written,
which is precisely why Question 0 exists.

---

## 3. Question 1 — is `VolumeService`'s observer deliberate, or unfinished?

> **Is `VolumeService` intentionally different from every other observable surface in the SDK, or
> is this an unfinished API?**

Three surfaces return `Disposable`. One does not, and it is the one nobody has implemented:

```
AnimationHandle.addListener(listener)      → Disposable
AuroraEventBus.subscribe(…)                → Disposable
FrameScheduler.postFrame(callback)         → Disposable

VolumeService.addOnVolumeChangedListener(listener)      → Unit
VolumeService.removeOnVolumeChangedListener(listener)   → requires the same lambda reference
```

This is a **pattern deviation**, not a style preference: removing by lambda identity means a caller
that writes the listener inline can never remove it, and every other surface in the SDK solved that
already.

**The implementation is what answers this, not review.** Whoever writes the first implementation
has to hold the listener list, and will find out whether the add/remove pair can be made to work as
declared. If it can, the deviation may be deliberate and the spec learns why. If it cannot, the
question answers itself.

Not fixed in advance. An SDK signature is changed by an ADR, and there is nothing yet to write one
from.

---

## 4. Question 2 — which layer owns the unit conversion?

Deliberately **not** phrased as *raw steps or normalised?* — that names two points in a solution
space and skips the question. `AudioManager` speaks in step indices, `VolumeService` declares
`Float` 0.0..1.0, so a conversion exists somewhere. The question is where.

```
A     AudioManager ──normalised──────────────────────────────►  VolumeService
B     AudioManager ──raw──►  VolumeService ──normalised──────►  UI
C     AudioManager ──raw──────────────────────────────────────►  UI converts
```

Each is a different architecture, and `stepCountOf` exists because the UI needs the raw count for
snapping regardless of which one wins — so no branch removes the need for both quantities to be
reachable.

**This is the same shape as the unit boundary 06B.2 and 06B.3 spent two sprints on**, in a different
subsystem: a quantity that exists in two units, a conversion that must be exact, and an invariant
that nothing observes until someone crosses it in production. Whether it deserves the same
treatment is not decided here; it is recorded so that the resemblance is noticed before rather than
after.

---

## 5. What this sprint will not do

- **No `android.*`.** Not because of a rule this sprint invents — `runtime.contract` forbids it
  permanently and `platform.contract` forbids it until Sprint 03.
- **No `kotlinx.coroutines`.** Adding `StateFlow` to `aurora.sdk` puts an external dependency on the
  public surface, and the SDK has an observer idiom already. If coroutines are wanted, that is an
  ADR of its own and not a side effect of a volume sprint.
- **No SDK signature changed without an ADR.** Questions 1 and 2 may both conclude that one should
  change. Neither may act on it inside this sprint without the ADR.
- **No UI, no overlay, no animation.** A `VolumeService` that works is the whole subject. The
  overlay is a later sprint and needs Sprint 03 and Sprint 08 first.

---

## 6. Task order

1. **Task 1 — inventory the requirement.** What does implementing all seven methods actually need?
   Answer Question 0 with A, B or C, and reconcile with `ServiceProvider`'s sentence about where
   implementations live. No production file is written in this task.
2. **Task 2 — take exactly one branch.**
   - **A** → implement, and record that no new abstraction was needed.
   - **B** → introduce the one seam, with an ADR that says why it is minimal and what the
     alternatives cost.
   - **C** → stop. Write the named gap, record what is missing, and close the sprint as a design
     sprint that found its subject unbuildable. This is a valid outcome, not a failed one.
3. **Task 3 — the first production implementation**, with host-side tests, if and only if Task 2
   took A or B. Questions 1 and 2 are answered here or explicitly carried forward, because this is
   the task that finds out.

---

## 7. Exit criteria

- [ ] Question 0 answered A, B or C, from what the SDK contains rather than from what would be nice
- [ ] `ServiceProvider`'s claim that implementations live in `aurora.platform` either upheld,
      narrowed by an ADR, or shown not to bind this case
- [ ] Question 1 answered by the implementation or carried forward with the reason it could not be
- [ ] Question 2 answered, and the answer recorded in the layer that ends up owning the conversion
- [ ] No `android.*`, no `kotlinx`, no SDK signature changed without an ADR
- [ ] If Task 2 took C, a named gap exists and no production file was written
- [ ] Host tests pass: `m aurora-platform-tests` on the build VM, JUnit class list extended by hand

---

## 8. The principle this sprint inherits

From `docs/plans/2026-08-05-sprint-06c0-replacement-boundary.md`, and it applies unchanged one
subsystem over:

> No production code may exist whose only purpose is to create a subject for an assertion.

Here it has a sibling, which is the same sentence with the arrow reversed and is what Question 0
protects:

> **No abstraction may be introduced whose only purpose is to make an implementation possible that
> nothing has asked for.**

Volume is what asked. If Question 0 answers C, then nothing has asked loudly enough yet, and the
honest outcome is to say so.
