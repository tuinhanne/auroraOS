# Sprint 08 — The Android frame source

**Status:** design in review · 2026-08-06 · pulled in by a decision, not by a roadmap

## Why it is open now

Sprint 09 Task 1 chose the volume overlay, and `docs/features/volume-overlay.md` §4 requires motion:
a run of presses must leave the overlay visibly one object. Motion on a device needs frames, and
Aurora has never received one — the only two `FrameScheduler` implementations in the tree are
`QueuedFrameScheduler` and `ImmediateFrameScheduler`, both test infrastructure.

So this sprint is a **known prerequisite** rather than a discovered one. That distinction is what
Sprint 09 §6 was written to produce, and it is the reason this spec exists before any overlay code.

---

## Question 0 — is it really an adapter?

> **The README has said this since Sprint 02, and nobody has checked.**

> *"`AnimationController.tick(FrameTime)` is already the entry point, so this is an adapter rather
> than a rework."*

Sprint 03 tested four claims that entry made about itself and **three were wrong**: `SystemServer`
was not modified, `aurora-platform` did not depend on `framework`, and `hostContext()` was not
narrowed. One survived. That is a poor enough record that this claim gets checked rather than
inherited.

**Two things already support it**, and they are worth stating so the check has somewhere to start:

```
AnimationDriver.kt:88   frameIndex = nextFrameIndex     the driver numbers frames itself
AnimationDriver.kt:105  pending = scheduler.postFrame(this)
```

A `FrameScheduler` therefore supplies **one number** — `frameTimeNanos` — and nothing else. There is
no frame index to invent, no `FrameTime` to construct, and no state to keep. If the claim is true
anywhere, it is true here.

**What could still refute it:** a timebase mismatch, a thread the engine cannot be driven from, or a
lifecycle that does not fit `start()`/`stop()`. Each is a question below.

---

## Question 1 — what timebase does `Choreographer` hand out?

`FrameScheduler`'s own documentation makes a claim that has never been tested against a real
implementation:

> *"@param frameTimeNanos the time the frame is being composed for, **on the same timebase as
> `AuroraClock.nowNanos`**"*

`RealtimeClock.nowNanos()` is `System.nanoTime()`. Whether `Choreographer`'s `frameTimeNanos` is on
the same clock, and whether either stops during suspend, decides whether elapsed time inside an
animation means what the engine assumes it means.

**This is not a detail.** `ExecutionTimeline` measures elapsed from frame timestamps, every sampler
is a function of elapsed, and RULE-011 hands one `FrameTime` to every animation in a frame. A
timebase that disagrees with the clock produces motion that is subtly wrong in a way no test in this
repository would catch, because every test supplies both numbers from the same source.

---

## Question 2 — the gap that has been waiting for this sprint by name

`DefaultAnimationController.stop()` has carried this since Sprint 06A:

> *"It says nothing about the first frame **after** `start()`. Frame timestamps come from a source
> outside this class, and if that source kept running while the engine was stopped, the first frame
> back carries a timestamp far beyond the last one seen. Every still-running handle would then be
> handed a single enormous elapsed step and could finish in one tick instead of resuming.*
>
> *Nothing in Sprint 06A can settle this, because what a stopped frame source does to its own
> timestamps is **Sprint 08's decision**, and there is no real one yet."*

**It is now Sprint 08, and there is one.** The paragraph names two outcomes:

| if `Choreographer` pauses with the display | the question is moot, and `stop()` needs nothing |
| if it does not | `stop()` must pause every live execution |

This sprint is obliged to determine which, and to say so in `DefaultAnimationController`'s own
words rather than leaving the paragraph as a warning. It is the third of the three gaps
`device-runnable-inventory.md` lists as open, and the only one this sprint can close.

---

## Question 3 — which thread, and who starts it?

Aurora runs inside `system_server`. `Choreographer` is per-`Looper`, so a frame source implies a
looper, and `AnimationHandle` documents that every mutating call must happen on the thread that
drives `tick`:

> *"Not thread safe. Every mutating call must happen on the same thread that drives
> `AnimationController.tick`, which on device is the frame thread."*

That sentence was written when no frame thread existed. Choosing one now decides where every future
Aurora animation call has to come from, and it is easier to choose than to change.

Related and unanswered: `AnimationDriver.start()` is called by nobody today. Whether the driver runs
for the process's life, or follows display state, is the other half of Question 2.

---

## Task 1's result, recorded 2026-08-06

One boot, one instrument, three questions. Two answered, one refuted its own applicability — and
the refutation came from a number the instrument was not built to produce.

### Question 3 — answered: the main thread, and it has a Looper

```
08-06 21:27:08.776   766   766 I Aurora : measure thread=main looper=true isMain=true
08-06 21:27:08.779   766   766 I Aurora : measure: posted the first frame callback
```

