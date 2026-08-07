# Sprint 09 — The first visible Aurora feature

**Status:** design in review · 2026-08-06 · the first sprint whose result a person can see

Numbered 09 because 08 is the Android platform bridge and this may or may not need it — Question 2
decides. The number records order, not dependency.

**The subject is the first pixel, not the volume overlay.** `docs/features/volume-overlay.md`
specifies a candidate's behaviour in full and is not a decision that the candidate wins. A sprint
named after volume would answer *how do we build the volume overlay*; this one has to answer *what
should Aurora show first*, and those send you to different places.

---

## 1. Where the project stands

Sprint 03 closed with Aurora running inside `system_server`, verified by boot:

```
I Aurora : onStart: Aurora is running inside system_server
I Aurora : volume service resolved: media=0.33333334 steps=16 active=MEDIA
```

Everything below the Android boundary is host-tested. One service answers from a real
`AudioManager`. The animation runtime is complete, evidenced across four layers, and has never
drawn anything.

**The inventory has one row left that nothing owns: *anything that draws*.** It is now the only
thing between Aurora and a user seeing it, and it is this sprint.

---

## 2. Question 0 — what should Aurora show first?

Not *how do we show the volume overlay*.

The candidate list is not the point; the criteria are, because the criteria outlive the choice:

| criterion | why it matters here |
|---|---|
| **it is wanted for its own sake** | 06C.0's rule, one level up: a feature built to prove the drawing path is a subject created for an assertion |
| **failure is visible, not catastrophic** | the first thing drawn will be wrong in ways nobody predicted. Something that appears over a running system and goes away is recoverable; something in the boot path is not |
| **it needs one surface, not a layout system** | the first pixel should not also be the first time Aurora has a view hierarchy |
| **it can be judged by looking** | if nobody can tell whether it is right by watching it, it is the wrong first feature |

Volume overlay meets all four and has a behaviour spec already written. **That is a strong position
and not a decision** — Question 0 stays open until this sprint's first task compares it against at
least one alternative that was chosen for its own reasons rather than for being next on a list.

### The alternative has to be able to win

The failure mode this question is most likely to meet is not choosing wrongly. It is holding a
comparison whose outcome was fixed before it started: a favourite beside two options nobody would
ship, weighed honestly, and recorded as a decision. **Question 0 would then have closed before
Task 1 opened, while leaving a paper trail that says otherwise** — which is worse than not comparing
at all, because the paper trail is what a later reader trusts.

Candidates worth putting up, and none of them is a joke:

| candidate | the case for it |
|---|---|
| a static diagnostic surface | answers *can Aurora draw* and nothing else; needs no frame source; wrong the moment it ships to a user, which is itself informative |
| an "Aurora" watermark | permanent, trivially judgeable, and forces the question of what Aurora is allowed to put on someone's screen |
| a notification chip | small, wanted for its own sake, and lands where `IslandService` already points |
| the volume overlay | a behaviour spec exists, ADR-003 names the use case, and hardware keys make it testable without touching anything |

**The test for whether an alternative was real: if it had won, would anybody be surprised?** If the
answer is no for at least one of them at the moment the comparison starts, the comparison is a
comparison. If it is yes for all but one, Task 1 has written up a decision it had already made.

That test is worth applying out loud, in Task 1's own words, because it cannot be applied
afterwards — by then the winner looks inevitable regardless of how it was chosen.

---

## Task 1's result, recorded 2026-08-06 — **the volume overlay, and the comparison was between two**

### The surprise test, applied before comparing

§2 requires this stated in advance, because it cannot be applied afterwards. Asked of each candidate
*before* any criterion was scored:

| candidate | would anybody be surprised if it won? |
|---|---|
| static diagnostic surface | **yes** — it is the option that exists to make the sprint easy |
| "Aurora" watermark | **yes** — nobody has ever asked for it |
| notification chip | **no** |
| volume overlay | **no** |

**So the real comparison was between two**, and that is recorded rather than hidden. A four-way
table would have made the result look better-tested than it was; two candidates that could each
plausibly win is a comparison, and it is what this one had.

### Why the other two failed, and it was the first criterion

Not on cost. On *"wanted for its own sake"* — the rule 06C.0 established one level down.

**The static surface is the forbidden artifact, at feature scale.** It would exist so that the
drawing path could be proven, which is precisely *a subject created for an assertion*. The sprint
that built it would satisfy exit criterion 4 — something visible, a person looked — and fail
criterion 5 in the same motion.

