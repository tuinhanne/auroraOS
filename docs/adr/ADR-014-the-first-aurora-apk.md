# ADR-014 — The first Aurora APK, and the process boundary it makes visible

**Status:** accepted · 2026-08-07 · Sprint 09, between Task 2 and Task 3

## Context

Sprint 09 Task 2 found that Aurora's first visible feature goes inside SystemUI, as a plugin behind
an extension point AOSP already built. A SystemUI plugin is an installed **APK**: it is discovered by
a `PackageManager` query, loaded through `createPackageContext` and a `ClassLoader`, and its
`<service>` is what makes it findable at all.

Aurora has never shipped an APK. Every module so far is a `java_library`, and the newest —
`aurora-platform-android` — is `platform_apis: true`, `system_ext_specific: true`, `installable: true`,
and sits on the system server classpath through `PRODUCT_SYSTEM_SERVER_JARS_EXTRA`.

Three placements were on the table, and Task 2 explicitly refused to choose between them because it
had surveyed the *runtime seam* and this is a question about the *build graph and ownership*:

1. inside `aurora.platform.android`
2. an independent Soong module in the Aurora tree, entering the image via `PRODUCT_PACKAGES`
3. a separate repository, pulled in at the product layer

ADR-012's rule applies: the build graph settles this, not preference.

## Decision

**Option 2, with its own layer.** A new Soong module, source in the Aurora tree, a contract of its
own:

```
layer:          platform-systemui
module:         aurora-systemui-plugin        android_app, not java_library
package-root:   aurora.platform.systemui
source-root:    platform/systemui/java
certificate:    platform
system_ext_specific: true
```

Answering the five questions directly:

| question | answer |
|---|---|
| **ownership** | Aurora. Source under `frameworks/base/aurora/`, inside the tree `arch-test.sh` walks |
| **where it is built** | in the platform build, as an `android_app` beside the other Aurora modules |
| **signing** | `certificate: "platform"`, and this is forced, not chosen — see below |
| **into the image** | `PRODUCT_PACKAGES` + `system_ext_specific: true`. **Not** `PRODUCT_SYSTEM_SERVER_JARS_EXTRA`: it is an installed app, not a classpath entry |
| **why better** | it is the only option that keeps the plugin inside the gate, and the only one Soong permits at all |

### Signing is forced

SystemUI declares `com.android.systemui.permission.PLUGIN` with `protectionLevel="signature"`. A
plugin that is not signed with the platform key cannot hold the permission, and
`PluginActionManager` will not load it. `ExamplePlugin/Android.bp` already shows the shape:
`certificate: "platform"`, `libs: ["SystemUIPluginLib"]`, `platform_apis: true`.

This is not a decision Aurora gets to make, and it is worth writing down because it eliminates
option 3 in its most likely form: a **prebuilt** APK from another repository would carry whatever key
signed it there, and would be refused on any build signed with different keys. Option 3 survives only
as *source in another repo, built here*, which is where its real cost lives.

### Why option 1 fails, and why Soong is the weaker half of the reason

Strictly, it is impossible: a Soong module is `java_library` **xor** `android_app`.
`aurora-platform-android` is the former, an APK must be the latter, so "inside
`aurora.platform.android`" cannot mean one module.

The weaker reading — a second module in the same directory, under the same contract — is possible
and still wrong, and the reason is the thing this ADR is really for:

> **`aurora-platform-android` runs in `system_server`. The plugin runs in SystemUI's process.**

They are not two halves of one layer. They can never share an object; **every interaction across the
boundary is IPC by construction.** The boundary exists before any implementation and independently of
what checks it, so crossing it is not a layering mistake — it is a violation of Android's process
model, and there is no version of the code in which it works.

ADR-012 created the fourth layer because of the Android dependency, which is a fact about the build:
`sdk_version` and `platform_apis` are module-global, so the boundary had to become a module boundary
to be expressible at all. **This one is not a fact about the build.** Soong happens to reflect it —
`java_library` xor `android_app` — but Soong is reflecting a separation that Android already imposes,
and if Soong changed tomorrow the separation would be exactly where it is. That is the more durable
reason, and it is the reason this ADR rests on.

