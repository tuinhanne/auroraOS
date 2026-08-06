# ADR-012 — Build graph after the first Android dependency

**Status:** accepted · 2026-08-06 · Sprint 03

## Context

Sprint 03 Task 2 found that Aurora needs no upstream patch: AOSP's own
`config_deviceSpecificSystemServices` starts a class named by a resource overlay the device tree
already owns. What it does need is a class that extends `com.android.server.SystemService`, and
that class has to compile.

Task 3 measured what compiling it costs. Two imports, and no third:

```kotlin
import android.content.Context
import com.android.server.SystemService
```

built successfully in a module declaring `static_libs: ["aurora-runtime"]`, `libs: ["services.core"]`,
`platform_apis: true`, `installable: true`.

**The same code does not build inside `aurora-platform`**, and three properties of that module block
it independently:

| property of `aurora-platform` | why it blocks |
|---|---|
| `host_supported: true` | a `linux_glibc_common` variant exists and can never resolve `android.` — measurement 1 failed in both variants |
| `sdk_version: "core_current"` | no Android on the classpath, and `arch-test.sh` **fails any module that changes it**: *"the classpath guarantee is gone"* |
| `aurora-platform-tests` statically links it | the `java_test_host` that runs all 356 tests loses its dependency the moment the module goes device-only |

And one property of the gate rather than the module: `arch-test.sh` reads **one** `source-root` per
contract. A second source directory under the same contract is invisible to it, which would put
Android into the tree at precisely the point nothing is watching.

### What the measurement proves, and what it does not

> **`aurora-platform` cannot hold the hook and keep all three of the roles it currently plays.**

That is the whole of it. The build graph has to change; **nothing measured says how.**

**Measurement 2 used a new module, and that is not evidence for one.** A new module was the fastest
way to ask the compiler a question — it required touching nothing that already existed. Reading the
instrument's shape as the answer would be adopting the first option the tooling made convenient,
which is the failure this ADR exists to avoid rather than commit.

## Question

> **After the first Android dependency, how must the build graph change so that the invariants
> Aurora already has survive?**

Three invariants, stated as what would be lost rather than as properties of the current file:

1. **Everything below the Android boundary is verifiable on a host.** 356 tests run on a JVM with no
   device and no emulator. This is the property `ServiceProvider`, `FrameScheduler`,
   `AuroraDispatcher` and `VolumeSource` all exist to protect, and it is the one most easily lost by
   accident — a single static dependency in the wrong direction removes it.
2. **The classpath boundary is asserted, not described.** `sdk_version: "core_current"` is checked;
   a layer that quietly gained Android would go red today.
3. **Android enters at one place, and a gate is watching that place.** RULE-002 states the first
   half. `arch-test.sh`'s single `source-root` is what enforces it, and it is why a second source
   directory is a real question rather than a detail.

## Alternatives considered

None weighed. Each is recorded with what would decide it, so that the survey has something to do
other than restate preferences.

**Per-variant properties on `aurora-platform`.** Soong can give a module different properties for
its device and host variants. If `target: { android: { … }, host: { … } }` reaches far enough to
carry `platform_apis` and `libs` on one side only, nothing new is created at all.
*What would decide it:* whether Soong permits `platform_apis` per variant, and whether the host
variant can still be statically linked by a `java_test_host` while the device variant links
`services.core`. Neither has been tried.

**Split the API from the implementation.** `aurora-platform` keeps the types and stays host-testable;
a second module holds only what touches Android. This is the layering Aurora already uses one level
down — `ServiceProvider` is declared where it is needed and implemented where it can be.
*What would decide it:* whether the split falls on a real seam or an invented one. A split that
leaves an implementation module with one class in it is a fourth module wearing a better name.

**A fourth module.** What measurement 2 happened to build.
*What would decide it:* whether the other options fail. It is the residual, and its honesty depends
on the others having been asked first.

**Something else Soong offers.** Nobody has surveyed. This row exists because the previous three are
the options that occurred to one person in one afternoon, and the tool has more in it than that.
*What would decide it:* an hour spent reading how other AOSP subprojects with the same shape —
host-tested logic plus a system-server-facing shim — actually structure themselves.

### The question every alternative must answer

So the survey ends rather than trails off:

1. Does everything host-tested today stay host-tested?
2. Where does Android enter, and is that place covered by a contract with its own `source-root`?
3. What does `sdk_version` become, and does the gate still assert something rather than being told
   to look away?
4. **What does the *second* Android-facing artifact cost?** The hook will not be the last one —
   `ChoreographerFrameScheduler` is already named for Sprint 08. A structure that is cheap once and
   expensive twice is the wrong structure, and this is the question a decision made under the
   pressure of one hook would skip.

## The survey, run 2026-08-06

Five Soong shapes, each the smallest thing that asks one question. Three files of Kotlin — a
host-verifiable object, a `SystemService` subclass, and a JUnit test — reused across all of them, in
a probe directory each script deleted after itself.

| shape | result |
|---|---|
| **A** — one module, `sdk_version: core_current`, `target.android.{srcs, libs}` | **refuted** |
| **A2** — `platform_apis` under `target.android`, `sdk_version` under `target.host` | **refuted** |
| **D** — one module, `libs` at top level, `exclude_srcs` on the host variant | **refuted** |
| **D2** — one module, `platform_apis` module-wide, `services.core` under `target.android` | builds (03:19) |
| **D3** — D2, plus a `java_test_host` linking it | builds (03:24) |
| **B/C** — two modules, `java_test_host` linking the Android-free one | builds (03:30) |

### What killed the three

