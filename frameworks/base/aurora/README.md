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
        │  to touch android.* (starting in Sprint 03). │
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
| `contracts/` | — | `contracts/*.contract` | Machine-readable layer rules |
| `tools/arch-test.sh` | — | `tools/` | Enforces the contracts |
| (part of `aurora-sdk`) | `aurora.sdk.design` | `sdk/java/aurora/sdk/design/` | Design tokens |
| (part of `aurora-sdk`) | `aurora.sdk.animation` | `sdk/java/aurora/sdk/animation/` | Animation contracts |
| (part of `aurora-runtime`) | `aurora.runtime.animation` | `runtime/java/aurora/runtime/animation/` | The animation engine |

Sources may be Java or Kotlin; every module globs both. `arch-test.sh` scans both too,
so the layer boundary does not depend on which language a file happens to use.

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

## Rules

Numbered so they can be cited in review and in commit messages.

**RULE-001 — Dependencies flow one way.** `platform` → `runtime` → `sdk` → nothing. Enforced
by `arch-test.sh` and, for the forbidden packages, by the compiler.

**RULE-002 — Android is confined to `aurora.platform`.** Only that layer may `import
android.*`. If a class in `sdk` or `runtime` seems to need an Android type, it is in the wrong
layer; declare an interface where it is needed and implement it where Android is allowed.
`ServiceProvider` is the reference example.

**RULE-003 — Mistakes fail loudly.** Never return a half-built object or silently do nothing
where the caller expects work. `AuroraRuntime.getInstance()` before initialization throws,
`AuroraServiceRegistry.register()` refuses to overwrite, and a missing service names itself in
the exception.

**RULE-004 — Design tokens MUST be declarative.** A token describes; it never executes.

Allowed:

```kotlin
Spring(stiffness = 400f, dampingRatio = 0.85f)
Easing(0.2f, 0f, 0f, 1f)
```

Forbidden, in `aurora.sdk.design`, without exception:

```kotlin
fun solve(...)        // no
fun interpolate(...)  // no
fun animate(...)      // no
fun valueAt(...)      // no
```

The reason is separation of change: a designer retunes a spring without touching the solver,
and the solver is optimised without risking a visual change. It is also why the tokens can be
read by Compose, the View system and XML alike — none of which would accept the same executable
object. Compose, Flutter and SwiftUI all draw this line in the same place.

The practical test: if a file under `aurora.sdk.design` grows a function that computes
anything beyond trivial arithmetic on its own constants, it has crossed the line. `nested()`
on `RadiusTokens` is at the boundary and is deliberately kept there — it applies a rule about
the tokens, it does not run an animation.

**RULE-005 — Runtime components communicate through events.** A direct reference from one
service to another is forbidden unless the exception is written down and justified.

Wrong:

```
VolumeService ──> NotificationService
```

Right:

```
VolumeService ──> VolumeChanged ──> AuroraEventBus ──> NotificationService
```

Direct references make a graph that only grows: every new feature adds edges, the edges become
cycles, and eventually nothing can be built, tested or replaced on its own. Through the bus,
a publisher does not know who listens, so a subscriber can be added, faked or deleted without
the publisher changing.

**RULE-006 — `AuroraClock` is monotonic, in every implementation.** Readings never decrease:
`t0 <= t1 <= t2`. Allowed: `advance()`, `pause()`, `resume()`. Forbidden everywhere, including
`TestClock`: `rewind()`, `setTime()`.

A test seam must not be able to produce a state production cannot. If `TestClock` could rewind,
code could be written and proven correct against behaviour the real clock will never exhibit —
and the use it appears to serve, seeking, is already covered: `Timeline` is stateless, so
seeking is querying a different argument. A caller wanting a different origin constructs
another clock. Enforced by a reflection test asserting no such method exists.

**RULE-007 — Time and threading come from a seam.** In `aurora.sdk` and `aurora.runtime`,
these are forbidden: `System.currentTimeMillis`, `System.nanoTime`, `Thread.sleep`, `Handler`,
`Looper`, `Choreographer`. `aurora.platform` may use them; that is its job.

Exactly one exemption exists: `RealtimeClock` is the single sanctioned reader of the system
clock. Every rule of this shape needs one hole or the abstraction it protects cannot be built,
and the hole is declared in `runtime.contract` as a `call-exemption` rather than remembered,
because an exemption that lives in someone's head gets copied within a few sprints.

