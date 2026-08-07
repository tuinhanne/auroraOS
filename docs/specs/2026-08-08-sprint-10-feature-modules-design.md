# Sprint 10 — The unit that has a contract

**Status:** **active 2026-08-08** · withdrawn and reinstated the same day, on an input the withdrawal
did not have

## Withdrawn, then reinstated — and the reversal is the useful part

The withdrawal below is left in place rather than deleted. It was sound on its inputs and wrong on
one of them.

**What it assumed:** that Island, Gesture and Control Panel were *speculation*, which made this sprint
an abstraction for things nothing had asked for — Sprint 04.1's rule, applied to a sprint.

**What is actually true:** they are *planned*. That is a product decision, not a forecast, and it is
information the withdrawal did not have. A second feature that will exist is not the same kind of
object as a second feature that might.

**And a second argument the withdrawal under-weighted, which is the stronger one.** It claimed the
extraction costs the same today as at the trigger. It does not — *in isolation* it costs the same, but
at the trigger it does not happen in isolation:

```
now         move Volume.                          one change, nothing competing
at trigger  move Volume  +  build Island.         two changes, and a failure in either
                                                  is attributable to the other
```

**Two unknowns arriving together** is the shape this project has spent several sprints learning to
refuse — Sprint 09 §4 names it, and Sprint 09 Task 3 then spent three build cycles on a bug that a
five-press test had entangled. Doing the extraction while Volume is the only thing in the tree that
can move is the cheap moment, and it is now.

What survives from the withdrawal unchanged: **Question 1 is still a hypothesis.** Nothing here has
shown that `arch-test.sh` can read an `android_library`, and if it cannot, C narrows to B.

## The withdrawal, as written

§9 says *"Not design for Island, Gesture or Control Panel. They do not exist."* The sprint is that
design. Aurora has **one** feature, and every benefit claimed here begins at the second.

The argument that settles it: **extracting Volume into a library costs the same whether it is done
today or the day the second feature starts.** Doing it now buys nothing except moving the risk
earlier — and the risk is real, because Question 1 is unanswered and the work touches the one thing
that was verified by eye hours ago.

Sprint 04.1's rule, applied to a sprint instead of a class: *no abstraction whose only purpose is to
make something possible that nothing has asked for.*

## What it proposed instead — superseded

A trigger rather than a sprint: *the second feature does not begin until Volume is extracted.* That is
still the right rule, and it is now satisfied by running the sprint rather than by waiting, for the
reason given above: at the trigger the extraction shares a sprint with a new feature, and here it does
not.

---

Not a sprint about directory layout. Sprint 09 produced Aurora's first feature and, in doing so,
produced the first evidence that the current arrangement stops describing anything once there are
two of them.

---

## 1. Question 0 — what is the unit that has a contract?

Everything else is downstream of this, and starting anywhere else produces an argument about taste.

Aurora's contracts today bind to **layers**, and that was right when a layer and an artifact were the
same thing. They are drifting apart:

| contract | binds to | artifact |
|---|---|---|
| `sdk.contract` | `aurora.sdk` | `aurora-sdk`, a host-testable jar |
| `platform-android.contract` | `aurora.platform.android` | a jar on the `system_server` classpath |
| `platform-systemui.contract` | `aurora.platform.systemui` | **an APK that will hold every SystemUI feature Aurora ever writes** |

The last row is the problem, and it is not aesthetic. `platform-systemui.contract` says its allow list
grew *"entry by entry, by what a compiler demanded"*. That sentence is true today because the layer
contains exactly one feature. Add Island and the same list means *the union of everything any feature
ever needed*, and the gate's answer changes from

> Volume needs `android.view.`

to

> this APK needs `android.view.`

**Those are different claims and only the first one is a measurement.** ADR-016 established that a
contract must be able to say something false; a union-of-everything list cannot.

### The answer this sprint proposes

> **The contract binds to the artifact that Soong builds, not to the directory it lives in.**

One `android_library` → one contract. One `android_app` → one contract. One jar → one contract.
Directory layout becomes a consequence of that, and stops being a decision at all.

---

## 2. Deployment unit and development unit are not the same thing

The framing that made this look like *feature vs layer* hides the actual degree of freedom. Android's
own build graph separates these constantly:

```
android_library  ──┐
android_library  ──┼──►  android_app        one shipped APK, several compiled units
android_library  ──┘
```

So the question *"one APK or many?"* is independent of *"one contract or many?"*, and conflating them
is what made A and B look like the only options.

> **A process boundary is not an artifact boundary, and an artifact boundary is not a contract
> boundary.** Sprint 09 measured the first of those the hard way — ADR-014 separated two layers
> because of *process*, and ADR-016 chose a mechanism because of what the *gate* could still see.
> This sprint separates the third.

---

## 3. The candidates

**A — one module, features as sub-packages.** Cheapest. Refused on the §1 argument: the allow list
becomes a union and stops measuring. Listed because it is what happens if nothing is decided.

**B — one module *and one APK* per feature.** Contract resolution preserved. Costs N APKs, N
`PRODUCT_PACKAGES` lines, N dexpreopt entries, and N entries in `config_pluginAllowlist` — each of
which is a real line in the composite contract ADR-015 made Aurora responsible for.

