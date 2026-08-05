# Sprint 03 — Wire into the system

**Status:** design in review · 2026-08-06 · the first sprint to touch anything Aurora does not own

Numbered 03 because `README.md` §Future Extension named it that in Sprint 02, and it is arriving
after 06C. The number records where it sits in the dependency order, not when anybody got to it.

**This sprint is two phases, and the first one builds no runtime.** ADR-011 was accepted an hour
before this spec and says of itself:

> "`sync-to-vm.ps1` and `vm-apply-code.sh` gain a patches path. **Until they do, this decision is
> written down and not yet operational.**"

So the dependency is:

```
ADR-011 accepted
      │
      ▼
ADR-011 operational          ← Phase A. No runtime code. No upstream patch.
      │
      ▼
Question 0 answered          ← Phase B. Where does AuroraRuntime belong?
      │
      ▼
the first upstream patch     ← the workflow's first consumer, whatever the answer was
```

Phase A before Phase B, because a sprint that answers Question 0 first would then have to build the
machinery to express its answer while under pressure to make that answer work.

---

## 1. Phase A — make ADR-011 operational

> **Subject: give the repository the ability to express an upstream modification at all.**

Not *modify `SystemServer`*. Not *modify anything*. At the end of Phase A the repository can carry
a delta against a file it does not own, and carries none.

### What is missing today

| | |
|---|---|
| a place for patches | no `patches/` directory |
| a way to send them | `sync-to-vm.ps1` `$TREES` is `device`, `frameworks` |
| a way to apply them | `vm-apply-code.sh` `PATHS` names three directories and rsyncs; it has no notion of a patch |
| pristine restore | nothing restores an upstream file before applying, so ADR-011's *applying twice equals applying once* is a claim with no mechanism |
| a gate | nothing checks that a patch still applies |

### What "operational" means, concretely

1. A `patches/` tree whose layout mirrors the paths it modifies, so a listing answers *what has
   Aurora changed outside its own directory* without opening a file.
2. `sync-to-vm.ps1` sends it; `vm-apply-code.sh` applies it.
3. **Apply begins from pristine.** The affected files are restored from the checkout's own
   `repo`-managed git before any patch is applied. This is what makes re-application well defined
   rather than carefully avoided.
4. A gate that fails loudly on a patch that no longer applies. No fuzz, no skip. **VM-only**, and
   it says so on a workstation rather than failing where it cannot see its input — the mirror of
   `verify-motion-evidence.sh` gate 6.

### Phase A opens a new evidence layer, so RULE-018 binds it

The patch gate is an assertion about a class of artifact that has never existed here. Its first run
will be green, and a green run from a gate nobody has seen refuse carries no information — which is
exactly the situation RULE-018 was written for.

**So Phase A must show the gate can reject before any pass from it counts.** The witness is a patch
deliberately written against context that is not in the tree: declared as a witness, kept where the
other witnesses live, and required to turn the gate red. A gate that has only ever agreed is
indistinguishable from a gate that agrees with everything.

**And there is a trap here worth naming before anyone walks into it.** The obvious way to prove the
machinery works is to write a real patch against a real upstream file. That patch would exist only
so a gate had something to chew on — which is the move Sprint 06C.0's plan forbids, one subsystem
over:

> No production code may exist whose only purpose is to create a subject for an assertion.

A patch is production. So Phase A proves **rejection** — which needs only a deliberately wrong
patch — and leaves proving **acceptance** to Phase B's first real one. Phase A therefore ends with
`patches/` empty of real content, and that is the correct end state rather than an unfinished one.

### Phase A exit

- [ ] a patch can be authored in the repository, sent, and applied to the VM's checkout
- [ ] applying twice leaves the tree identical to applying once, demonstrated rather than asserted
- [ ] **the gate always runs against a freshly restored tree, never against the result of a previous
      apply.** An operational rule for the gate rather than a restatement of the invariant above:
      without it, the obvious optimisation — reuse the tree, it is already patched — reintroduces
      exactly the accumulated state ADR-011 removed, and does it in the one place that would have
      caught it
- [ ] the gate is red against a witness patch that cannot apply
- [ ] the gate is green against an empty set, and says which machine it ran on
- [ ] `patches/` contains no patch against a real upstream file
- [ ] no file under `frameworks/base/aurora` changed by Phase A except the tooling under `tools/`

---

## 2. Question 0, and the assumption it must not make

> **Where does `AuroraRuntime` belong?**

**Not** *how do we modify `SystemServer`?* The README names `SystemServer`, ADR-011's context quotes
that, and Phase A's whole justification is that an upstream modification needs a home — all of which
makes it very easy to open Phase B with the answer already in it.

