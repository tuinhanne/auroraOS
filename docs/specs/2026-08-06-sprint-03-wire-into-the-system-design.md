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

## Task 2's result, recorded 2026-08-06 — **no upstream patch is needed**

Question 0 answered by survey of the AOSP checkout. **`SystemServer` is not the answer**, and the
constraint in §2 is what made the answer findable: opening with *how do we modify `SystemServer`*
would have sent the survey to `SystemServer` and stopped there.

### Two config-driven hooks exist, and Aurora already owns the files that drive them

**Hook 1 — AOSP's own device-specific services array.**

```java
// SystemServer.java:3254
t.traceBegin("StartDeviceSpecificServices");
final String[] classes = mSystemContext.getResources().getStringArray(
        R.array.config_deviceSpecificSystemServices);
for (final String className : classes) {
    try {
        mSystemServiceManager.startService(className);
    } catch (Throwable e) {
        reportWtf("starting " + className, e);
    }
}
```

`config_deviceSpecificSystemServices` is declared empty in
`frameworks/base/core/res/res/values/config.xml:5450`, and
`device/samsung/beyond2lte/overlay/frameworks/base/core/res/res/values/config.xml` — 45 lines,
Aurora's own tree, already synced — is a resource overlay that can fill it. It does not declare the
array today.

**Hook 2 — LineageOS's external system server**, which turns out to be a patch someone else already
carries. `SystemServer.java:3108` reads `org.lineageos.platform.internal.R.string.config_externalSystemServer`
and reflectively invokes `LineageSystemServer.run()`, which walks
`config_externalLineageServices` and starts each entry. `frameworks/base`'s remote is
`LineageOS/android_frameworks_base`, so that call site is inherited rather than owed.
`device/samsung/beyond2lte/overlay/lineage-sdk` exists, so the array is overridable without a patch
too.

### The four questions, answered for Hook 1

| | |
|---|---|
| **when does it run** | after `DisplayManagerService.systemReady`, immediately before `PHASE_DEVICE_SPECIFIC_SERVICES_READY`. Package manager, power, display and a system `Context` all exist by then |
| **what does it have** | `mSystemContext`, a real `Context` — exactly what `AuroraContext.hostContext()` needs in order to stop being `Object` |
| **what does it cost across a rebase** | **nothing.** There is no patch to re-apply, because there is no patch |
| **what happens when Aurora fails** | `catch (Throwable e) { reportWtf(...) }`. A WTF in the log, not a bootloop — and structurally, rather than because whoever wrote the integration remembered to guard it |

The fourth is the one §2 called most likely to be skipped, and it is the strongest argument for a
hook over an edit: a hand-written call in `SystemServer` would have to carry its own try/catch, and
would be one review away from not carrying it.

### Why Hook 1 over Hook 2

Hook 2 requires extending `org.lineageos.platform.internal.LineageSystemService`, implementing
`getFeatureDeclaration()`, and having that feature declared on the device — so Aurora's platform
layer would bind to **LineageOS** rather than to **Android**. RULE-002 says Android is confined to
`aurora.platform`; it does not say LineageOS is, and widening it that way is a decision this sprint
has no reason to make. Hook 1 binds to `com.android.server.SystemService`, which is the thing
`aurora.platform` already exists to know about.

### What it costs instead, and where

One real cost, and it is in a tree Aurora owns rather than one it does not:
`SystemServiceManager.startService(String)` resolves the class on the system server's classloader,
so `aurora-platform` has to be on `SYSTEMSERVERCLASSPATH`. That means `installable: false` changes
and the device makefile appends to `PRODUCT_SYSTEM_SERVER_JARS` — a mechanism with precedent in
this checkout (`device/google/cuttlefish`, `packages/services/Car`, `device/google/atv`).

### The consequence for Phase A

**ADR-011 gets no consumer in this sprint.** `patches/` stays empty, and the machinery Task 1 built
goes unused.

That is the outcome §2 said would not invalidate anything, and it is worth being plain about: Task 1
was not wasted, and it was also not vindicated. It will be worth having the first time something
upstream genuinely has to change, and this sprint is not it. Had Task 1 come second, the survey
would have run under pressure to justify the machinery already built — which is the same shape as
the trap Task 1 itself avoided, one level up.