**A** — Soong orders API sets and checks the ordering across a dependency:

> *module "probe-a" variant "android_common": compiles against core Java API, but dependency
> "services.core" is compiling against private API.*

**A2** — the properties do not exist per variant:

> *unrecognized property "target.android.platform_apis"*
> *unrecognized property "target.host.sdk_version"*

That is structural rather than a policy: `sdk_version` and `platform_apis` are **module-global in
Soong**, so no spelling of "core_current on the host, platform on the device" exists. A is not
refuted in one form — it is refuted in every form.

**D** — `services.core` has no host variant, and a top-level `libs` applies to both:

> *dependency "services.core" of "probe-d" missing variant:*

### Two survivors, and B/C and C were the same shape all along

`probe-b-android` and a "fourth module" are the same Soong graph; the difference was only where
existing code lives. So the four alternatives are really **two**: one module carrying both halves,
or two modules.

### The four questions, for the two that build

Measured rows say what a build did. Derived rows say what the contracts would then have to say, and
are reasoning rather than measurement — marked so nobody has to guess which is which.

| | **D2/D3** — one module | **B/C** — two modules |
|---|---|---|
| host tests survive *(measured)* | **yes** — a `java_test_host` links it | **yes** — links the Android-free module |
| second Android artifact *(derived)* | another file in `target.android.srcs` | another file in the Android module |
| **`sdk_version`** *(measured)* | must be `platform_apis` **module-wide**; `arch-test.sh`'s *"sdk_version is core_current"* check fails for the whole layer | the core module keeps `core_current`; only the new module needs a different rule |
| **where Android enters** *(derived)* | a subdirectory of the same module, under the same contract — so the allow list admitting `android.content.` admits it for the Android-free half too | its own module, its own `source-root`, its own allow list |

### What the survey actually found

The two shapes are equal on everything except the two rows that carry the invariants:

> **D2 works, and works by removing the thing the gate was asserting.** `platform_apis` module-wide
> and one shared allow list mean the boundary between host-verifiable code and Android code still
> exists — it is just no longer expressible in a contract, and therefore no longer checked.

That is not an argument that D2 is wrong. It is the price, stated in the same terms the other rows
are stated in, so that choosing it would be choosing it rather than discovering it later.

---

## Decision

**Split.** Aurora's Android-facing code lives in a Soong module of its own, and the module that
everything below it depends on stays host-supported and `core_current`.

The survey above is kept in full, and the refuted shapes with it. They are not a record that
options were considered; they are why this one was chosen.

### The reason, and it is not that a second module is tidier

**The logical boundary and the build boundary become the same line.**

Aurora has always had a boundary between code that is verifiable on a host and code that knows what
Android is. Until now that boundary was stated — RULE-002, `platform.contract` — and checked by a
gate reading a source root. Under D2 it would still exist and still be true, and nothing in the
build would be able to say so: `platform_apis` module-wide removes the `sdk_version` assertion for
the whole layer, and one shared allow list admits `android.content.` for the half that must never
use it.

Under the split, the two lines coincide:

| | |
|---|---|
| the host module | `sdk_version: "core_current"` — Android is *absent from the classpath*, not merely unused |
| the Android module | its own `source-root`, its own allow list, and that list applies to nothing else |

So a later change that pulls `android.content.` into the host half is a **compile error**, not a
review catch. **The build artifact becomes evidence of the architecture** rather than a place where
the architecture is described.

That is the same move this repository has made everywhere else: `samplerFor`'s exhaustive `when`
replaced a runtime throw with a build failure, and gate 5 retired because *"a type error beats a
grep"*. This is that sentence applied to a layer boundary.

### What decided it was refutation, not preference

Three of the four alternatives were removed by the compiler. A2's message —
`unrecognized property "target.android.platform_apis"` — is structural: Soong has no way to express
"core_current on the host, platform on the device" in one module, so an entire branch of the design
space is closed rather than dispreferred.

D2 was not refuted. It was priced, and the price is stated above in the same terms as everything
else so that this decision is a choice between two known costs rather than a preference between two
descriptions.

Not "no decision yet" as a way of leaving the room. The exit condition is written down: the four
questions above, answered for each alternative, with what was tried recorded — including whatever
turns out not to work.

Deferred rather than decided because the measurement that prompted this ADR answers none of the four
questions for any option, and because the option it happens to demonstrate is the one that would
benefit most from nobody asking.

## Consequences

- **Sprint 03 Task 4 reopens**, with the build graph decided rather than discovered while building.
- A new Soong module, `platform_apis`, `libs: ["services.core"]`, statically linking the layer below
  it, and the only module in the tree that may import `android.`.
- **A new contract**, because `arch-test.sh` reads one `source-root` per contract and the new source
  root would otherwise be unwatched. It carries the narrow allow list Task 3 measured —
  `android.content.` and nothing adjacent — and it needs its own answer to the `sdk_version` check,
  which `platform.contract` currently states as though it were universal.
- `aurora-platform` is unchanged: still `host_supported`, still `core_current`, still what
  `aurora-platform-tests` links. **The 356 host tests are untouched by this decision**, which was
  the first of the three invariants and the one most easily lost.
- **RULE-002 holds by package.** The new module's package sits under `aurora.platform`, so *"Android
  is confined to `aurora.platform`"* stays true as written; what changes is that the confinement is
  now also a build fact.
- `ChoreographerFrameScheduler`, already named for Sprint 08, has a home the day it is written. That
  was question 4 of the survey, and it is answered by the same module rather than by a second
  decision.
- `patches/` stays empty and ADR-011 stays unused. Unchanged since Task 2, and not affected here.