And a second source root under one contract is exactly the mistake `platform-android.contract:18`
already records: `arch-test.sh` reads **one** `source-root` per contract, so the new directory would
be unwatched at precisely the point the new boundary appears.

### The gate enforces the process boundary for free

`check_aurora_layering` treats `allow-aurora-import` as a default-deny whitelist. So the new contract
lists:

```
allow-aurora-import: aurora.sdk.
allow-aurora-import: aurora.runtime.
allow-aurora-import: aurora.platform.
```

and **omits `aurora.platform.android.`** — which makes an import across the process boundary a red
gate rather than a runtime surprise. No new contract verb, no new check. The boundary is expressed by
what the allow list does not say, which is how the aurora-side of every contract already works.

### Why option 3 is refused

Not on principle — ADR-011 governs *upstream* modifications and a separate Aurora repo is not
upstream, so nothing forbids it. It is refused because of what it costs:

`tools/arch-test.sh` walks `frameworks/base/aurora/`. Move the plugin out and the gate stops seeing
the one Aurora artifact that builds a view hierarchy — the artifact that will exercise the
`android.view.` allowance that `platform-android.contract:68` flags as *the one to watch*. Splitting
the repository splits the gate, and it would do so at the worst possible place.

## Consequences

- **The `android.view.` note gets tested, in a new contract rather than the one that flags it.** The
  plugin's allow list starts empty and grows by what a compiler demands, per ADR-012.

- **Sprint 08's deliberately-unwired frame source now has an address, and it is not
  `AuroraSystemService`.** `ChoreographerFrameScheduler` binds to `Looper.myLooper()` and refuses
  `postFrame` from another thread. The frames must come from SystemUI's main thread, so the scheduler
  is constructed in the plugin. Sprint 09's spec said where it lives follows Question 1's answer;
  this is that answer.

- **`AuroraSystemService` may have no consumer, and that has to be said out loud.** The plugin has a
  `Context` and can build `AndroidServiceProvider(context)` itself — `AndroidVolumeSource` only needs
  an `AudioManager`, which SystemUI has. Aurora has no Binder interface, so talking to the
  `system_server` instance is not currently possible anyway. **So the first visible Aurora feature
  will not use the service Sprint 03 booted.** That is a real finding, not a tidy one. It does not
  change this decision — the plugin has to be where the pixels are either way — but *what
  `system_server` Aurora is for* is now an open question, and it needs its own ADR rather than being
  settled by whichever process happened to get the feature.

- **One thing is decided here and one hazard is only named.** The user-build allowlist entry belongs
  in `frameworks/base/aurora/overlay/frameworks/base/packages/SystemUI/res/values/config.xml`, which
  Aurora owns, so no patch. But `vendor/lineage/overlay/common/.../SystemUI/res/values/config.xml`
  **already defines `config_pluginAllowlist`**, and Aurora's overlay root is appended to
  `PRODUCT_PACKAGE_OVERLAYS` after Lineage's. Which of two overlays wins for the same resource has
  **not been measured**, and if Lineage's wins, Aurora's entry vanishes silently — the same failure
  species as the `userdebug` gate Task 2 found, and invisible in the same way.

  The measurement that settles it: build, then read the merged array out of the produced SystemUI
  resources rather than reasoning about overlay precedence.

  **This is not advice to Task 3; it is Task 3's opening gate.** Writing plugin code before the merged
  array is known would not risk a silent failure — it would *build a second one*, on top of the
  `userdebug` gate Task 2 just found, and the two would be indistinguishable from the outside: in both
  cases the plugin does not load, AOSP's dialog keeps working, and nothing is logged. Sprint 09's spec
  carries it as an exit criterion for that reason.

- Aurora gains a fifth Soong module and a sixth contract. `verify-motion-tests.sh`'s 356 host tests
  are untouched: the plugin is device-only, like `aurora-platform-android`, and for the same reason.