---

## Task 3 — the minimal Android surface

> **What is the narrowest Android surface that lets the hook compile?**

Not *remove `forbid-import: android.`*. The two framings differ in who decides the scope: the first
lets the compiler decide it, the second lets whoever is typing decide it, and only one of those has
an opinion that can be checked.

> **Prediction: the answer is substantially smaller than `android.*`.**

Recorded before the work so it can be wrong. A hook that constructs a service and hands over a
context plausibly needs one or two packages; if the honest list turns out to be broad, that is worth
knowing and worth writing down as a surprise rather than as a shrug.

### The amendment has a second half, and it is the one nobody predicts

`platform.contract` today:

```
forbid-import:  android.
forbid-import:  com.android.internal.
forbid-import:  aurora.device.
forbid-dep:     services
```

`com.android.server.SystemService` is not blocked by any `forbid-import` line — but it lives in the
`services` module, and **`forbid-dep: services` blocks the dependency**. So a change that only
touched the import list would produce a contract that permits the imports and forbids the module
they come from.

> **Prediction, testable: the amendment is two lines, not one — a narrowed `android.` allow list
> *and* a relaxed `forbid-dep: services`.**

`com.android.internal.` stays forbidden regardless. The contract's own comment says why, and nothing
here changes it: *"it is unstable private API, and depending on it makes every AOSP rebase a
liability."*

### `org.lineageos` is not on the list, and that is deliberate

Task 2 chose the AOSP hook over the LineageOS one so that Aurora depends on Android rather than on
LineageOS:

```
Aurora → Android                    what this sprint builds
Aurora → LineageOS → Android        what hook 2 would have made it
```

The allow-list must keep that true. `org.lineageos.` is not an `android.` prefix, so nothing in this
amendment admits it by accident — but if a later sprint finds itself wanting it, that is a decision
about Aurora's dependency floor and needs an ADR, not an allow-list edit.

### Method, and why writing the code first is not a violation

The list is derived by compilation rather than by design:

1. write the hook, with the contract still forbidding everything
2. compile, and let the compiler name what is missing — `arch-test.sh` is red throughout, which is
   the correct state and not a problem to route around
3. amend `platform.contract` to exactly what was named, and nothing adjacent
4. `arch-test.sh` green, negative fixtures updated to the new boundary

Step 1 has code importing Android before the contract allows it, which looks like it inverts the
rule this sprint keeps repeating. It does not: **the rule is that no such code may *land* before the
contract permits it**, and steps 1–2 are a measurement whose output is a list. Nothing is committed
until step 3 has run, so the tree never holds code the contract forbids.

Written down because the distinction is exactly the sort that gets quietly dropped, and because a
sprint that skipped step 1 would have to guess the list — which is the failure this task exists to
avoid.

**Two kinds of code, and only one of them is an artifact:**

| | what it is | where it lives |
|---|---|---|
| **candidate** | an instrument. Its output is a list of missing dependencies, and it is thrown away or superseded | nowhere, until the contract permits it |
| **production** | the same text, after the contract has been amended to permit it | the tree, reviewed, gated |

The two can be character-for-character identical and still be different things, which is the same
distinction ADR-011 part 3 makes about a patch and for the same reason: **what an artifact is
depends on the direction it was authored in, and that is invisible in the artifact.**

### The allow list may not be wider than the compiler forces

`android.content.Context` does not admit `android.view.`, `android.os.` or `android.graphics.`
because they will probably be wanted later. Every package on the list must have been named by a
compiler or demanded by an implementation that exists.

This is what keeps `platform.contract` a measurement rather than a wish list. A precautionary
entry cannot be wrong — nothing fails when it is unused — so nothing ever removes it, and the
contract slowly stops describing the boundary it claims to describe. The narrow list is
uncomfortable on purpose: it goes red the moment the boundary really moves, and that red is the
only signal that it did.

---

## Task 3's measurement, recorded 2026-08-06 — the amendment is not an amendment