Enforced by `arch-test.sh`, which scans call text rather than imports — nothing has to be
imported to write `System.nanoTime()` — and which ignores comment lines, so documentation can
name what it forbids.

**RULE-008 — The animation engine consumes time; it never acquires it.** An animation is handed
a `FrameTime`. It must not call a clock.

```kotlin
animation.update(System.nanoTime())  // no
animation.update(frameTime)          // yes
```

Three practical consequences. *Determinism*: the same sequence of frames always produces the
same output, so a failure replays exactly instead of being chased. *Coherence*: every animation
in a frame gets the same timestamp, so long parallel transitions cannot drift apart by the
microseconds between separate clock reads. *Testing*: frames are handed out at any spacing,
including pathological ones, with no device and no waiting.

**RULE-009 — Animation MUST be deterministic.** The same sequence of `FrameTime` values always
produces the same result, independent of wall clock, thread and frame rate. Host tests and a
device must agree frame for frame.

All mutable animation state lives in a `MotionSampler`. An `Interpolator` is a pure
function, and a design token is data; state hiding in either would make `seek()` and
`restart()` silently stop being repeatable, because `transform(0.5f)` twice would return two
different numbers.

Enforced by `arch-test.sh` through `forbid-call-under`, which bans `Math.random`,
`java.util.Random`, `kotlin.random.Random` and a bare `Random(` beneath
`runtime/java/aurora/runtime/animation`, and by `AnimationDeterminismTest`, whose float
comparisons all use a tolerance of exactly zero.

Four spellings and not one, because the check matches literal text: `kotlin.random.Random`
shares no substring with `java.util.Random`, so banning only the Java one would leave the
idiomatic Kotlin spelling untouched. What that still cannot catch is randomness reached through
an alias or a helper defined elsewhere — the check is a grep, not a call graph, and saying so is
better than implying a guarantee it does not give.

**RULE-010 — SDK defines the language, Runtime speaks it, Platform connects it to Android.**

```
aurora.sdk.animation        interface Animator          the language
aurora.runtime.animation    class DefaultAnimator       speaking it
aurora.platform.animation   class AndroidAnimatorBridge connecting it   (Sprint 08)
```

Three layers, never mixed. The practical test for a new file: if it *executes* anything beyond
arithmetic on its own fields, it does not belong in `aurora.sdk`.

**RULE-011 — One `FrameTime` per frame, shared by reference.** Exactly one is built and handed
to every animation, never cloned and never mutated. Twenty animations in one transition cannot
drift apart, because only one timestamp for the frame exists. No animation may post its own
frame callback.

**RULE-012 — Execution identity is not handle identity.** The handle is stable; executions are
ephemeral. `COMPLETED` and `CANCELLED` end an execution, `DISPOSED` ends the handle, and every
callback carries the `executionId` it belongs to so a listener from run 3 can tell it is being
handed an event from run 4.

**RULE-013 — An execution advances at most once per frame.** Anything started, restarted or
disposed from inside a listener takes effect at the end of the frame. Otherwise where an
animation started would depend on listener order, which no caller controls.

A handle therefore never has more than one execution in `RUNNING` at the same time — not for a
frame, not for an instant.

**RULE-014 — An animation callback must never mutate `FrameTime`.** Every animation in a frame
reads the same instance, so one callback dirtying it would corrupt the whole frame. `FrameTime`
is a data class of `val`s; `AnimationApiTest` asserts by reflection that every field is `final`,
so the rule fails the day someone adds a `var`.

### Time, in three tiers

| Layer | Holds | Examples |
|---|---|---|
| `aurora.sdk.time` | concepts and contracts | `Duration`, `Timeline`, `FrameTime`, `TimeSource`, `AuroraClock`, `FrameScheduler` |
| `aurora.runtime.time` | portable implementations | `RealtimeClock`, `TestClock`, `QueuedFrameScheduler`, `ImmediateFrameScheduler`, `TimelineDriver` |
| `aurora.platform.time` | the Android bridge | `ChoreographerFrameScheduler` — Sprint 08 |

### Animation, in the same three tiers

