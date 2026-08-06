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

- [ ] Question 0 answered by building it, not by quoting the README
- [ ] the timebase question answered by measurement, and the `FrameScheduler` KDoc corrected if it
      turns out to have been wrong
- [ ] `DefaultAnimationController.stop()`'s deferred paragraph replaced by a decision
- [ ] the frame thread named, and `AnimationHandle`'s threading note updated to say which one
- [ ] an animation advances on a device, with frame timestamps from `Choreographer`
- [ ] nothing drawn

The last one is the constraint that keeps this sprint from becoming Sprint 09.