`onStart` runs on `system_server`'s **main thread**, which is the main `Looper`, and
`Choreographer.getInstance()` succeeded there. So the frame thread is not something Aurora has to
create or choose — it is the thread it is already started on.

That answers the sentence in `AnimationHandle` that was written before a frame thread existed:
*"the same thread that drives `AnimationController.tick`, which on device is the frame thread"*. It
is `system_server`'s main thread, and callers must be on it.

### Question 1 — answered while awake, and the interesting half is unreachable

`frameTimeNanos` tracks the same timebase as `System.nanoTime()`, behind it by the compose lag the
platform documents:

```
n=0      frameTimeNanos=119082629916  nanoTime=119093689300   lag 11.06 ms
n=3420   frameTimeNanos=182549294044  nanoTime=182550974400   lag  1.68 ms
```

Always behind, never ahead, by a varying amount — which is what *"the time the frame started being
composed"* should look like. `FrameScheduler`'s KDoc claim survives.

**But the measurement cannot distinguish `nanoTime` from `elapsedRealtimeNanos`**, because on this
device they never disagreed. That distinction only appears across a suspend, which brings us to:

### Question 2 — NOT answered, and the instrument says why

Frames did not stop. The display slept — `dumpsys power` reported `mWakefulness=Asleep` — and the
callback kept arriving at roughly 57/second for the whole 41-second window.

**Pre-registered before the data arrived: this is the ambiguous direction.** "Frames kept coming"
does not distinguish *`Choreographer` ignores display state* from *the emulator never really stopped*.

**And the instrument settled it against itself**, using two clocks it was logging for the other
question:

```
elapsedRealtimeNanos − uptimeNanos      (grows by exactly the time the CPU spent suspended)

  before screen off          10,100 ns
  after 16 s of "sleep"      23,200 ns
  growth across the window   13,100 ns  =  0.013 ms
```

**A real 41-second suspend would have shown about 41,000,000,000 ns.** It showed thirteen
microseconds, which is the jitter between two consecutive syscalls. The CPU never slept. The
emulator turned off a display and carried on.

So the observation is *frames continue while the display is off*, and the question was *what happens
when the frame source stops*. Those are different questions, and this device cannot be made to ask
the second one.

### The two questions turn out to be one

Worth stating because it changes what would settle them. Whether `frameTimeNanos` follows
`CLOCK_MONOTONIC` or `CLOCK_BOOTTIME` only matters across a suspend — and a suspend is exactly the
scenario `DefaultAnimationController.stop()` is worried about. **Question 1's unreachable half and
Question 2 are the same experiment**, and both need hardware that actually sleeps.

`lineage_beyond2lte` would answer both. Nothing available today will.

### What this does to the sprint

| | |
|---|---|
| **Question 0 — adapter?** | supported so far. A `FrameScheduler` needs one number, the thread already has a `Looper`, and `Choreographer` hands over `frameTimeNanos` on the clock the engine expects. Task 2 proceeds |
| **Question 3** | closed. `AnimationHandle`'s threading note gets a name |
| **Question 1** | closed for the awake case, which is the case that matters for a volume overlay |
| **Question 2** | **stays open**, and `stop()`'s paragraph is not replaced by a decision. It is replaced by a sharper statement of what is unknown and what would settle it |

That last row is the one worth not fudging. Task 3 was written expecting to close a gap that has
waited since 06A; it will instead record why the gap survived a sprint that was named as its owner.
A warning that has been made more precise is a better outcome than a decision made on a device that
cannot produce the evidence for it.

---

## Task 4's result, recorded 2026-08-06 — a frame arrived, and the cadence is a finding

```
22:51:39.709  task4 scheduler built, frameIntervalNanos=16666665
22:51:39.711  task4 driver started; registry=0
22:51:39.719  task4 played; registry=1
22:51:43.490  task4 state SCHEDULED -> RUNNING after 0 ticks
22:51:43.490  task4 tick=0 elapsedMs=0   value=0.0        velocity=-0.0
22:51:43.867  task4 tick=1 elapsedMs=383 value=100.2787   velocity=-4.387105
22:51:44.447  task4 tick=2 elapsedMs=966 value=100.000015 velocity=-1.9077424E-4
22:51:44.447  task4 state RUNNING -> COMPLETED after 3 ticks
```

### What worked

**`frameIntervalNanos=16666665`** — 60.000006 Hz, read from the default display through
`DisplayManager`, not guessed. The lookup works from `system_server`'s non-display context.

**Frames reached the engine.** `Choreographer` → `ChoreographerFrameScheduler` → `AnimationDriver`
→ `AnimationController.tick` → sampler → listener, all of it on a device. Question 0's *adapter*
claim is now verified end to end rather than argued.

**The solver ran, and the numbers prove it rather than merely permitting it:**

```
tick=1   value=100.2787      overshoot above the target
tick=2   value=100.000015    settled back
```

