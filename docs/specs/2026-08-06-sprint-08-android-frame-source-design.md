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