| Layer | Holds | Examples |
|---|---|---|
| `aurora.sdk.animation` | concepts and contracts | `Animation`, `AnimationSpec`, `AnimationState`, `AnimationHandle`, `MotionSample`, `MotionSampler`, `Animator`, `AnimationController`, `Interpolator` |
| `aurora.runtime.animation` | the engine | `AnimationStateMachine`, `ExecutionTimeline`, `TimedSampler`, `AnimationRegistry`, `AnimationHandleImpl`, `DefaultAnimator`, `DefaultAnimationController`, `AnimationDriver` |
| `aurora.platform.animation` | the Android bridge | `ChoreographerAnimationDriver` — Sprint 08 |

Sprint 06A builds the lifecycle and leaves the motion. There is no solver: `TimedSampler` is
the only `MotionSampler`, and it delegates to `Timeline`. Sprint 06B.0 settles what a solver *is*
and writes no solver at all; 06B.1, 06B.2 and 06B.3 then add spring, decay and snap, each one an
implementation of a contract that already exists. `BezierInterpolator` is an `Interpolator` rather
than a sampler, because a Bezier shapes progress that time has already produced while a spring
produces progress from energy. They plug into different seams and ADR-002 keeps them apart
deliberately.

One existing class does change: `AnimationHandleImpl.samplerFor` currently throws for every
`PhysicsSpec`, and 06B.1 onward replace those throws with branches. That is a `when` in one private
function, and it is the *only* engine change physics requires — the state machine, the registry,
the driver, the handle's lifecycle and every public interface stay as they are.

Timing bugs and physics bugs look identical from the outside — something moved wrong — so the
half that can be proven exactly is built first. When a pixel is wrong in 06B, it is the
solver.

The same split Kotlin coroutines make between `kotlinx.coroutines`, its dispatchers, and the
Android dispatcher. Time is a *domain model*, not an implementation detail: `Duration` and
`Timeline` know nothing of threads, loopers or Android, so they belong at the bottom where
every layer — including service contracts in `aurora.sdk` — can name them.

### Event naming

The name should say which of the two things it is, because the API cannot.

| Kind | Tense | Published with | Examples |
|---|---|---|---|
| Something happened | past | `publish` | `NotificationPosted`, `VolumeChanged`, `PowerButtonPressed` |
| Current state | `Current…` | `publishSticky` | `CurrentTheme`, `CurrentVolume`, `CurrentOrientation` |

Keeping these apart is what stops the event bus drifting into a state store. A notification
being posted is an event; the theme in force is state. If every publish were sticky, a
subscriber joining later would be handed a pile of things that already finished happening.

---

## Architecture enforcement

The layering rules are not a convention; they are checked.

```bash
frameworks/base/aurora/tools/arch-test.sh
```

Enforcement has two independent parts, and the order matters. The compiler is the real
barrier: `sdk_version: "core_current"` keeps `android.*`, `com.android.server.*` and
`com.android.internal.*` off the classpath, so a forbidden import cannot compile. That
guarantee can be removed by a single edit to `Android.bp`, which is what `arch-test.sh`
exists to catch. It re-checks the rules declared in `contracts/`, verifies `Android.bp`
still says what it should, and compiles the fixtures under `tests/arch/` that must fail.

Change a rule by editing the relevant `.contract` file. Nothing else reads them.

On a workstation without `javac` on the PATH the negative compiles report `skip`; they run
on the build machine, where the jars they compile against exist.

---

## Design tokens

`aurora.sdk.design` holds the design language: spacing, radius, elevation, motion,
typography and colour. Read everything through the `DesignTokens` facade.

```kotlin
val pad = DesignTokens.spacing.COMPONENT_PADDING
val ms  = DesignTokens.motion.DURATION_ENTER
val bg  = DesignTokens.colors(dark = true).background
```

Values are plain `Int`, `Long` and `Float` — never `Dp` or `Color`. That is forced by the
layer rules, since `aurora.sdk` has neither Android nor Compose on its classpath, and it is
also the better design: tokens are data, so one set feeds Compose, the View system and XML
resources alike. Converting them to platform types belongs in an adapter in
`aurora.platform`.

| Kind | Unit | Type |
|---|---|---|
| Spacing, radius, elevation | dp | `Int` |
| Type size, line height | sp | `Int` |
| Duration | ms | `Int` |
| Colour | packed ARGB `0xAARRGGBB` | `Long` |
| Letter spacing | em | `Float` |

