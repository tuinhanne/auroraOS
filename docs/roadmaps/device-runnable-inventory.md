# Inventory — what is missing before Aurora runs on a device

**Status:** gathered 2026-08-05, after Sprint 06C.0. Descriptive; it designs nothing and commits
to nothing. Its purpose is to let the sprint that makes Aurora device-runnable open with a subject
instead of an ambition.

---

## The correction this inventory produced before its first row

**The sprint already exists in the repository's roadmap, it is two sprints rather than one, and
neither is numbered 07.** `README.md` §Future Extension has named both since Sprint 02:

> **Sprint 03 — Wire into the system.** Let `aurora-platform` depend on `framework`, narrow
> `AuroraContext.hostContext()` from `Object` to `android.content.Context`, initialize
> `AuroraRuntime` inside `SystemServer`, and publish `AuroraServiceRegistry` as a system service.

> **Sprint 08 — Android platform bridge.** `ChoreographerFrameScheduler` and
> `ChoreographerAnimationDriver` in `aurora.platform`. `AnimationController.tick(FrameTime)` is
> already the entry point, so this is an adapter rather than a rework.

**And Sprint 03 blocks Sprint 08 absolutely.** `contracts/platform.contract` still reads:

```
forbid-import:  android.
```

enforced by `tools/arch-test.sh`. `ChoreographerFrameScheduler` imports `android.view.Choreographer`
and therefore **cannot be written** until that line is replaced by the narrow allow-list Sprint 03
was defined to introduce. The contract says so itself, three lines above:

```
# Sprint 03 will delete the `android.` line below and replace it with a narrower allow list.
```

So the first sprint is not the Choreographer one. Naming the work *runtime host* was right about the
scope and wrong about the ordering: the first move is a contract amendment with a gate behind it.

**Sprint 04 also half-landed, and this explains an observation 06C.0 made from the other side.**
`ffa2535` shipped seven service *interfaces* into `aurora.sdk.service` and no implementations — the
`aurora.platform` half of that sprint never happened. That is why `AnimationService` has never had
an implementing class, which `FlingFactory`'s KDoc noticed in 06B.2 and 06C.0 Task 1 confirmed
tree-wide.

---

## The inventory

| capability | exists | evidence | owner |
|---|---|---|---|
| a monotonic clock | **yes** | `RealtimeClock` | done, 06A |
| a seam to plug a frame source into | **yes** | `AnimationDriver(scheduler, controller, registry)`; `AnimationController.tick(FrameTime)` | done, 06A |
| a frame source aligned to the display | no | 2 `FrameScheduler` impls, both in `runtime/time/TestClock.kt` | Sprint 08 |
| an animation driver bound to it on device | no | nothing constructs `AnimationDriver` outside tests | Sprint 08 |
| permission for `aurora.platform` to see Android | no | `forbid-import: android.` in `platform.contract` | **Sprint 03** |
| a typed host context | no | `AuroraContext.hostContext()` returns `Object` | Sprint 03 |
| the runtime initialised on a device | no | `AuroraRuntime.initialize(AuroraContext)` exists; nothing calls it from `SystemServer` | Sprint 03 |
| the service registry published as a system service | no | `AuroraServiceRegistry` is a plain typed map | Sprint 03 |
| any service implementation at all | no | only `FakeThemeService`, inside a test | Sprint 04's unfinished half |
| Aurora present in a system image | no | all three modules `installable: false`; no product `.mk` references them | **unowned** |
| anything that draws | no | no surface, no view, no `SystemUI` hook, no `import android` anywhere | **unowned** |

Eleven rows, two of them green, and the two green ones are the two 06A deliberately built as seams.

---

## Re-measured 2026-08-06, after Sprint 03

The first update this table has ever had from evidence rather than from reading. Two rows are green
because a device produced them, one row is **refused** rather than done, one row's question turned
out to be wrong, and five are unchanged.