It survives as something else, and the distinction is worth keeping: **a debugging affordance is not
a feature.** If Aurora later wants a way to see that it is alive, that is a developer tool, judged by
whether it aids debugging, not by whether anyone wants it on their phone.

**The watermark fails twice.** Nobody wants it, and it fails the second criterion in a way the others
do not: a transient overlay that is wrong disappears, while a permanent mark that is wrong is
permanently wrong. It also asks a question the project is not ready to answer — what Aurora may put
on someone's screen unasked — and asking it through an implementation is the wrong order.

### Notification chip against volume overlay

The genuine comparison. Both are wanted, both are transient, both are judgeable.

| | notification chip | volume overlay |
|---|---|---|
| wanted for its own sake | yes — `IslandService` exists, ADR-003 names Dynamic Island | yes — every phone has one, and ADR-003 names it too |
| failure visible, not catastrophic | yes | yes |
| **one surface, not a layout system** | **no** — a chip carries an icon, text, and probably progress. It is a small layout, and the first pixel would also be the first view hierarchy | **yes** — a level indicator is one shape and one number |
| judged by looking | yes, but needs a notification to arrive | **yes, and by anyone** — the hardware keys are the trigger. No instrumentation, no waiting for an event |
| what is already built | nothing. `NotificationService` is an unimplemented interface, and reaching real notifications means `NotificationListenerService` and a new span of Android surface | **the entire non-visual half.** `DefaultVolumeService`, 17 host tests, `AndroidVolumeSource`, and a real `AudioManager` answering on a device |

**The third row decides it and the fifth confirms it.** The chip would make the first visible thing
also the first layout, and its data source does not exist. The overlay's data source was verified on
a device this morning; what is missing is exactly one thing, which is what a first pixel should be.

That last point is the whole reason this comparison was worth holding rather than assumed:
**volume wins because everything except the surface is already true, not because it had a spec
written.** Had the chip's service been implemented instead, the same criteria would have picked the
chip.

### And it drags Sprint 08 in, which Question 2 predicted

`volume-overlay.md` §4 requires that a run of presses leaves the overlay visibly one object — no
replayed entrance, the indicator moving from where it is, no blink when a press interrupts a fade.
Those are motion requirements. Motion on a device needs `ChoreographerFrameScheduler`, which is
Sprint 08 and does not exist.

> **Resolved the same day.** Sprint 08 ran and closed: `ChoreographerFrameScheduler` exists in
> `aurora.platform.android`, and a spring overshot its target inside `system_server` on a device.
> The prerequisite this section named is met, so Task 3 is no longer blocked on it.
>
> Sprint 08 also left this sprint two things it did not ask for. **The frame source starves for
> ~3.7 s during early boot and recovers to ~57 fps** — so nothing here may animate at `onStart`,
> which the volume overlay does not, since a hardware key fires long after boot. And **the frame
> source has not been wired permanently**, on purpose: where it lives follows Question 1's answer,
> and wiring it before that would decide Question 1 by accident.

**So the answer to Question 2 is yes**, and it is a consequence of Question 0's answer rather than a
separate decision. A static first version is available and is not taken: it would ship the behaviour
`volume-overlay.md` §4 exists to forbid, and shipping a known-wrong version to make a sprint fit is
the same failure as building a subject to make an assertion possible.

---

## 3. Question 1 — where does Aurora draw from?

Aurora currently lives in `system_server`. That is where it *starts*; it is not obviously where it
should *draw*.

| candidate | what it means | what happens to AOSP's volume dialog | what would decide it |
|---|---|---|---|
| a window from `system_server` | Aurora adds a system window directly | it still runs. A key press would produce **two** overlays unless something suppresses it, and it belongs to SystemUI | is a system window from that process acceptable, and what does it cost when Aurora is wrong? |
| inside `SystemUI` | where the system's own volume dialog lives | it can be replaced in place, with nothing to suppress | an Aurora component in SystemUI is an upstream patch, or a package the product adds — which is it? |
| Launcher / QuickStep | what the README names for gesture work | untouched, and unrelated | almost certainly wrong for a system overlay, and worth refuting rather than ignoring |

> **Every candidate also implies a different relationship with Android's existing volume UI.** Some
> add a second overlay that must be reconciled; others replace the existing presentation. **This is
> part of choosing where Aurora draws, not a later implementation detail.**