**A linear interpolation cannot exceed its target.** The overshoot is a spring's signature, and it
is the one shape that cannot be produced by a driver that is delivering frames while the solver
does nothing.

### What did not, and it is the more useful half

**Three ticks in 966 ms.** At 60 Hz that window holds about fifty-eight. And the first frame arrived
**3.771 seconds** after `play()`.

| | |
|---|---|
| `played` → first frame | 3.771 s |
| tick 0 → 1 | 0.377 s |
| tick 1 → 2 | 0.580 s |

**Task 1's log shows the same opening gap**, from a completely different build: its callback was
posted at 21:27:08.779 and its first frame logged at 21:27:12.396 — 3.617 s. And then Task 1
recovered to roughly 57 frames per second and stayed there.

So: **the frame source starves during early boot and recovers afterwards.** Two independent
measurements, the same shape.

*Why* is not established. `system_server` is starting everything it owns, SurfaceFlinger may not be
fully up, and the emulator renders through swiftshader — any of those would do it, and this
measurement cannot separate them.

### The engine did not care, and that is not luck

Three samples across a second, spaced unevenly, and the animation still finished at 100.000015
rather than somewhere arbitrary. That is `ExecutionTimeline` measuring elapsed from **timestamps**
rather than counting frames, and `FrameScheduler`'s KDoc insisting on it:

> *"Always measure from the [FrameCallback] timestamps instead of accumulating this value, or the
> animation will drift."*

An engine that had accumulated `frameIntervalNanos` would have believed 3 × 16.67 ms had passed —
50 ms — and would be 5% into a spring that had actually finished. The advice was written before any
frame source existed, and the first real one delivered exactly the conditions it was written for.

### What this means for Sprint 09

**Do not animate at boot.** Anything Aurora shows during `onStart` will be sampled a handful of
times and look like a slideshow, no matter how correct the solver is.

This does not threaten the volume overlay: its trigger is a hardware key, which happens long after
boot, in the window Task 1 measured at ~57 fps. But it is worth recording before somebody adds a
splash animation and blames the engine.

---

## What this sprint will not do

- **Nothing is drawn.** A frame source makes frames arrive; it puts no pixel anywhere. Sprint 09 is
  what consumes them.
- **No new motion, no solver change, no API widened** beyond what the compiler demands of the
  adapter.
- **No `SystemUI` work.** Where Aurora draws is Sprint 09 Question 1 and is untouched here.

---

## Task order

1. **Task 1 — measure the timebase and the pause behaviour.** Questions 1 and 2, on a device,
   before anything is written. Both are observations rather than designs.
2. **Task 2 — the adapter**, in `aurora.platform.android`. `ChoreographerFrameScheduler`, and
   `ChoreographerAnimationDriver` only if Task 1 shows the existing `AnimationDriver` cannot be
   driven as-is — which would also be Question 0 answering *not an adapter*.
3. **Task 3 — close the gap.** Whatever Task 1 found about a stopped frame source, written into
   `DefaultAnimationController` in place of the warning.
4. **Task 4 — a frame arrives.** Something advances an animation on a device, observed in a log.

---

## Exit criteria

- [x] Question 0 answered by building it, not by quoting the README — **it is an adapter**, and the
      `ChoreographerAnimationDriver` the README also named turned out to have nothing to do
- [x] the timebase question answered by measurement, and the `FrameScheduler` KDoc corrected if it
      turns out to have been wrong — **it was right**, so nothing was corrected. A second KDoc was
      *vindicated* instead: *"always measure from the timestamps"* is why three uneven samples in a
      second still landed the spring on its target
- [ ] `DefaultAnimationController.stop()`'s deferred paragraph replaced by a decision — **not met as
      written, and deliberately.** The emulator cannot suspend, so there is no decision to make on
      evidence. The paragraph was replaced by a sharper unknown: the mechanism, the failure site
      (`ExecutionTimeline.advanceTo`), the repair that already exists (`pause`/`resume` shifting
      `originNanos`), and what would settle it (hardware that sleeps)
- [x] the frame thread named, and `AnimationHandle`'s threading note updated to say which one —
      `system_server`'s main thread, and now enforced rather than described
- [x] an animation advances on a device, with frame timestamps from `Choreographer` — and overshot
      its target, which is the shape a non-running solver cannot fake
- [x] nothing drawn

The last one is the constraint that keeps this sprint from becoming Sprint 09, and it held.

**Five of six, and the sixth is the interesting one.** A criterion written expecting a decision
was met by a measurement showing no decision was available. Leaving it unticked is the honest
record: the gap that has waited since 06A is still open, and now waits on hardware rather than on
a sprint.

**One result nobody asked for**, and it is the sprint's most useful: the frame source starves for
~3.7 s during early boot and recovers to ~57 fps, observed twice from independent builds. Sprint 09
inherits it as *do not animate at boot* — and, more usefully, as evidence for later that boot
scheduling and runtime scheduling are different environments.