Two builds on the VM. Nothing was committed; both candidates were removed by the scripts that
wrote them.

### Measurement 1 — the candidate against the contract as it stands

```kotlin
class AuroraSystemService(context: Context) : SystemService(context) { override fun onStart() {} }
```

```
AuroraSystemService.kt:3:16: error: unresolved reference 'content'.
AuroraSystemService.kt:4:8:  error: unresolved reference 'com'.
AuroraSystemService.kt:7:36: error: unresolved reference 'Context'.
AuroraSystemService.kt:7:47: error: unresolved reference 'SystemService'.
```

Two variants failed, `android_common` and `linux_glibc_common` — the second because
`aurora-platform` is `host_supported: true`, and a host variant can never have Android in it.

### Measurement 2 — a device-only module

```
java_library {
    name: "…",
    static_libs: ["aurora-runtime"],
    libs: ["services.core"],
    platform_apis: true,
    installable: true,
}
```

`build completed successfully (08:37)`. Two imports, and no third: `android.content.Context` and
`com.android.server.SystemService`.

### The predictions

**Held — the surface is substantially smaller than `android.*`.** One package: `android.content.`.

**Refuted — the amendment is *not* two lines**, and the way it failed is worth more than the
prediction was. `forbid-dep: services` does not need relaxing, because it never applied.
`arch-test.sh` matches with `grep -qE "\"$dep\""` — the pattern carries both quotes — so
`libs: ["services.core"]` does not contain `"services"`.

**And that is not a gate defect.** The matcher does exactly what the contract says: it forbids a
named module, and `services.core` is a different module that was never named. `runtime.contract`
settles the convention by example — it enumerates `framework` **and** `framework-minus-apex`, two
members of one family, listed separately because the file names modules rather than prefixes.

So the finding is a **contract that names one member of a family**, and it is not about Android at
all: `aurora-platform` could depend on `services.core` today and no gate would say a word. It needs
fixing whether or not anything here proceeds, which is why it is not part of the decision below.

### What was not predicted at all, and it changes the deliverable

**The hook cannot live in `aurora-platform`.** Three properties of that module each block it
independently:

| property | why it blocks the hook |
|---|---|
| `host_supported: true` | a linux_glibc variant exists and can never resolve `android.` |
| `sdk_version: "core_current"` | no Android on the classpath, and `arch-test.sh` **fails any module that changes it** — *"the classpath guarantee is gone"* |
| `aurora-platform-tests` statically links it | the `java_test_host` running all 356 tests would lose its dependency the moment the module went device-only |

And a fourth, about the gate rather than the module: `arch-test.sh` reads **one** `source-root` per
contract (`value_of`, not `values_of`). A second source directory under the same contract would be
**invisible to the gate** — Android would enter the tree at exactly the point nothing was watching.

### What the measurement proves, stated no wider than it is

> **`aurora-platform` cannot hold the hook and keep all three of the roles it currently plays:
> host-supported, `core_current`, and the static dependency of the host test module.**

That is the whole of it. It says the build graph has to change; it does **not** say how, and it
names no module.

**A fourth module is one way and not the only one.** The measurement refutes none of these:

| | |
|---|---|
| split `aurora-platform` into host and device variants | Soong supports per-variant properties; nothing here tested whether they reach far enough |
| separate the API from the implementation, and let only the implementation see Android | the layering Aurora already uses one level down |
| a fourth module | the shape the compiler happened to make cheapest to measure |
| something else Soong offers | nobody has surveyed |

The last row is the honest one. Measurement 2 used a new module because a new module was the fastest
way to ask the compiler a question — **not** because the alternatives had been weighed. Reading the
instrument's shape as the answer would be taking the first option the tooling made convenient.

### Recorded rather than acted on

A change to the build graph is an architectural decision and not a task's to make quietly. Task 3
stops at the measurement.

What the measurement buys is that the decision is now cheap: the compiler has already said the
surface is two imports wide, so no part of the choice rests on a guess about scope.

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
3. **Task 3 — the minimal Android surface.** See below. Before any code imports Android, not
   alongside it.
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
