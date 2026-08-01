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
tested, but no code runs on the device yet. Sprint 02 is where Aurora is
initialized from `SystemServer` and behaviour actually changes.

---

## Target device

Samsung Galaxy S10+ (`beyond2lte`, Exynos 9820). Development happens against the
`lineage_sdk_phone_x86_64` emulator target, which shares the framework layer with
the device build, so framework-level work carries over unchanged.