The third column exists because without it the reasoning runs: *a surface is needed → a
`system_server` window is easiest → the overlay appears → so does AOSP's → now work out how to
suppress it.* By that point the product decision has been made by whichever surface was quickest to
obtain.

It does not favour any candidate. If the survey still chooses `system_server`, the decision reads
*"we accept the cost of suppressing AOSP's dialog"* rather than *"we forgot it was there"* — which
is the difference between a cost chosen and a cost discovered.

**None has been examined.** The README says gesture work belongs in `aurora.platform` acting on
SystemUI and Launcher3, which is a hint about a different subject and not an answer to this one.

Whatever wins, it lands in `aurora.platform.android` or in a new module beside it — ADR-012's rule
holds, and the allow list grows by what a compiler demands.

---

## Task 2's result, recorded 2026-08-07 — **inside SystemUI, through a door AOSP already built**

### What was looked at, including what turned out not to exist

The survey ran in four passes over the real tree on the build VM, and two of them found nothing —
recorded because exit criterion 2 asks for it, and because the misses are the reason the last pass
was needed at all.

| looked for | found |
|---|---|
| how AOSP's volume dialog is constructed | `VolumeDialogImpl`, `VolumeDialogComponent`, `VolumeModule` |
| whether that dialog is replaceable | **yes** — `VolumeDialog` is a plugin interface, and AOSP's own dialog is registered as its *default* |
| `PluginManagerImpl.java`, `PluginActionManager.java` | **do not exist.** They are `.kt`, and they live in `SystemUI/shared/`, not `SystemUI/src/` |
| `isPrivileged` in `shared/plugins/` | **not there.** It is on `PluginManager.Config` in `plugin_core/` |
| whether Aurora already owns anything inside SystemUI | a `dimens.xml` overlay under `device/samsung/beyond2lte/overlay/` |

The two misses are the same mistake twice: **guessing a path from a class name.** Both were found by
searching for content instead — the fourth pass looked for the permission string and the debuggable
check, not for filenames, and located the machinery immediately.

### The finding that decides it

`VolumeDialogComponent.java:95`:

```java
extensionController.newExtension(VolumeDialog.class)
        .withPlugin(VolumeDialog.class)
        .withDefault(() -> volumeDialog)
        .withCallback(dialog -> {
            if (mDialog != null) {
                mDialog.destroy();
            }
            mDialog = dialog;
            mDialog.init(LayoutParams.TYPE_VOLUME_OVERLAY, mVolumeDialogCallback);
        }).build();
```

**AOSP's volume dialog is not the implementation. It is the fallback behind an extension point.**
When a plugin supplying `VolumeDialog` appears, the callback fires, the old dialog is `destroy()`ed
and the new one is `init`'d with the same window type and the same callback.

That answers §3's third column outright, and it is the column that mattered:

| candidate | verdict |
|---|---|
| a window from `system_server` | **refuted.** It buys the double-overlay problem — two dialogs on one key press — and then has to solve it by suppressing UI that belongs to another process |
| **inside `SystemUI`** | **chosen.** The replacement is `destroy()`-then-`init`, performed by AOSP's own code. There is nothing to suppress and no upstream patch |
| Launcher / QuickStep | **refuted**, as expected. It owns no volume surface and the README's mention of it concerns gestures |

The §3 note asked whether an Aurora component in SystemUI is *an upstream patch or a package the
product adds*. It is the second, and that was not a foregone conclusion — it is true only because
the extension point already exists. Had it not, this candidate would have carried a patch to
`frameworks/base` and ADR-013's observer/subject distinction would have had something real to say.

### The two gates, and only one of them was visible from here

A plugin is an ordinary APK: `<uses-permission android:name="com.android.systemui.permission.PLUGIN" />`
plus a `<service>` filtering the plugin's action, which comes from the interface's
`@ProvidesInterface` annotation. `ExamplePlugin/Android.bp` shows the build shape —
`certificate: "platform"`, `libs: ["SystemUIPluginLib"]`, `platform_apis: true`.

**Gate one — signature.** SystemUI declares that permission `protectionLevel="signature"`, so the
plugin must be signed with the platform key. In a ROM Aurora builds, that is not an obstacle.

**Gate two — the build variant, and this is the one worth the sprint.**
`PluginActionManager.kt:222` and `PluginInstance.kt:301`:

```kotlin
if (!buildInfo.isDebuggable && !config.isPrivileged(component)) { ... }
```

On a **user** build, an unprivileged plugin is refused. `PluginManager.Config` gets its privileged
list from one place — `PluginsModule.java:99`:

```java
String[] plugins = context.getResources().getStringArray(R.array.config_pluginAllowlist);
```

**A string-array resource.** The same shape as `config_deviceSpecificSystemServices`, which is how
Sprint 03 put Aurora into `system_server` — a hook filled from an overlay rather than a patch. And
Lineage has already used it, in `vendor/lineage/overlay/common/.../SystemUI/res/values/config.xml`:

```xml
<string-array name="config_pluginAllowlist" translatable="false">
    <item>com.android.systemui</item>
    <item>com.android.systemui.plugin.globalactions.wallet</item>
    <item>org.lineageos.settings.device</item>
</string-array>
```

That third entry is a vendor adding its own plugin to the allowlist in this very checkout. It is a
description of precedent, not a standard — but it means Aurora's move here is one the tree already
makes.

### The trap this pass avoided, which is the whole reason it ran

**The emulator is `userdebug`, so `isDebuggable` is true, so the gate never fires there.** A plugin
that works perfectly on every device this project can currently boot would be silently refused on a
`user` build — and the failure would be invisible, because the plugin simply never loads and AOSP's
default keeps working. The overlay would just be *absent*.

This is RULE-018's point arriving in a new place: the measuring instrument is more permissive than
the thing being measured, so passing on the instrument proves nothing about the target. Sprint 08
Task 1 caught the same shape once already. **It was caught here by reading the gate, not by running
anything** — no available machine can ask this question, and that is exactly why the allowlist entry
has to be written now rather than when a `user` build first fails.

### What this costs Aurora, stated before Task 3 spends it

- **A new build module.** The plugin is an `android_app`, not a jar — the first APK Aurora has. Where
  it sits relative to `aurora.platform.android` is a real fork (a fourth-layer sibling, or a fifth
  thing that is not a layer at all), and ADR-012's rule says the answer is settled by the build
  graph. **Task 3 opens with that decision and it is ADR-shaped.** It is not made here.

  > **Settled by ADR-014**, written before Task 3 rather than during it: a fifth layer,
  > `aurora.platform.systemui`, in the Aurora tree. The deciding fact was not the Android dependency
  > but the **process** — the plugin runs in SystemUI, `aurora-platform-android` runs in
  > `system_server`, and omitting `aurora.platform.android.` from the new contract's
  > `allow-aurora-import` turns that boundary into a gate. ADR-014 also names two consequences this
  > section did not see: the frame source belongs in the plugin, and `AuroraSystemService` may have no
  > consumer.
- **A second overlay directory.** `frameworks/base/aurora/overlay` currently holds only
  `core/res/res/values/config.xml`. The allowlist entry needs
  `.../packages/SystemUI/res/values/config.xml` beside it — Aurora's own overlay root, so no patch.
- **The `android.view.` allowance stops being theoretical.** `platform-android.contract` already
  flags it as the one to watch; a `VolumeDialog` implementation is a view hierarchy, and this is
  where that note gets tested.

### What the survey did **not** establish

That a plugin actually replaces the dialog end to end. The survey proves the mechanism exists, is
reachable without a patch, and has a precedent in-tree. **Whether Aurora's plugin loads, replaces,
and draws is Task 3's result, not this one's** — and the first thing Task 3 should check is that
AOSP's dialog stopped appearing, because a plugin that fails to load looks exactly like a plugin
that was never written.

---

## Task 3.0's result, recorded 2026-08-07 — **FAIL, and the artifact was not the one being measured**

### Three runs before any evidence about the subject

Recorded because the distinction is the point of the verdict set:

| run | outcome | what it was evidence about |
|---|---|---|
| 1 | `LUNCH FAILED` | the target `bp2a` was wrong. **The instrument** |
| 2 | `LUNCH FAILED` | `bp4a` was right; `set -u` breaks `envsetup.sh`, and `lunch`'s output was redirected to `/dev/null`, so the cause was hidden by the measurement itself. **The instrument** |
| 3 | `UNREADABLE` | build and dump succeeded; the parser expected `(string8) "…"` and `aapt2` prints `["…"]`. **The instrument** |