Each token file separates a raw scale from semantic aliases. Prefer the aliases:
`COMPONENT_PADDING` survives a decision to change component padding, a literal `LG` does not.
Colour has the same split — `ColorTokens.Palette` holds raw hues, `LIGHT` and `DARK` map them
to roles, and interface code must only ever read roles.

Nothing stops a caller from writing `16` instead of a token; that part is convention, not
something the compiler can catch. What the compiler does guarantee is that no token can reach
into Android or Compose.

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

**Sprint 02 — Architecture boundary.** Declare the layer rules in `contracts/`, enforce them
with `tools/arch-test.sh`, and prove the compiler still rejects forbidden imports with
negative fixtures. Done before the system wiring on purpose: once `aurora.platform` gains
access to `framework` in Sprint 03, the classpath stops protecting the lower layers by
accident, so the tripwire has to exist first.

**Sprint 03 — Wire into the system.** Let `aurora-platform` depend on `framework`, narrow
`AuroraContext.hostContext()` from `Object` to `android.content.Context`, initialize
`AuroraRuntime` inside `SystemServer`, and publish `AuroraServiceRegistry` as a system
service. This is the first step that changes runtime behaviour, so Boot PASS must be
verified for real rather than holding by construction as it does today.

**Sprint 04 — First service.** Define a service interface in `aurora.sdk`, implement it in
`aurora.platform`, and register it. This is where the three-layer boundary gets its first
real test.

**Sprint 05 — Configuration.** Read system properties and overlays so the runtime can toggle
features without a rebuild.

**Sprint 06B.0 — Physics semantics.** Not a framework: after 06A.5 there is nothing left to
build one out of. `MotionSampler` has a single method, `isFinished` lives on the spec, and a
sampler is created per execution, so a shared physics base class would be an abstraction with no
variation to justify it — the same objection that rejected a `MotionSample` interface and a
`CompletionPolicy`.

What it does instead is settle the questions every solver has to answer the same way, before
three of them answer differently. The design is in
`docs/specs/2026-08-03-sprint-06b0-physics-semantics-design.md`; three decisions came out of it.

**The contract states its own domain.** It applies to every solver whose entire dynamical state
is `(value, velocity)`. Every convergent system has a monotone Lyapunov function, so existence
constrains nothing; the binding requirement is that one be *readable from a `MotionSample`*, which
holds exactly for second-order autonomous systems. That is the real reason spring, decay and snap
are one family. A PID controller carries an integral term, so two frames with the same value and
velocity sit at different distances from rest and no function of `MotionSample` separates them —
it does not break the contract, it falls outside it, and the extension it would need is a wider
`MotionSample` rather than another threshold.

**Completion becomes a metric, a threshold and a comparison.** The current rule cannot stand: an
underdamped spring has zero velocity at every turning point, so once its envelope drops under
`restDelta` the rule reports finished there and not-finished a moment later. The engine survives
by never asking twice, but the instant a spring settles then depends on which frame lands near a
turning point, and the same spring finishes at different times at 60Hz and 120Hz. So `PhysicsSpec`
loses `restDelta` and `restVelocity` and gains `completionMetric` — a scalar in progress units
that must never increase while the sampler evolves — plus one `completionThreshold`.
`completionMetric` owns the physics, `completionThreshold` owns the UX, and `isFinished` becomes
a comparison no spec overrides. This is the sprint's one API change, and it is free only until
06B.1 exists.

**A decay does have a target; it is derived.** Under exponential friction the total travel is
`v0/friction`, in closed form, and normalising against it gives `1 - e^(-ft)` — **`v0` cancels
entirely**. Initial velocity decides how far a decay goes, not how it goes. So `to` is
`from + v0/friction`, `MotionSample.value` keeps one meaning across every family, and `from == to`
turns out to mean exactly `v0 == 0` — a fling released at rest, which is a precondition rather
than a hazard. ADR-002 called this circular on an unstated assumption: that finding the resting
position means simulating to it. For exponential friction it is one division.

That last decision leaves one question open rather than answered — whether `(from, to)` is
universal or is Spring's shape wearing a general name. It blocks no deliverable here, so it is
recorded as open analysis in the spec rather than resolved in a hurry.

