# AuroraOS

An Android platform layer built on top of LineageOS 23.2 (Android 16).

This repository holds only the parts that are ours. The LineageOS source tree
itself is not vendored here: it is fetched with `repo` on the build machine and
these files are overlaid onto it.

---

## Layout

```
frameworks/base/aurora/     Aurora platform module (see its own README)
sync-to-vm.ps1              Push local sources to the build VM
vm-apply-code.sh            Counterpart that runs on the VM
CHAY-EMULATOR.md            Emulator run notes
```

Paths mirror their location inside the LineageOS tree, so `frameworks/base/aurora`
here maps to `<lineage>/frameworks/base/aurora` on the build machine. The sync
script relies on that, which is why the structure is kept identical.

---

## Aurora platform module

Three layers with one-way dependencies:

```
aurora.platform  →  aurora.runtime  →  aurora.sdk  →  (nothing)
```

Only `aurora.platform` is ever allowed to touch `android.*`. The two layers below
are pure Java, so they unit test on a host JVM with no device or emulator.

Full design notes: [`frameworks/base/aurora/README.md`](frameworks/base/aurora/README.md).

---

## Build

On a machine with the LineageOS 23.2 tree synced:

```bash
source build/envsetup.sh
lunch lineage_sdk_phone_x86_64-bp4a-userdebug

m aurora-sdk aurora-runtime aurora-platform
m aurora-platform-tests
atest aurora-platform-tests
```

---

## Status

**Sprint 01 — Platform bootstrap: complete.**

| Exit criterion | Result |
|---|---|
| Build PASS | `m aurora-*` and a full-tree `m` both succeed |
| Boot PASS | Nothing is installed into `system/`, so no execution path changes |
| No UI change | Same reason |
| Unit tests | 25 passing on the host JVM |

Sprint 01 deliberately wires nothing into Android. The module compiles and is
tested, but no code runs on the device yet. Sprint 03 is where Aurora is
initialized from `SystemServer` and behaviour actually changes.

**Sprint 02 — Architecture boundary: complete.**

| Exit criterion | Result |
|---|---|
| `arch-test.sh` PASS | 27 checks, 0 failures, exit 0 on the build machine |
| Each check fails on a violation | All four check types were individually broken, observed to exit 1, then reverted |
| Contracts for all four layers | `sdk`, `runtime`, `platform`, and `device` (rules recorded; the layer itself is deliberately not created yet) |
| Build still passes | `m aurora-sdk aurora-runtime aurora-platform` rc=0 |

The `aurora.device` layer has no module on purpose. With one target device there is nothing
real to abstract, so its contract records the rules and `arch-test.sh` reports it as `skip`
until the layer exists.

**Sprint 03 — Design system: complete.**

| Exit criterion | Result |
|---|---|
| Design tokens compile | `m aurora-sdk aurora-runtime aurora-platform` rc=0 |
| Unit tests | 45 passing, up from 25 |
| Architecture wall intact | `arch-test.sh` 27 checks, 0 failures |
| Wall covers Kotlin | A forbidden import in a `.kt` file was observed to fail, exit 1 |

Seven token files under `aurora.sdk.design`, reached through the `DesignTokens` facade.
Values are plain `Int`, `Long` and `Float` rather than `Dp` or `Color`: `aurora.sdk` has
neither Android nor Compose on its classpath, and keeping tokens as data means one set feeds
Compose, the View system and XML resources alike.

This sprint also taught `arch-test.sh` to scan Kotlin imports. Without that, the seven files
added here would have sat outside the boundary Sprint 02 built.

**Sprint 04 — Aurora runtime services: complete.**

| Exit criterion | Result |
|---|---|
| Compile PASS | `m aurora-sdk aurora-runtime aurora-platform` rc=0 |
| Unit tests | 54 passing, up from 45 |
| Architecture wall intact | `arch-test.sh` 27 checks, 0 failures |

Seven service contracts under `aurora.sdk.service`: animation, theme, notification, gesture,
volume, power and island. Contracts only — no implementation exists yet, and every accessor on
`AuroraRuntime` fails with a message naming the missing service.

**Sprint 05 — Event bus: complete.**

| Exit criterion | Result |
|---|---|
| 100% tests pass | 84 passing, up from 54 |
| Compile | `m aurora-sdk aurora-runtime aurora-platform` rc=0 |
| Architecture wall intact | `arch-test.sh` 27 checks, 0 failures |

Scoped, prioritised publish/subscribe under `aurora.sdk.event`. Beyond plain publish and
subscribe it carries three things that are painful to retrofit: sticky events, so a surface
created after a state change still starts in the right state; scopes, which are lifetimes as
much as filters, so closing a window retires its subscribers and sticky values in one call;
and `Disposable` handles instead of `unsubscribe(id)`, so whatever holds a subscription holds
the only thing needed to end it.

Subscribers are isolated from each other — a throwing one cannot stop the rest — and the
`AuroraDispatcher` seam decides where they run, which is what will let the platform post to the
main thread later without this module knowing that a main thread exists.

**Sprint 05.5 — Time infrastructure: complete.**

| Exit criterion | Result |
|---|---|
| Tests pass | 108 passing, up from 84, in 230 ms |
| Compile | `m aurora-sdk aurora-runtime aurora-platform` rc=0 |
| Architecture wall intact | `arch-test.sh` 27 checks, 0 failures |

`AuroraClock`, `RealtimeClock`, `FrameScheduler` and a stateless `Timeline`, plus a
`TimelineDriver` that ties them together. No physics: this answers only *how far through are
we*, and shaping that number is the animation engine's job.

Inserted before the animation engine on purpose. A spring solver that is slightly wrong
produces one ugly animation; a clock that is wrong makes every animation wrong, and those bugs
are unreproducible because they depend on real scheduling. With time behind a seam the whole
suite runs without a single sleep — 108 tests in 230 ms — and interruption, cancellation and
repeat boundaries are asserted exactly rather than approximately.

`Timeline` is deliberately stateless, so seeking is just querying a different argument. A
stateful timeline needs an explicit seek that resets counters, and that is precisely where
off-by-one errors around repeat boundaries live. The one bug this sprint did produce was on
such a boundary — a zero-duration timeline returned 0 instead of 1 at the instant its delay
elapsed — and the test caught it before the code was merged.

---

The interfaces live in `aurora.sdk` rather than beside their future implementations because
`aurora.runtime` is forbidden from importing `aurora.platform`. `ServiceProvider`, declared in
`aurora.runtime` and implemented later by the platform, is the seam that lets the runtime hand
out services it cannot see. That indirection is also what allows the service tests to run on a
host JVM against fakes, with no Android involved.

---

## Target device

Samsung Galaxy S10+ (`beyond2lte`, Exynos 9820). Development happens against the
`lineage_sdk_phone_x86_64` emulator target, which shares the framework layer with
the device build, so framework-level work carries over unchanged.
