# ADR-016 — Contested resources go through an RRO Aurora owns; uncontested ones stay in the overlay directory

**Status:** accepted · 2026-08-07 · Sprint 09, after Task 3.1

## Context

Task 3.1 measured the number ADR-015 was waiting for:

```
vendor      0 – 4
product     5 – 38
system_ext  39
```

`system_ext` outranks everything, measured three ways and proved to be *partition*-caused rather than
manifest-caused, because the probe declared `android:priority="1"` — a value that could not have
helped — and won anyway.

Aurora already installs to `system_ext`: `aurora-platform-android` is `system_ext_specific: true`.

## Decision

**Contested resources go through a dedicated `runtime_resource_overlay` module Aurora owns, installed
to `system_ext`. Uncontested resources stay in `frameworks/base/aurora/overlay/`.**

The dividing line is *contest*, not mechanism preference, and the evidence for it is already in the
tree — one of each, with opposite outcomes:

| resource | who else defines it | Aurora's directory overlay | outcome |
|---|---|---|---|
| `config_deviceSpecificSystemServices` | nobody | wins | **Boot PASS.** Aurora has been starting from it since Sprint 03 |
| `config_pluginAllowlist` | Lineage | loses | Aurora's entry silently absent, Task 3.0 |

So the directory overlay was never broken. It is sufficient exactly when Aurora is the only party
writing the resource, and it fails exactly when it is not — because
`PRODUCT_PACKAGE_OVERLAYS` merges by directory order and Aurora is appended last.

**That makes this a per-resource decision with a test, rather than a global switch.** A new Aurora
resource asks one question: *does anything upstream already define this?* If no, the overlay directory
is correct and cheaper. If yes, it needs the RRO.

### The mechanism

```
frameworks/base/aurora/rro/systemui/     AuroraSystemUIOverlay
    system_ext_specific: true            → priority 39, above product's 38
    certificate: "platform"              → same key as the auto-generated RROs
    targetPackage com.android.systemui, isStatic=true
```

Into the image by `PRODUCT_PACKAGES`, which is how every Aurora artifact already ships. It is outside
`frameworks/base/aurora/overlay/` deliberately: that path is a `PRODUCT_PACKAGE_OVERLAYS` root and the
build scans it for `<root>/<resource-dir>` matches, so a Soong module living inside it would be two
things at once.

## The consequence that nearly went unnoticed: the gate would become tautological

ADR-015's gate reads the merged array out of the artifact and compares it to the declared set. Under
the old mechanism the artifact was the *product* RRO — a file Lineage writes — so the comparison had
something to say.

Under this mechanism **the artifact is Aurora's own RRO, built from Aurora's own contract.** Comparing
them proves only that a file matches itself. The gate would go green, stay green, and measure nothing.

So the check changes shape. Three sets, not two:

```
UPSTREAM  = the array as found in the product RRO and SystemUI.apk   (AOSP + Lineage)
AURORA    = the array as found in Aurora's RRO                       (what actually wins)
DECLARED  = expect-entry lines in the contract

PASS  iff   AURORA == DECLARED == UPSTREAM ∪ { aurora.platform.systemui }
```

**The left equality is intentionally retained.** Under a normal build it is tautological — the RRO is
generated from the contract, so of course they agree — but it still detects an RRO that was
hand-edited, partially built, or otherwise corrupted, and those are exactly the cases where a
build-time gate is the only thing looking.

**The right equality is the gate.** When Lineage adds a plugin, `UPSTREAM` grows, the union stops
matching `DECLARED`, and the third verdict ADR-015 named fires — a human decides whether Aurora
carries the new entry forward.

This is what ADR-015 meant by judging each candidate on *does the gate still work under it?* It does,
but not unchanged: **owning the winning artifact costs you the ability to use it as evidence about
yourself.**

## Rejected

