# Aurora Platform

The AuroraOS platform layer, built on top of LineageOS 23.2 (Android 16).

Sprint 01 establishes structure only. Nothing is wired into Android, no runtime
behaviour changes, and no UI is touched. The goal is to create a well-bounded
place for later sprints to plug into.

---

## Architecture

Three layers, with dependencies flowing in one direction only:

```
        ┌──────────────────────────────────────────────┐
        │  aurora.platform                             │
        │  System integration. The ONLY layer allowed  │
        │  to touch android.* (starting in Sprint 02). │
        │  → AuroraServiceRegistry                     │
        └───────────────────┬──────────────────────────┘
                            │ depends on
        ┌───────────────────▼──────────────────────────┐
        │  aurora.runtime                              │
        │  Process lifecycle and environment           │
        │  → AuroraRuntime, AuroraContext              │
        └───────────────────┬──────────────────────────┘
                            │ depends on
        ┌───────────────────▼──────────────────────────┐
        │  aurora.sdk                                  │
        │  Public, stable surface. Pure Java.          │
        │  → AuroraVersion                             │
        └──────────────────────────────────────────────┘
```

Three principles drive this design:

**One-way dependencies.** `sdk` knows nothing about `runtime`, and `runtime`
knows nothing about `platform`. This lets `sdk` be published to third parties
without dragging in system internals, and it means a change in the integration
layer can never break the public surface.

**Android is confined to one layer.** Only `aurora.platform` may `import
android.*`. The two layers below are pure Java, so they can be unit tested on a
host JVM with no device, no emulator and no Android stubs on the classpath.
That is why `AuroraContext.hostContext()` currently returns `Object` rather
than `android.content.Context`.

**Mistakes must fail loudly.** `AuroraRuntime.getInstance()` throws when the
runtime has not been initialized, instead of quietly creating one with an
environment nobody chose. A second `initialize()` throws, because otherwise the
second caller would believe its context had been installed when it had not.
`AuroraServiceRegistry.register()` refuses to overwrite, because a silent
replacement would leave two callers holding different objects for the same
service.

---

## Module

| Soong module | Package | Sources | Role |
|---|---|---|---|
| `aurora-sdk` | `aurora.sdk` | `sdk/java/` | Public, stable surface |
| `aurora-runtime` | `aurora.runtime` | `runtime/java/` | Lifecycle and environment |
| `aurora-platform` | `aurora.platform` | `platform/java/` | System integration |
| `aurora-platform-tests` | — | `tests/java/` | Host-side unit tests |

All three are `java_library` with `sdk_version: "core_current"` and
`host_supported: true`. They are marked `installable: false` because Sprint 01
ships nothing into the system image — the modules only need to compile, not to
be installed.

### Classes

**`AuroraVersion`** (`aurora.sdk`) — Version information. It keeps two numbering
schemes deliberately separate: the *release version* (`MAJOR.MINOR.PATCH`) is
what humans read and may change for reasons that do not affect callers, such as
a rebase onto a newer LineageOS tag; the *API level* is what code should branch
on, and only increments when the `aurora.sdk` surface actually changes. Feature
detection must use `isApiAtLeast()`, never the release numbers.

**`AuroraContext`** (`aurora.runtime`) — Abstraction of "where am I running".
Immutable, built through a builder, safe to share across threads.

**`AuroraRuntime`** (`aurora.runtime`) — Per-process entry point. Exactly one
runtime per process: `initialize()` → `getInstance()` → `shutdown()`. Thread
safe. `shutdown()` on an uninitialized runtime is a no-op so teardown paths can
call it unconditionally.

**`AuroraServiceRegistry`** (`aurora.platform`) — Service registry keyed by type
rather than by string name. This trades a little flexibility for compile-time
safety: a rename is caught by the compiler, and `get()` needs no cast at the
call site.

---

## Dependency

Within Aurora:

```
aurora-platform  →  aurora-runtime  →  aurora-sdk  →  (nothing)
```

External dependencies, as of Sprint 01:

| Module | External dependencies |
|---|---|
| `aurora-sdk` | none |
| `aurora-runtime` | none |
| `aurora-platform` | none |
| `aurora-platform-tests` | `junit` |

**Aurora does not depend on `framework`, and `framework` does not depend on
Aurora.** This is why Sprint 01 satisfies Boot PASS and No UI Change by
construction rather than by luck: the new code sits on no execution path in the
system.

Rule to preserve when extending: if a class in `sdk` or `runtime` needs to
`import android.*`, that class is in the wrong layer.

---

## Build & Test

```bash
source build/envsetup.sh
lunch lineage_sdk_phone_x86_64-bp4a-userdebug

m aurora-sdk aurora-runtime aurora-platform   # compile the libraries
m aurora-platform-tests                        # build the host unit tests
atest aurora-platform-tests                    # or run them through atest
```

The unit tests run on the build machine's JVM. No device or emulator is
required, so the edit–test loop is measured in seconds rather than hours.

---

## Future Extension

In expected order. Each step names the extension point already prepared for it:

**Sprint 02 — Wire into the system.** Let `aurora-platform` depend on
`framework`, narrow `AuroraContext.hostContext()` from `Object` to
`android.content.Context`, initialize `AuroraRuntime` inside `SystemServer`, and
publish `AuroraServiceRegistry` as a system service. This is the first step that
changes runtime behaviour, so Boot PASS must be verified for real rather than
holding by construction as it does in Sprint 01.

**Sprint 03 — First service.** Define a service interface in `aurora.sdk`,
implement it in `aurora.platform`, and register it. This is where the three-layer
boundary gets its first real test.

**Sprint 04 — Configuration.** Read system properties and overlays so the
runtime can toggle features without a rebuild.

**Later — Gesture work.** iOS-style gesture customisation belongs in
`aurora.platform`, acting on `SystemUI` and Launcher3 QuickStep. Because gesture
code lives in the framework layer and touches no device hardware, whatever is
developed on the emulator will behave identically on real hardware.

### What to watch out for

The strongest temptation when extending this is to let `aurora.runtime` "just
import android.* this once". Doing so costs host-side unit testing for both
lower layers, and once lost it is hard to recover. If the runtime needs
something from Android, define an interface in `runtime` and implement it in
`platform`.