| capability | then | now | what changed |
|---|---|---|---|
| any service implementation at all | no | **yes** | `AndroidServiceProvider` hands out `DefaultVolumeService`, and a real `AudioManager` answered it on the emulator: `media=0.33333334 steps=16 active=MEDIA` |
| Aurora present in a system image | no | **yes** | `system_ext/framework/aurora-platform-android.jar`, with `.odex`/`.vdex`, named in `systemserverclasspath.pb`. This row was **unowned** and got done anyway, by the sprint that needed it |
| a typed host context | no | **refused** | `hostContext()` stays `Object` permanently. Narrowing it would put Android on the runtime's classpath and cost the 356 host tests. The cast is one line in `aurora.platform.android` |
| permission for `aurora.platform` to see Android | no | **question was wrong** | `aurora.platform` still cannot see Android and never will. ADR-012 put the Android surface in a fourth layer instead, because Soong's `sdk_version` and `platform_apis` are module-global |
| the runtime initialised on a device | no | **still no** | `AuroraSystemService.onStart` builds a service graph directly; `AuroraRuntime.initialize` is still called by nothing |
| the service registry published as a system service | no | **still no** | `AuroraServiceRegistry` is still a plain typed map |
| a frame source aligned to the display | no | still no | Sprint 08. The layer to put it in now exists |
| an animation driver bound to it on device | no | still no | Sprint 08 |
| anything that draws | no | still no | **still unowned** |

**Two of eleven moved, and the honest reading is that this table was mostly right.** What it got
wrong were the two rows phrased as steps toward a plan Sprint 03 then abandoned — the typed context
and the permission — and both were wrong in the same way: they described *how* the goal was expected
to be reached rather than the goal. The rows that described a capability rather than a route
survived the sprint unchanged.

### What Boot PASS did and did not settle

Aurora runs inside `system_server` and can read the device. It does not yet *start the runtime* —
`onStart` constructs what it needs and no more — and it publishes nothing other processes can call.
Those are the two rows above that stayed red while looking like they should have flipped, and they
are worth not confusing with what did happen.

---

## Two things the inventory settles about scope

**1. There is no Activity, and there should not be one.** This is a ROM tree. `device/` is
gitignored — upstream LineageOS clones kept only so the sync script has something to compare
against — and every Aurora module is `installable: false` with no product packaging. The host the
README names is `SystemServer`, and the surface the roadmap names for later work is `SystemUI` and
Launcher3 QuickStep. A demo `Activity` with a `ComposeView` would be a fourth kind of host, outside
the three-layer architecture, and would prove the engine runs somewhere it will never run.

**2. Nothing here is a rework.** The README's claim survives the inventory: `AnimationDriver`
already takes a `FrameScheduler` in its constructor and `tick(FrameTime)` is already the entry
point, so Sprint 08 is an adapter. The work is in Sprint 03, where the layer boundary is
deliberately weakened for the first time — which is exactly why Sprint 02 built `arch-test.sh`
first, and says so.

---

## What is unowned

**One of the two was done anyway.** *Aurora present in a system image* had no sprint and got done by
Sprint 03, because that sprint could not reach Boot PASS without it. That is worth noticing rather
than quietly ticking: an unowned row is not a row nobody will do — it is a row whose cost lands on
whoever needs it first, unbudgeted.

*Anything that draws* is still unowned, and is now the only thing between Aurora and a user seeing
it.


Two rows have no sprint anywhere in the roadmap:

- **getting Aurora into a system image** — `installable: false` is correct today and will have to
  change, and no sprint description mentions it
- **anything that draws** — every named sprint produces logic; none produces a pixel

Recorded here rather than discovered halfway through a sprint. Neither is large, and both are the
kind of thing whose absence is invisible until the first person tries to see something on a screen.

---

## What this document does not do

It does not decide whether to do any of this, does not design a bridge, and does not schedule
anything. Whether the project continues into device work is a decision outside this file. What it
provides is the answer to one question — *which artifacts are missing before the engine can run on
a device* — so that the sprint which does begin can be scoped by what is absent rather than by
whoever writes its first file.