None of the three said anything about `config_pluginAllowlist`. Had `LUNCH_FAILED` been folded into
`FAIL`, run 1 would have reported *the subject does not satisfy the contract* on the strength of a
typo — so the five-way verdict set earned itself on its first use.

Run 2's cause was written down on the VM before the run: `07-discover.sh` opens with
`# ... KHONG dung set -e/-u`. **A note existed and did not propagate to a new measurement** — which is
the same shape as the subject under test, where two overlay files each record a fact and neither
knows the other exists.

### The verdict, once the instrument worked

```
-- expected --                                       -- actual (RRO) --
   aurora.platform.systemui                             com.android.systemui
   com.android.systemui                                 com.android.systemui.plugin.globalactions.wallet
   com.android.systemui.plugin.globalactions.wallet      org.lineageos.settings.device
   org.lineageos.settings.device
-- missing --  aurora.platform.systemui
VERDICT=FAIL
```

**Lineage wins and Aurora's entry is dropped in silence** — the direction §3's warning named. `aapt2`
did compile Aurora's directory (`m SystemUI` step 47/54 names it), so this is not a wiring mistake;
the merge resolved against Aurora.

### Four things the measurement found that no source reading had suggested

**A. `PRODUCT_PACKAGE_OVERLAYS` is not baked into `SystemUI.apk` on this product.**
`build/make/target/product/generic_system.mk:125` sets `PRODUCT_ENFORCE_RRO_TARGETS := *`, so every
static overlay becomes a **runtime** resource overlay in its own APK.
`SystemUI.apk`'s own `config_pluginAllowlist` is `size=1` — AOSP's untouched entry. **The first pass
measured the wrong artifact**, and read `size=1` as a subject failure for a few minutes.

**B. The two overlay variables produce two different APKs.** `package_internal.mk:150-152`:

```make
runtime_resource_overlays_product := $(filter-out $(static),$(product_package_overlays))
runtime_resource_overlays_vendor  := $(filter-out $(static),$(device_package_overlays))
```

`PRODUCT_PACKAGE_OVERLAYS` → `product/overlay/SystemUI__…_rro_product.apk`.
`DEVICE_PACKAGE_OVERLAYS` → `vendor/overlay/SystemUI__…_rro_vendor.apk`.

**C. And they have different priorities, which closes the escape route.** Both are
`isStatic="true"`, `targetPackage="com.android.systemui"`, so both always apply — but
`priority=1` for the product RRO and `priority=0` for the vendor one. **Lineage's overlay reaches the
higher-priority APK.** So routing Aurora through `DEVICE_PACKAGE_OVERLAYS` — the variable Aurora's
device tree already uses — would lose at runtime instead of at build time. It is not a fix.

**D. Earlier in the list wins.** Aurora's directory is appended last and lost; Lineage's set survived
intact. Measured in one direction only — enough to refuse *append*, not enough to state a general
rule about the build system.

### And one finding that corrects an accepted ADR

`device/` contains **no reference to Aurora at all** — verified on the VM tree and again on the
committed repository. `device/samsung/beyond2lte/overlay/` holds five files, none of which names
`AuroraSystemService`, and neither `beyond2lte` nor `exynos9820-common` mentions Aurora anywhere.

**ADR-013's demonstration is therefore wrong** where it says deleting the emulator patch *"leaves
`lineage_beyond2lte` carrying Aurora through a device tree Aurora owns."* Nothing carries it. See
the correction appended to ADR-013; the removability **test** survives, its **demonstration** does not.

### Task 3.0b — asking the runtime, because everything above was still a model

The findings above came from build files and manifests. **That the product RRO therefore wins at
runtime was an inference**, and this sprint's own rule forbids acting on one. So the emulator was
booted and asked directly.

```
$ cmd overlay lookup --verbose com.android.systemui \
      com.android.systemui:array/config_pluginAllowlist

  Found initial: /product/overlay/SystemUI__…_rro_product.apk #14
  Overlaid:      /product/overlay/SystemUI__…_rro_product.apk #14
  Best matching is from default configuration of com.android.systemui.auto_generated_rro_product__

  com.android.systemui
  com.android.systemui.plugin.globalactions.wallet
  org.lineageos.settings.device
```

**Build merge and runtime resolution agree.** Three entries in the artifact, the same three at
runtime, from the named APK. There is no third layer between them, which is the thing worth knowing:
`aapt2 dump` on the RRO is a sound proxy for what SystemUI will read, and a future gate can use it.