It produces a document the project does not yet have a place for. `docs/adr/` records why a
decision was made and what was rejected; `docs/specs/` describes one sprint; `contracts/*.contract`
holds layer rules that `arch-test.sh` parses. None of those is "what every implementation of this
kind must satisfy, across sprints". That goes in `docs/contracts/motion-sampler-contract.md` — a
new directory, prose and normative, named so it is not mistaken for the machine-read `.contract`
files and not dropped among them.

Every property in it maps to a named assertion in the harness, and any property that cannot be
asserted says so. A contract nobody checks is prose, and this project has now been bitten twice
by an unverified claim: `zero-diff-gate.sh` asserted no guarded line mixed a string with a
trailing comment, and `PhysicsSpec` asserted its normalisation held for a decay. Both were true;
neither had been checked.

The harness has two tiers, because a red test has to say what is broken. A contract property
(`assertFinite`, `assertVelocityMatchesDerivative`) runs against every sampler; when it fails on
all of them the semantics are wrong, not the solver. A solver property (a spring oscillates
around its target) runs against one; nothing else is expected to satisfy it. Mixing the two
turns the harness into a demand that every solver behave like a spring.

Two questions gate a property before it is written: is it a property of the contract or of one
solver, and if every solver failed it, what would that mean? "Don't know" disqualifies it.

Filing a property in the wrong tier is not a symmetric mistake. A solver property filed as
contract fails loudly the moment the second solver cannot satisfy it. A contract property filed
as solver-only fails silently and permanently - Decay and Snap simply never get checked and the
harness stays green. So when the tier is unclear the property goes in the contract tier, and the
harness reports which samplers each contract property actually ran against, so an unchecked
solver cannot be invisible.

06B.0 builds this with zero solvers in existence, so no property can be assigned a tier by
experiment - only by argument. That is why each one is stated in prose before it is asserted,
and why **demoting a property from contract to solver-only is a normal move, not a defect in
06B.0**. When 06B.2 shows a decay cannot satisfy something a spring can, the contract is what
was wrong. Sprint 06A got this backwards three times: a test asserted a promise the design had
never made, and the first instinct each time was to change the code.

The refusal in `samplerFor` stays. Removing it would mean returning something for a `SpringSpec`,
and a placeholder sampler in production would animate silently and wrongly where the exception
fails loudly and names the sprint that fixes it (RULE-003). It goes when a real sampler arrives.

**Sprint 06B.1 — Spring.** The first solver: closed form for the underdamped, critically damped
and overdamped cases, no integration. Because 06B.0 goes first, its opening question is not *what
is a correct spring* — which is open-ended, and where 06A went wrong three times — but *make this
spring satisfy the contract*, which has a stopping condition. It removes the refusal, and it is
the first implementation the harness has ever had to judge.

**Sprint 06B.2 — Decay.** 06B.0's derived target meets its first real consumer, and the settle
time gets measured rather than guessed — the current default works out to roughly fourteen
seconds for a fling to stop.

**Sprint 06B.3 — Snap.** Chooses a target from a list, then springs to it. If it shrinks to a
`TargetSelectionPolicy` and no solver at all, that is evidence the abstraction was found rather
than imposed — which is why its shape waits until 06B.1 shows how general the spring turned out
to be.

`BezierInterpolator` is an `Interpolator` rather than a sampler and depends on none of this; it
can land beside any of these sprints.

**Sprint 08 — Android platform bridge.** `ChoreographerFrameScheduler` and
`ChoreographerAnimationDriver` in `aurora.platform`. `AnimationController.tick(FrameTime)` is
already the entry point, so this is an adapter rather than a rework.

**Later — Gesture work.** iOS-style gesture customisation belongs in `aurora.platform`, acting
on `SystemUI` and Launcher3 QuickStep. Because gesture code lives in the framework layer and
touches no device hardware, whatever is developed on the emulator will behave identically on
real hardware.

### What to watch out for

The strongest temptation when extending this is to let `aurora.runtime` "just
import android.* this once". Doing so costs host-side unit testing for both
lower layers, and once lost it is hard to recover. If the runtime needs
something from Android, define an interface in `runtime` and implement it in
`platform`.
