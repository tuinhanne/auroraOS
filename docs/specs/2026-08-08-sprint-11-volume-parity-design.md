# Sprint 11 — Closing the gap between the volume spec and the volume overlay

**Status:** design in review · 2026-08-08

Sprint 09 shipped an overlay and treated `volume-overlay.md` as the reason to build it. This sprint
reads that document as a **checklist against the thing that exists**, which nobody had done.

The result reorders the work, and it removed an item I had proposed.

---

## 1. What the existing spec already decided, and I nearly ignored

`volume-overlay.md` §9, written 2026-08-06:

> **Whether it can be touched.** Dragging the level directly, or expanding to reach other streams, is
> a second feature and a different set of questions.

**So drag-to-set is not a gap in this feature; it is a different feature**, and it is out of this
sprint. It needs its own behaviour spec — what happens to the dismiss delay while a finger is down,
whether the overlay may take touches from the app behind it, what a drag means at the ends of the
range, and whether an overlay that can be touched is still *feedback* rather than a *control*, which
§1 says it is.

Naming it here so its absence is deliberate. It was proposed out loud before this document was
re-read, which is the mistake this section exists to record: **the spec had already answered a
question I was about to open a sprint on.**

---

## 1b. Scope change, same day — touch is in, and §9 is now a finding

**Recorded as a finding rather than absorbed silently**, which is exit criterion 5's whole purpose.

§1 above correctly reported that `volume-overlay.md` §9 defers touch. The product owner then asked for
it, with one of the deferred questions already answered: *when the user touch-drags, the width must go
wide as it does on the first key press.*

That is a decision, not a forecast, so touch is in scope. But **§9 deferred touch because of questions,
and one answer does not close the rest.** Four more were answered during implementation, and each is
recorded here so that the next person reads a decision rather than inferring one from code:

| question §9 left open | answer, and why |
|---|---|
| does the level spring toward the finger? | **no.** The finger assigns the level; the spring is for keys only. A spring chasing a finger is lag, and lag in a direct control reads as a slow phone rather than as motion |
| what happens to the dismiss delay while a finger is down? | **it stops**, rather than restarting. A finger held still for three seconds is still an interaction, and a restarting timer would remove the overlay underneath it |
| a drag past either end? | clamps, and the overlay stays up — §7's reason applies to a drag as much as to a press |
| touches outside the bar? | fall through to whatever is behind, which `FLAG_NOT_TOUCH_MODAL` already arranged. The overlay is a control now, but only where it is drawn |

**`volume-overlay.md` is therefore incomplete, and that is the finding.** It says the overlay is
*feedback, not a destination* (§8) and *not a persistent control* (§1); it is now also a control, and
the document has no section describing that. Writing that section is a UX decision and is **not** done
here — the standing note at the top of that document requires the reason to be recorded before the
behaviour, and a sprint that has already built the behaviour cannot honestly satisfy it.

What this sprint may do is what it did: implement what was asked, and leave the specification's gap
visible rather than backfilling it to match.

---

## 2. The actual gaps

Read as requirements, each quoted rather than paraphrased.

### §6 and §1 — mute is unimplemented, and it is the largest gap

> *Muting is a state, not a level of zero. The overlay must distinguish silent because it is at the
> bottom from silent because it is muted, because the recovery is different: one is a press away, the
> other is not.*
>
> *Muting **preserves** the level. Unmuting returns to the same loudness, and the overlay shows the
> preserved level while muted rather than showing zero.*

`AuroraVolumeDialog.onStateChanged` reads `level`, `levelMin` and `levelMax`. It does not read
`muted`, so a muted stream is drawn as whatever its preserved level is — with **no indication that it
is silent at all**, which is worse than showing zero. A user who muted the ringer sees a half-full bar.

§1 lists this as one of the overlay's three jobs: *"which stream, **and whether it is muted**"*.

### §8 — nothing is announced

> *Every change is **announced**, including changes that came from the hardware keys rather than from
> touch. A user who cannot see the overlay gets the same three answers §1 lists.*
>
> *The dismiss delay is **not** the announcement's deadline.*

Aurora announces nothing. AOSP's dialog is a `View` hierarchy with content descriptions and gets much
of this for free; Aurora draws with `Canvas` and gets none of it — **the choice that made the first
pixel cheap is the choice that made accessibility manual.** That is a consequence of Sprint 09's
"one surface, not a layout system", and it is now due.

### §7 — probably satisfied, unverified

> *At maximum, further presses do nothing to the level. The overlay still responds — it stays up and
> the delay restarts — because a press that produces no feedback at all is indistinguishable from a
> failure.*

Aurora restarts the delay in `onStateChanged`, which fires on *changes*. At the top of the range a
press changes nothing. Whether `onShowRequested` still arrives — and so whether the delay still
restarts — is **unmeasured**, and it is the kind of thing that looks fine until someone holds
volume-up at maximum.

### Not in the spec, but broken: RTL

`Gravity.END` flips the window to the left edge in a right-to-left locale. The track is drawn against
`right = width` inside that window, and **that does not flip**, so the bar detaches from the screen
edge and floats 24 dp inside it. §9 defers *"where on screen it sits"*, so this is not a spec
violation — it is a bug in what Sprint 09 chose.

---

## 3. Task order

1. **Task 1 — measure §7** before changing anything. One press at maximum, and read whether the
   dismiss delay restarted. It is the cheapest item and it decides whether it is an item at all.
2. **Task 2 — mute.** §6's two requirements, and the visual language for *muted* is a UX decision to
   be recorded before it is coded, per `volume-overlay.md`'s standing note.
3. **Task 3 — announcements.** §8, including the requirement that the dismiss delay is not the
   announcement's deadline, which is a real constraint on the 1.5 s timer.
4. **Task 4 — RTL.** A bug, and the only item here with a known fix.

Task 2 before Task 3 because a muted state that is announced but not drawn is worse than one that is
neither.

---

## 4. Exit criteria

- [ ] §7 measured, not assumed, and the result recorded either way
- [ ] A muted stream is distinguishable from a stream at minimum **by looking**, and the preserved
      level is still visible — §6
- [ ] A volume change is announced with the stream, the level and the mute state, and an announcement
      is not cut short by the dismiss delay — §8
- [ ] The bar sits against the screen edge in a right-to-left locale
- [ ] `volume-overlay.md` gains no new requirements during this sprint. **If something needs
      specifying, it is a finding that the spec was incomplete, and it gets recorded as that** rather
      than quietly added

The last one guards against the failure this sprint opened with: a specification that grows to match
whatever was built stops being able to say the build is wrong.

---

## 5. Out of scope, deliberately

- **Drag to set**, and **expanding to other streams** — §9 defers both, and §1 calls the overlay
  feedback rather than a control. They need a spec, not a task.
- **Lock screen, AOD, dozing, multiple displays** — §9.
- **Feature-module restructuring.** Sprint 10 did that. Volume's code moves for no other reason now.
