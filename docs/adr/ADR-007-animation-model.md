# ADR-007 — Animation describes behaviour, Execution describes time, Sampler maps time to motion

**Status:** accepted · 2026-08-03 · Sprint 06A.5

## Context

Sprint 06A built the engine around one word doing three jobs. `progress` was the timeline's
position, the shaped output, and the thing a caller read to draw with. That held while the only
animation was time-driven. It stopped holding the moment a spring was considered, because a
spring reports 1.18, then 0.95, then 1.03 — a position that oscillates, not a progress that
advances.

The question is not what to rename it to. It is which of the three jobs belongs where.

There is also a standing temptation worth naming, because it will recur every time a designer
asks for something Aurora cannot yet express: model animation the way a motion editor does, with
tracks, clips, layers and keyframes over a global timeline. After Effects, Rive and Lottie all
work that way, and Dynamic Island, Notification, Volume HUD, Lock Screen and AOD are exactly the
kind of surfaces that invite it.

## Decision

Three concepts, each owning one thing, none knowing the other two:

> **Animation describes behaviour. Execution describes time. Sampler maps time to motion.**

| | Owns | Mutable | Knows about |
|---|---|---|---|
| `Animation` + `AnimationSpec` | what moves, from where to where, and what kind of motion the designer asked for | no | nothing |
| `ExecutionTimeline` | elapsed time, and what pause, resume and seek do to it | yes | no animation, no sampler |
| `MotionSampler` | how an elapsed time becomes a value and a velocity | its own business | no execution |

`AnimationHandle` is where the three meet, and it is the only place they do.

**Aurora is a runtime animation engine, not a motion editor.** It answers *what is this animation
doing at this instant*. It does not own a global timeline, does not compose tracks, does not
store keyframes, and has no authoring model.

## Alternatives considered

**The editor model — Animation → Track → Clip → Layer → Sampler.** Rejected, and worth writing
down because it is the shape anyone arriving from After Effects or Lottie will reach for.

It is the right model when motion is *authored* — designed once, stored, then played back, where
a global timeline is the document and scrubbing it is the primary interaction. Aurora's motion is
not authored. It is *reactive*: a volume key is pressed, a notification arrives, a finger lifts at
some velocity. There is no document, there is no global timeline, and the thing that most often
happens to an animation is that it gets interrupted by another one.

Adopting the editor model would mean carrying a timeline concept that nothing in a phone's
interface has, and expressing "a spring that absorbs the velocity the gesture ended with" as a
clip on a track — which it is not.

**Keep `progress` and document the exception.** Every reader of the API learns that progress
sometimes exceeds 1 and sometimes decreases. An exception that has to be remembered is one that
gets forgotten, and the code that forgets it looks correct.

**A single mutable `AnimationState` object owned by the handle**, with the sampler writing into
it. Fewer types, no per-execution allocation. But then the sampler knows about the execution, and
the clean testability of "give it an elapsed, get a sample" is gone — which is the property that
lets a solver be tested with no engine at all.

## Consequences

- A sampler is created per execution and discarded with it. It never needs resetting, and its
  internal state — a fixed-step integrator's position, velocity and step count — never reaches
  the SDK. One allocation per execution, which is a human-scale event.
- `ExecutionTimeline` can be tested with no animation. A sampler can be tested with no engine.
  `AnimationStateMachine` already needed neither.
- Elapsed time becomes the engine's canonical quantity. Seeking is by elapsed, which makes the
  question of inverting a non-injective progress curve disappear rather than be worked around.
- `normalizedPosition` survives on the handle as a convenience for callers that genuinely want a
  0..1 reading — a scrollbar, a scrubber — with `hasNormalizedPosition` saying when it means
  anything. Without it those callers would compute `elapsed / duration`, which is correct only
  for a `TimedSpec`, and the abstraction would leak.
- The editor model stays available if Aurora ever grows an authoring surface. It would sit
  *above* this engine, producing `Animation` values and driving them, not replace it.
- **A sampler reports where a motion is, never whether it is done.** Ending is a policy made of
  the spec's own numbers — a spring rests inside `restDelta` of its target and below
  `restVelocity` — so `AnimationSpec.isFinished(elapsed, sample)` owns it. Had the sampler
  carried it, every alternative spring solver in 06D would have had to re-implement the same
  rule identically, and a timed animation would have had to answer a question its value cannot
  see: a timeline ends because time ran out, not because the value arrived somewhere.
  It also keeps the engine free of a `when` over spec kinds, so a spec added later brings its
  own rule and `AnimationHandleImpl` never learns it exists.
- Cost: three concepts where a less careful design has one. The names have to be right, because
  the separation is only useful if a reader can tell which of the three they are holding.

## `MotionSample` is a value, and stays one

Immutable, never cached, never reused, never pooled. One is created per sample and forgotten.

The rule exists because of a specific temptation: anyone worried about allocating per animation
per frame will reach for an object pool, and pooling breaks exactly the property that makes a
sample useful. A pooled sample handed to a listener can be overwritten underneath it later in the
same frame, so the number a listener read is no longer the number it acts on — and that failure
is invisible in review and intermittent at runtime.

It also keeps determinism easy to reason about. If a sample can never change after it is made,
then "what was this animation doing at 96ms" has exactly one answer, and a replay can be compared
against a recorded one without asking whether either has been touched since.

The allocation this forbids optimising is small — around 58KB/s at 120Hz with twenty animations,
which ART absorbs without a collection worth measuring. If it ever does become measurable, the
answer is to sample less often, not to make a sample mutable.

## Two shapes considered and deliberately not built

Both were proposed in review, both are reasonable, and both were declined as generality for a
variation that does not exist. Recorded so the reasoning is available when it does.

**`MotionSample` as an interface, so 06D can add acceleration or energy.** Declined. The engine
reads `value` and `velocity` and nothing else, so a field on a subtype would be invisible to
everything that exists — and if some future consumer genuinely needed acceleration, it would need
a route to it through `AnimationHandle`, so the interface moves the breaking change rather than
removing it. Aurora is built as one tree, so the binary-compatibility constraint that makes a
published library's data classes hard to grow does not apply: adding a field with a default is
source-compatible here. If a second consumer of a richer sample ever appears, that is the moment
to widen it.

**`CompletionPolicy` extracted from `AnimationSpec`.** Declined. A spec added in 06D has to be
written anyway, and giving it one method is cheaper than writing a policy class and wiring it.
The case that would justify extraction is a *caller* wanting to override when a particular
animation counts as finished — a scroll that should stop early, say — and nothing needs that yet.
`SpringSpec` and `SnapSpec` sharing a rest rule is served by a shared private function, not by a
public interface. If overridable completion is ever wanted, `CompletionPolicy` is the shape it
takes, and `AnimationSpec.isFinished` becomes its default.