Both auto-generated RROs are `STATE_ENABLED` with `mIsMutable=false` — always on, not enableable.

### And the runtime falsified part of the model

```
product RRO   manifest android:priority="1"   →   runtime mPriority = 32
vendor  RRO   manifest android:priority="0"   →   runtime mPriority = 4
```

**The manifest number is not the priority.** `mPriority` is a global index over every immutable
overlay, and it is blocked by partition:

| partition | count | priority range |
|---|---|---|
| `vendor` | 5 | 0 – 4 |
| `product` | 83 | 5 – 38 |
| mutable (emulation skins, `data/*.frro` themes) | — | `2147483647` |

So the ordering conclusion (product beats vendor) holds, but **the mechanism named for it was wrong**,
and with it the plan built on it: *"an Aurora RRO declaring `android:priority="2"` outranks Lineage"*
is refuted. Two is not a rank; it is an input the runtime discards.

**What decides is which partition the overlay is installed to** — and that has a consequence nobody
had looked for: `/product/overlay/partition_order.xml` exists as a documented override
(`OverlayConfig.java:76`), absent on this build, so the default order applies. Partition precedence is
itself product-configurable.

### What Task 3.0 did not establish

**Where `system_ext` ranks.** `/system_ext/overlay` is empty on this image, so the one partition
Aurora already ships to has no measured position at all. That is the missing number, and only a build
that puts an overlay there can supply it.

**And which repair is right.** That is not what a measurement decides. The artifact now says who wins
today; it does not say who *should own* `config_pluginAllowlist` — a resource whose value is a union
of AOSP's, Lineage's and Aurora's entries, where whoever writes it takes responsibility for
preserving the parts they do not own. Task 3 stays closed until an ADR settles that.

---

## 4. Question 2 — does the first pixel need animation at all?

The most useful decomposition available, and it is easy to miss because Aurora's animation runtime
is the most finished thing in the tree.

**Drawing something static needs no frame source.** A surface, a colour and a dismissal are enough
to answer *can Aurora put a pixel on screen*, and none of it touches `AnimationController`,
`FrameScheduler` or Sprint 08.

**The volume overlay, as specified, does need one.** `volume-overlay.md` §4 requires that a run of
presses leaves the overlay visibly one object — no replayed entrance, the indicator moving from
where it is, no blink when a press interrupts a fade. Those are motion requirements, and motion on a
device needs `ChoreographerFrameScheduler`.

So Question 2 is really a scheduling question:

```
static first    →  first pixel is cheap, proves the surface, and Sprint 08 stays separate
volume first    →  Sprint 08 becomes a prerequisite, and the first visible thing is also the
                   first animated thing, which is two unknowns arriving together
```

The second is what the roadmap assumed. It has never been examined, and *two unknowns arriving
together* is the shape this project has spent several sprints learning to avoid.

---

## 5. What this sprint inherits and must not spend

- **`volume-overlay.md` is a behaviour spec, not a plan.** Its §4 argument was written to stand
  without reference to the motion runtime, and its §10 records — deliberately below §4 — that those
  requirements may turn out to need one motion to hand over to another. **If this sprint chooses the
  volume overlay, the animation decisions must still be made on UX grounds and recorded before
  anyone checks what they imply for 06C.1.**
- **06C.1 is not a goal.** It opens if production creates a replacement subject and not otherwise.
- **No abstraction whose only purpose is to make something possible that nothing has asked for.**
  Sprint 04.1's mirror of the 06C.0 rule.

---

## 6. Task order

1. **Task 1 — choose.** Answer Question 0 against the criteria in §2, with at least one alternative
   examined seriously. Record what lost and why.
2. **Task 2 — the surface.** Answer Question 1 by survey, the way Sprint 03 Task 2 answered where
   the runtime belongs: look for what already exists before proposing anything.
3. **Task 3 — the first pixel.** Whatever Question 2 decided, static or animated. Opens on ADR-014's
   module, and **it is gated, not merely advised**:

   > **Task 3.0 — the allowlist measurement.** Add Aurora's entry to
   > `frameworks/base/aurora/overlay/frameworks/base/packages/SystemUI/res/values/config.xml`, build
   > SystemUI, and read the merged `config_pluginAllowlist` out of the produced artifact. **No plugin
   > code is written until that array is known to contain Aurora's package.**
   >
   > Not caution. Two overlay roots define the array and Aurora's is appended second; if Lineage's
   > wins, the entry vanishes with no error. Writing the plugin first would stack a second silent
   > failure on the `userdebug` gate Task 2 found, and **the two are indistinguishable from outside** —
   > in both, the plugin does not load, AOSP's dialog keeps working, and nothing is logged. Debugging
   > them together costs more than measuring one of them now.