**C — one `android_library` per feature, statically linked into one `android_app`.** Contract per
feature, one artifact shipped.

**C is the proposal**, and the reason is §2 rather than the APK count: a feature is a development
unit and the plugin is a deployment unit, and there is no rule requiring them to be the same.

**Rejected outright: feature-first everywhere** (`features/volume/{sdk,runtime,platform}`).
`aurora-sdk` and `aurora-runtime` have no feature axis — a spring is not volume's. Splitting them
would either duplicate them or create feature-to-feature dependencies, which is what the four layers
exist to prevent.

---

## 4. Question 1 — does a contract still work on a library?

**This is the sprint's real risk, and the previous framing stated it as a conclusion.** The claim
*"C keeps the gate's resolution"* is not established. `arch-test.sh` reads `Android.bp` for
`expect-classpath` and `expect-host-supported`, and every module it has ever read has been a
`java_library` or an `android_app`.

```
Hypothesis
----------
An android_library carries enough metadata for a contract to be checked against it:
source-root, package-root, imports, deps, and a classpath claim.

Measurement
-----------
arch-test.sh against a feature library's contract, with no APK built.
Specifically: check_forbidden_imports, check_aurora_layering, check_soong_deps,
expect-classpath, expect-host-supported.

Exit
----
The library's contract passes, and its failure modes still fire — calibrated the way
verify-plugin-allowlist.sh was, by provoking each one.
```

If `expect-classpath` cannot be expressed for an `android_library`, that is a finding and C narrows
to B rather than being argued around.

---

## 5. Question 2 — what does the split cost, measured

Three numbers, none of them currently known.

| measurement | why it matters | how |
|---|---|---|
| **incremental build locality** | the whole developer-experience case for C. Editing `volume/` should rebuild the volume library and relink the APK — not rebuild Island and Gesture | touch one file, `m AuroraSystemUIPlugin`, read what ninja actually rebuilt |
| **gate resolution** | Question 1's exit criterion, restated as a number: how many allow-import entries does the volume contract have alone, versus the union | count both |
| **boot cost, only if C narrows to B** | N APKs means N dexopt and N signature verifications. Unmeasured, and irrelevant unless B is forced | boot with N=2 and compare `SystemServerTiming` |

The third is deliberately conditional. Measuring the cost of a candidate that Question 1 may refuse is
work spent on a question nobody asked yet.

---

## 6. What this sprint also closes

**ADR-017's open question** — `PluginFrameScheduler` is a near-copy of `ChoreographerFrameScheduler`,
duplicated because ADR-014 forbids the SystemUI layer from importing the `system_server` one, and
recorded as debt because *"moving a file across a contract boundary changes two contracts and belongs
in an ADR, not in the commit that draws the first pixel."*

That question is a special case of this sprint's: **where does code shared between features in the
same process live?** Under C it has an obvious home and an obvious contract. So this sprint does not
add an open question; it collects one.

---

## 7. Task order

1. **Task 1 — extract Volume as an `android_library`.** The code does not change. Only its module
   does.
2. **Task 2 — move the contract onto the library**, and narrow its allow list to what Volume alone
   demands. The entries that disappear are the measurement.
3. **Task 3 — prove the gate still measures.** Question 1's exit criterion, calibrated by provoking
   each failure mode rather than by observing a green run.
4. **Task 4 — only then may a second feature exist.** Not Island, not Gesture: a second feature's
   *shape*, proving the pattern repeats. If Task 3 fails, Aurora has moved exactly one feature back,
   not a source tree.

**This ordering is the risk control and not the plan's shape.** A restructure that moves everything
and then checks is a restructure whose failure costs the whole tree.

---

## 8. Exit criteria

- [ ] Question 0 answered: the unit that carries a contract is named, and the naming survives a case
      where artifact and layer disagree
- [ ] Question 1 answered **by running `arch-test.sh`**, not by reading Soong documentation
- [ ] Volume's contract lists what Volume demands, and the difference from today's union is recorded
- [ ] Incremental build locality measured, with the ninja output quoted rather than summarised
- [ ] Every gate that exists today is still green, and each one was seen red at least once during the
      move

The last is the one that keeps the others honest: a restructure that leaves every gate green without
anyone ever seeing one fail has not demonstrated that the gates still point at anything.

---

## 9. What this sprint must not do

- **Not open until Sprint 09 closes.** Met: Sprint 09 closed 6/6 on 2026-08-08.
- **Not build Island, Gesture or Control Panel.** They are planned, which is why this sprint runs —
  but Task 4 proves the *pattern* repeats with a second shape, and that is a different thing from
  building the second feature. The reinstatement changed why this sprint exists; it did not licence it
  to grow.
- **Not fix Volume's feature gaps.** Aurora replaced a dialog that could drag-to-set, mute, expand to
  other streams and reach DND; Aurora's does none of those, and
  `onLayoutDirectionChanged` / `onConfigurationChanged` / `onAccessibilityModeChanged` are no-ops.
  **That is a separate sprint and a real one** — this sprint moves code without changing behaviour, so
  that any behaviour change during it is a bug rather than a feature.
- **No abstraction whose only purpose is to make something possible that nothing has asked for.**
  Sprint 04.1's rule, and the most likely way a restructure goes wrong.