> **Question 0 must not assume `SystemServer` is the answer.** If the answer turns out to be
> `SystemServer`, that is the sprint's conclusion and not its premise.

The distinction is not pedantry. The two framings send someone to look at different things: *how do
we modify X* sends them to X, and *where does this belong* sends them to survey what initialisation
points exist. Only the second can discover that a better one already exists — and if one does, it is
almost certainly cheaper than patching a vendor file forever.

**Nothing in this sprint is invalidated if the answer is not `SystemServer`.** ADR-011 governs how an
upstream change is stored, not whether one is needed. Phase A's machinery is worth having the first
time anything upstream is touched, whenever that is.

---

## 3. Phase B — runtime integration

Opens only after Phase A's exit criteria are met.

### What Phase B surveys

Candidates, listed as things to examine rather than as options to choose between. The list is
certainly incomplete, which is the point of surveying rather than deciding:

| candidate | what would make it right | what would make it wrong |
|---|---|---|
| `SystemServer` | it is where system services are started, and the README named it | it is a vendor file, so it costs a patch forever |
| a LineageOS-provided hook | no upstream patch at all | it may not exist; nobody has looked |
| an `init.rc` service | independent lifecycle | a separate process is a different architecture, not a different line |
| something Aurora's own | full control | nothing starts it, which is the original problem |

### What would decide it

An initialisation point has to answer four questions, and the survey is complete when each candidate
has an answer to all four:

1. **When does it run**, relative to the services Aurora will need?
2. **What does it have** — is a `Context` available there, which `AuroraContext.hostContext()` needs
   in order to narrow from `Object`?
3. **What does it cost to keep**, across an AOSP rebase?
4. **What happens when Aurora fails there** — a service that throws during boot is a bootloop, and
   the answer must not be discovered on a device.

The fourth is the one most likely to be skipped and the most expensive to learn late.

### Phase B's deliverables, if the answer requires a patch

- the first patch under `patches/`, which makes it the workflow's first real consumer
- `AuroraContext.hostContext()` narrowed from `Object` to a real type
- `AuroraServiceRegistry` published, and a `ServiceProvider` implementation that resolves services
- **`DefaultVolumeService` gets somewhere to live** — Sprint 04.1 built it and nothing constructs it
- `platform.contract`'s `forbid-import: android.` replaced by a narrow allow list, before any code
  needs it

That last one is first in execution order. The contract is amended, the gate is watched to go red on
the old fixtures and green on the new boundary, and only then does anything import Android — the
order Sprint 02 established when it built `arch-test.sh` before there was anything to test.

---

## 4. What this sprint will not do

- **No `ChoreographerFrameScheduler`.** That is Sprint 08, and it needs what this sprint produces.
- **No UI, no overlay, nothing drawn.** `docs/features/volume-overlay.md` describes behaviour for a
  sprint that is at least two away.
- **No new Aurora subsystem.** Every artifact here either carries a delta or starts something that
  already exists.
- **No patch written to exercise the machinery.** See Phase A's trap.

---

## 5. Task order

1. **Task 1 — Phase A.** The `patches/` tree, the sync path, pristine-restore apply, the gate, and
   the witness that shows the gate can refuse.
2. **Task 2 — the survey.** Answer Question 0 by examining candidates against the four questions.
   Record what was looked at, including whatever turned out not to exist.
3. **Task 3 — the contract amendment.** `platform.contract` narrowed, gate re-run, negative fixtures
   updated. Before any code imports Android, not alongside it.
4. **Task 4 — integration.** Whatever Question 0 decided, including the first real patch if one is
   needed, and a `ServiceProvider` that hands out `DefaultVolumeService`.

Tasks 3 and 4 do not start until Task 2 has an answer. If Task 2 finds that no adequate
initialisation point exists, the sprint stops there and says so — that is a result, and the
machinery from Task 1 keeps its value regardless.

---

## 6. Exit criteria

- [ ] ADR-011 is operational: a patch can be carried, applied, re-applied and gated
- [ ] the patch gate has been shown to refuse before any green from it was read as evidence
- [ ] Question 0 answered by survey, with the candidates that were examined recorded — including
      those that do not exist
- [ ] the answer is a conclusion with reasons, and `SystemServer` appearing in it is not by default
- [ ] `platform.contract` amended before the first Android import, with `arch-test.sh` green after
- [ ] `AuroraRuntime` starts on a device, and `DefaultVolumeService` is reachable through it
- [ ] boot verified for real. Per the README's own note on this sprint: *"This is the first step
      that changes runtime behaviour, so Boot PASS must be verified for real rather than holding by
      construction as it does today."*

The last one is the sprint's real test. Everything before it can pass on a host.