4. **Task 4 — look at it.** The only sprint so far whose exit criterion is a person watching a
   screen. Boot PASS was checkable from a log; this is not.

Task 3 does not start until Tasks 1 and 2 have answers, and Task 2 may find that Sprint 08 has to
come first — in which case this sprint stops and says so, exactly as Sprint 03 stopped for ADR-012.

---

## 7. Exit criteria

- [x] Question 0 answered against stated criteria, with a real alternative weighed — **the volume
      overlay**, against the notification chip, and the surprise test was applied first
- [x] Question 1 answered by survey, with what was looked at recorded — including whatever turned
      out not to exist. **Inside SystemUI, as a plugin**; the two misses are recorded above
- [x] Question 2 answered — **animated**, and Sprint 08 was named as the prerequisite rather
      than something discovered mid-task
- [ ] **The merged `config_pluginAllowlist` in the built SystemUI artifact matches the declared set
      exactly** — read out of the build, not inferred from overlay ordering. Added after ADR-014; it
      gates Task 3 rather than reporting on it. **Currently red**: measured 2026-08-07, Aurora's entry
      absent. ADR-015 widened it from *contains Aurora* to *exact set*, because an overlay replaces an
      array and winning can drop the other parties' entries as silently as losing
- [ ] Something is visible on a device, and a person has looked at it
- [ ] Nothing was built whose only purpose was to make the looking possible

The fifth is the sprint's real test and the first in this project that no gate can check. The sixth
is what keeps the fifth honest. The fourth was inserted between them and the others deliberately: it
is the only one that must go green *before* work starts rather than as a result of it.

---

## 8. Retrospective notes, accumulated while the sprint is open

Written as they happen rather than reconstructed at the end, because the useful detail is the part
that stops being surprising once you know the answer.

### The recurring shape: the result was right and the explanation was wrong

Twice, and the second time is what makes it worth naming:

| | first belief | measurement | what actually held |
|---|---|---|---|
| **Task 2** | a plugin that loads on this device will load anywhere | read the gate in `PluginActionManager.kt` | true only because the emulator is `userdebug`. On a `user` build an unallowlisted plugin is refused, silently |
| **Task 3.0b** | the product RRO wins because its manifest says `priority="1"` | `cmd overlay dump` on a booted device | the product RRO does win — but `mPriority` is a partition-derived global index (32 vs 4). The manifest number is discarded |

**Both predictions were correct and both mechanisms were wrong.** A model that gets the outcome right
is not thereby validated, and the cost of the error only appears at the *next* decision — the
`priority="1"` reading had already produced a proposed fix (`android:priority="2"`) that could not
have worked.

The practice this argues for is narrow and cheap: when a measurement confirms what was expected, ask
the artifact *why* as well as *whether*. Task 3.0b cost one emulator boot and refuted a plan.

### A note existed and did not propagate

Run 2 of Task 3.0 failed because the measurement script used `set -u`, which breaks `envsetup.sh`.
That fact was already written on the build VM — `07-discover.sh` line 2 — in a sibling script written
for another purpose months of work earlier.

Not a knowledge gap; a **local convention that nothing carried forward**. No gate is proposed for it:
the next instance will be a different convention in a different script, and the only thing that
generalises is *read a script that already works before writing a new one*, which is a habit and not
a check. Recorded because it is the same shape as the subject the sprint was measuring — two overlay
files each holding a fact, neither aware of the other.

### A verification cost was removed by evidence, not by assumption

Task 3.0b established that `aapt2 dump` on the built RRO returns exactly what `cmd overlay lookup`
returns on a booted device, for this resource. That turns a runtime check into a build-time one and
deletes *flash → boot → adb* from the loop.

Worth keeping honest: the equivalence was **measured for one resource on one build**, and it is the
gate's licence rather than a law. If the resolution path changes — a mutable overlay, a fabricated
`.frro`, a `partition_order.xml` appearing — the licence needs re-measuring.
