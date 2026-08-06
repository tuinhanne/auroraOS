# ADR-013 — Patches that carry the observer, not the subject

**Status:** accepted · 2026-08-06 · Sprint 03 Task 4.4

## Context

Sprint 03 Task 2 concluded that Aurora needs no upstream patch, and that conclusion is correct for
the target it was measured against. Task 4.4's first survey found the target it was measured against
is not the one that can be booted.

```
device/samsung/beyond2lte      →  PRODUCT_NAME := lineage_beyond2lte    a physical Galaxy S10 5G
vendor/lineage/build/target/…  →  lineage_sdk_phone_x86_64              the emulator
out/target/product/            →  emu64a, emu64x                        the only products ever built here
```

The overlay Aurora owns — `device/samsung/beyond2lte/overlay/...` — applies to a product that has
never been built in this checkout and a device nobody here has. The product that boots comes from
`vendor/lineage`, which Aurora does not own.

So Task 2's result narrows rather than falls:

| target | upstream patch needed? |
|---|---|
| `lineage_beyond2lte` — the product | **no.** Aurora owns the overlay and the device makefile |
| `emu64x` — the only thing observable | **yes.** Its product makefile is upstream |

And that is uncomfortably close to something Sprint 06C.0 forbade:

> No production code may exist whose only purpose is to create a subject for an assertion.

A patch is production. A patch written so that a test can run looks exactly like the artifact that
rule exists to refuse, and the resemblance is close enough that it needs deciding rather than
assuming.

## Decision

**A patch may carry the observer. It may not carry the subject.**

Two kinds, and they are told apart by a test rather than by intent:

> **The removability test.** If the production target's behaviour and every Aurora artifact are
> unchanged by deleting the patch, it belongs to the observation environment. If deleting it makes
> the thing under observation disappear, it *is* the thing, and 06C.0 applies.

Applied here: the emulator product patch adds Aurora's module to a system server classpath and names
its service in a resource array. Delete it, and `AuroraSystemService`, `AndroidServiceProvider`,
`AndroidVolumeSource` and every contract still exist unchanged; `lineage_beyond2lte` still carries
them by way of a device tree Aurora owns. What disappears is the ability to *watch* it happen.

The day a `beyond2lte` device exists, this patch is deleted and no Aurora source changes. That is
the removability test passing, demonstrated rather than argued.

### Why the distinction is not a loophole

Because it is checkable, and because it can stop being true.

A patch that begins as environment can grow product behaviour — a flag here, a default there — and
the moment it does, deleting it changes what Aurora does. At that point it has become the subject,
and this ADR stops covering it: it needs its own decision, and probably belongs in a tree Aurora
owns instead.

**So the test is a standing obligation rather than a one-time classification.** A patch under
`patches/` that no longer passes it is not a patch that needs a better justification; it is a patch
in the wrong place.

### What this does not license

It does not license patching upstream to make a test convenient when an Aurora-owned mechanism
exists. The emulator qualifies because its product makefile is upstream by construction and there is
no Aurora-owned seam into it — not because patching was quicker than looking.

## Consequences

- `patches/` gets its first entry, and ADR-011's machinery its first consumer. Task 1 built it,
  Task 2 concluded it had none, and Task 4.4 found one somewhere nobody was looking. The machinery
  was not vindicated by being planned well; it was vindicated by being ready.
- **Sprint 03's framing changes.** Task 4.4 was described as *integration with the Android runtime*.
  It is integration with a **test product**: Aurora's implementation is complete, and what was
  missing is not an API but a bootable thing to watch it in.
- Boot PASS remains the sprint's exit criterion and is now reachable — on the emulator, which is not
  the production target. What it will prove is that Aurora starts inside a real `system_server`;
  what it will not prove is anything specific to a Galaxy S10 5G.
- The patch is expected to be deletable for as long as it exists. If it ever is not, that is a
  finding and not a maintenance task.
