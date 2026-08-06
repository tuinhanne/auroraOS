# ADR-012 — Build graph after the first Android dependency

**Status:** open · Decision **deferred** · 2026-08-06 · Sprint 03

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

## Decision

**Deferred.**

Not "no decision yet" as a way of leaving the room. The exit condition is written down: the four
questions above, answered for each alternative, with what was tried recorded — including whatever
turns out not to work.

Deferred rather than decided because the measurement that prompted this ADR answers none of the four
questions for any option, and because the option it happens to demonstrate is the one that would
benefit most from nobody asking.

## Consequences

- **Sprint 03 does not complete.** Task 4 needed a build graph, and the build graph is now a decision
  rather than a step. The sprint's other results stand: no upstream patch is needed, the surface is
  two imports wide, and the contracts now forbid families rather than names.
- `patches/` stays empty and ADR-011 stays unused. That was already true after Task 2.
- Nothing in `frameworks/` changes on account of this ADR. There is no code to write until it is
  answered, which is the point of writing it before there is.
