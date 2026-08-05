# Volume Overlay — behaviour

**Status:** specified 2026-08-06 · no implementation exists · no sprint open

**This document names no API, no class and no platform.** It describes what a person sees and what
they can do. Every statement here is meant to be readable by someone who has never opened this
repository, and to stay true if the whole thing were rewritten underneath.

> **Before any animation policy is chosen, record the UX reason for the behaviour. Runtime
> consequences are observations, not design inputs.**
>
> Carried from Sprint 06C.0. There is an open question about whether the motion runtime should
> support one animation replacing another, and this feature is the first thing in a position to
> need it. That makes it the first thing in a position to be **bent** toward needing it. The test
> applied throughout: *does the reason still stand if that question did not exist?* Where the answer
> is no, the behaviour is wrong regardless of what it would unlock.

---

## 1. What it is

A transient surface that appears when the volume changes, shows what changed, and goes away by
itself. It is not a settings screen and it is not a persistent control.

It answers three questions, in this order of importance:

1. **Did my press register?**
2. **What am I changing?** — which stream, and whether it is muted
3. **How loud is it now?**

The first is why it exists at all. A volume key with no feedback is indistinguishable from a broken
volume key, and the user's next action is to press it again — harder, and more times than they
meant to.

---

## 2. States

```
        hidden
          │  a volume change arrives
          ▼
       appearing ──────────────┐
          │  fully shown       │  a change arrives mid-appear
          ▼                    │
        shown ◄────────────────┘
          │  no interaction for the dismiss delay
          ▼
        fading ────────────────┐
          │  fully gone        │  a change arrives mid-fade
          ▼                    │
        hidden ◄───────────────┘  (returns to shown, from where it is)
```

Four states, and the only way out of the last two is a change arriving or time passing.

---

## 3. Timing

| | |
|---|---|
| appears | on the change, not after it. There is no delay to "confirm intent"; the press *is* the intent |
| dismiss delay | measured from the **last** change, not the first |
| stays | long enough to read the level after the final press of a run |
| leaves | by fading rather than vanishing, so peripheral vision registers it as leaving rather than as having blinked |

**Exact durations are not fixed here.** They are the kind of number that has to be felt on a device,
and this document exists before there is a device to feel them on. What is fixed is that they are
*single* values, shared by every stream — a ring adjustment that lingers longer than a media
adjustment reads as a bug in the phone.

---

## 4. Repeated presses — the behaviour this section exists for

A person changing volume presses the key several times in a row. That is the normal case, not an
edge case, and it is where most volume overlays feel wrong.

**Requirement: throughout a run of presses, the overlay must remain visibly one object.**

Concretely, and each of these is observable without knowing anything about how it is built:

- **It does not replay its entrance.** An overlay that is already on screen and pops in again reads
  as a *second* overlay arriving, and the eye tracks it as a new thing rather than as the same thing
  updating.
- **The level indicator moves from where it is.** Not from where it was before the previous press,
  and not by jumping. If the indicator is still travelling toward 40% when a press asks for 50%, it
  continues from wherever it has reached.
- **A press during the fade brings it back without a discontinuity.** It must not blink: it does not
  finish disappearing and then reappear, and it does not snap instantly to fully visible. It returns
  from whatever visibility it currently has.
- **The dismiss delay restarts on every press**, so a run of ten presses shows one overlay that
  stays for one delay after the last one.

### Why, and the reason has nothing to do with animation machinery

**The overlay is the phone's answer to "I pressed the volume key."** A person pressing twice is not
asking two questions; they are pressing harder on one. An answer that restarts, blinks or jumps is
telling them something changed *besides* the volume — and the only thing that could have changed is
whether the phone was listening.

The failure is worst in exactly the case that matters most. A user who is unsure whether a press
registered presses again. If that press makes the overlay blink, they now have *more* doubt, not
less, and the interface has punished the person it was built to reassure.

**This reason stands with no reference to any framework.** Remove every animation system from the
world and it is still true that an object which restarts its entrance is read as a different object.

### What this does not say

It does not say the level indicator springs, eases, or moves at constant speed. It does not say the
fade reverses along its own curve or takes a new path back. It does not say whether returning from a
fade is one motion redirected or two motions arranged to look like one.

**Those are implementation questions and this document has no opinion on them.** The requirement is
that a person cannot see a seam. Any construction that achieves it satisfies this section, and any
that does not, does not — including constructions that would be convenient for something else.

---

## 5. Changing stream mid-run

The active stream can change while the overlay is up: a call arrives, or media starts, and the keys
now drive something else.

- The overlay **stays**; it does not dismiss and reappear.
- What it shows changes: the stream's identity, its level, its mute state.
- The change is **legible** — the user must be able to tell that the *subject* changed, not just the
  number. Adjusting the ringer when you meant to adjust media is a mistake people make constantly,
  and an overlay that changes only a number lets them make it silently.
- The dismiss delay restarts, because the user now has something new to read.

---

## 6. Mute

- Muting is a state, not a level of zero. The overlay must distinguish *silent because it is at the
  bottom* from *silent because it is muted*, because the recovery is different: one is a press away,
  the other is not.
- Muting **preserves** the level. Unmuting returns to the same loudness, and the overlay shows the
  preserved level while muted rather than showing zero.

---

## 7. The ends of the range

- At maximum, further presses do nothing to the level. The overlay still responds — it stays up and
  the delay restarts — because a press that produces no feedback at all is indistinguishable from a
  failure.
- At minimum, the same. Whether the bottom of the range is silence or the quietest audible step is a
  property of the stream, not of this overlay.

---

## 8. Accessibility

Stated as behaviour, since assistive technology is not an implementation detail of it.

- Every change is **announced**, including changes that came from the hardware keys rather than from
  touch. A user who cannot see the overlay gets the same three answers §1 lists.
- The dismiss delay is **not** the announcement's deadline. An overlay that disappears mid-sentence
  has answered nobody.
- The overlay never steals focus. It is feedback, not a destination.

---

## 9. What this document does not decide

Recorded so their absence is deliberate rather than forgotten:

- **Where on screen it sits, and how it looks.** Position, size, orientation behaviour and visual
  design.
- **Whether it can be touched.** Dragging the level directly, or expanding to reach other streams,
  is a second feature and a different set of questions.
- **Lock screen, always-on display and dozing.** Each is its own environment with its own rules.
- **Multiple displays.**
- **Exact durations and curves.** See §3.

Every one of these needs a device to answer honestly, and there is not one yet — Aurora cannot
receive a frame or draw anything today. See `docs/roadmaps/device-runnable-inventory.md`.

---

## 10. Observation, recorded after the fact

§4 requires that a press during a fade produces no visible discontinuity, and that a level indicator
in motion continues from where it has reached.

**Recorded here rather than in §4, and deliberately below it:** if those requirements survive
implementation, they are the first production behaviour that may need one motion to hand over to
another — the named gap in §7.0 of the motion sampler contract, which Sprint 06C.0 established had
no production subject.

That is an observation about what this specification implies. It is not a reason for anything in it,
and §4's argument was written to stand without it. Whether the requirement can be met by cancelling
and starting again, or needs something the runtime does not have, is unknown and stays unknown until
someone builds it.
