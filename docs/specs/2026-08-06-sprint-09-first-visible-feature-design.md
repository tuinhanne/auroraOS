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

## 3. Question 1 — where does Aurora draw from?

Aurora currently lives in `system_server`. That is where it *starts*; it is not obviously where it
should *draw*.

| candidate | what it means | what would decide it |
|---|---|---|
| a window from `system_server` | Aurora adds a system window directly | is a system window from that process acceptable, and what does it cost when Aurora is wrong? |
| inside `SystemUI` | where the system's own volume dialog lives | an Aurora component in SystemUI is an upstream patch, or a package the product adds — which is it? |
| Launcher / QuickStep | what the README names for gesture work | almost certainly wrong for a system overlay, and worth refuting rather than ignoring |

**None has been examined.** The README says gesture work belongs in `aurora.platform` acting on
SystemUI and Launcher3, which is a hint about a different subject and not an answer to this one.

Whatever wins, it lands in `aurora.platform.android` or in a new module beside it — ADR-012's rule
holds, and the allow list grows by what a compiler demands.

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
3. **Task 3 — the first pixel.** Whatever Question 2 decided, static or animated.
4. **Task 4 — look at it.** The only sprint so far whose exit criterion is a person watching a
   screen. Boot PASS was checkable from a log; this is not.

Task 3 does not start until Tasks 1 and 2 have answers, and Task 2 may find that Sprint 08 has to
come first — in which case this sprint stops and says so, exactly as Sprint 03 stopped for ADR-012.

---

## 7. Exit criteria

- [ ] Question 0 answered against stated criteria, with a real alternative weighed
- [ ] Question 1 answered by survey, with what was looked at recorded — including whatever turned
      out not to exist
- [ ] Question 2 answered, and if the answer is *animated*, Sprint 08 is a named prerequisite rather
      than something discovered mid-task
- [ ] Something is visible on a device, and a person has looked at it
- [ ] Nothing was built whose only purpose was to make the looking possible

The fourth is the sprint's real test and the first in this project that no gate can check. The fifth
is what keeps the fourth honest.