**Reordering `PRODUCT_PACKAGE_OVERLAYS` so Aurora comes first.** It works on the emulator, where
Aurora's patch controls the product makefile. It does not work on `lineage_beyond2lte`: the only hook
Aurora owns there is `device/samsung/beyond2lte/device.mk`, which appends to
`DEVICE_PACKAGE_OVERLAYS` — and Task 3.0 measured that this lands in the **vendor** RRO at priority
0–4, below Lineage's product RRO. Making it work would require Aurora to write
`PRODUCT_PACKAGE_OVERLAYS` from a device makefile *and* land ahead of an inherited list, which is two
unmeasured things in a mechanism whose ordering has already bitten once.

**Patching Lineage's overlay file.** Refused by ADR-015 for reasons that have not changed: the gate
supplies the loud-failure property the patch was wanted for, and ADR-013 forbids a patch that carries
the subject.

**Keeping the directory overlay and accepting the loss.** Not a candidate, listed because it is what
happens by default: Aurora's plugin does not load, on the emulator as much as on a `user` build, and
nothing reports it.

## Consequences

- **`frameworks/base/aurora/overlay/frameworks/base/packages/SystemUI/res/values/config.xml` is
  deleted.** It cannot stay: two Aurora files declaring the same array is precisely the drift ADR-015
  exists to prevent, and the dead one is the one that loses — the worst of the two to leave lying
  around, because it looks like the mechanism.
- **The framework-res overlay stays**, and this ADR is the reason it is not "cleaned up" for
  consistency. It works, it is uncontested, and Boot PASS is the evidence.
- **`verify-plugin-allowlist.sh` grows a second subject** and the contract grows a declaration of
  which artifacts carry `UPSTREAM`. The gate gets stricter, not looser.
- **Aurora now ships an APK it did not previously have**, separate from ADR-014's plugin. Two APKs
  with different jobs: one draws, one declares.
- The probe from Task 3.1 is deleted with this change. It answered its one question.

- **No Aurora resource migrates to an RRO by default.** Future resources ask whether they are
  contested first. Only a contested resource pays the cost of becoming its own runtime artifact — a
  Soong module, an installed APK, a partition dependency, and a gate that can no longer use that
  artifact as evidence about itself. **The dividing line is contest, not mechanism preference**, and
  reading this ADR as *"RRO is the better mechanism"* inverts it.

## Confirmed at runtime, 2026-08-07 — and the licence was re-measured rather than inherited

ADR-015 licensed a build-time-only gate on a measured equivalence between `aapt2 dump` and
`cmd overlay lookup`. **That measurement was taken on the product RRO.** This ADR moves the winning
artifact to a different partition, a different module type and a different resolution path, so the
licence did not carry over — the clause in ADR-015 that says it breaks with the path is the reason
this boot happened rather than being skipped.

```
aurora.rro.systemui    STATE_ENABLED   mIsMutable=false   mPriority=39
                       /system_ext/overlay/AuroraSystemUIOverlay.apk

cmd overlay lookup com.android.systemui:array/config_pluginAllowlist
    Found initial: /system_ext/overlay/AuroraSystemUIOverlay.apk
    Overlaid:      /system_ext/overlay/AuroraSystemUIOverlay.apk
    Best matching is from default configuration of aurora.rro.systemui
      com.android.systemui
      com.android.systemui.plugin.globalactions.wallet
      org.lineageos.settings.device
      aurora.platform.systemui
```

Build artifact and runtime agree again, on the new path. **The chain contract → built artifact →
runtime is closed**, and each link was measured rather than assumed.

The probe's removal was confirmed in the same boot: `/system_ext/overlay/` holds one APK, and
`dimen/status_bar_clock_starting_padding` resolves to `12.0dip` — the vendor RRO's value, which is
what it was before the probe existed. Deleting an instrument is also a change that needs checking.

## What actually decided this

Not that `system_ext` outranks `product`. That number only made one candidate *possible*.

Aurora chose the mechanism under which **the gate retains the ability to say something false** — and
then paid for it by rewriting the gate, because the winning artifact stopped being usable as evidence.
A mechanism that made the gate green by construction would have been cheaper and would have been
worse.

*Evidence decides mechanism*, and the test is not "does this work?" but "if this were wrong, what
would tell us?"
