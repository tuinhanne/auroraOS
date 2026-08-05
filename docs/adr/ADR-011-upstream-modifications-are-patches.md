# ADR-011 — Upstream modifications are patches

**Status:** accepted · 2026-08-06 · before Sprint 03

## Context

Everything Aurora has built so far lives under `frameworks/base/aurora`, a directory that exists in
no upstream tree. The workstation repository holds it, `sync-to-vm.ps1` sends it, and
`vm-apply-code.sh` rsyncs it into the LineageOS checkout on the build VM. Three paths, all owned by
this project, and the question of how to modify somebody else's file has never come up.

Sprint 03 is where it does. Its central deliverable is initialising `AuroraRuntime` inside
`SystemServer`, and:

```
git ls-files frameworks/    →  frameworks/base/aurora, and nothing else
ls frameworks/base/         →  aurora        (no services/)
vm-apply-code.sh PATHS      →  device/samsung/beyond2lte
                               device/samsung/exynos9820-common
                               frameworks/base/aurora
```

`SystemServer.java` is not on the workstation, is not tracked here, and is not on any sync path. It
exists only inside the VM's `repo`-managed checkout. **No mechanism in this project can currently
express a change to it**, which is a workflow question and not an architectural one — and it will be
asked again by every AOSP file Aurora ever needs to touch.

`.gitignore` already answers the adjacent question and gives its reason:

> "These are unmodified clones … kept locally only so the sync script has something to compare
> against. The build machine fetches them with `repo`, so tracking them here would duplicate
> upstream for no benefit."

That reasoning covers *unmodified* clones. A file Aurora changes is a different case and the
existing rule says nothing about it.

## Decision

**For upstream source Aurora does not own, this repository stores the delta and never the base.**

`SystemServer` is the first instance, not the subject. The rule is stated over upstream
modifications in general so that `PackageManagerService`, `SettingsProvider`, an `init.rc` or a
manifest are all covered by it without another ADR.

Four parts:

1. **The repository stores patches.** One patch per logical change, under a path that mirrors the
   file it modifies, so that where a patch applies is legible without opening it.
2. **The AOSP checkout is a workspace, not a state holder.** It consumes patches and holds no
   information that the repository lacks. It must be reconstructible from `repo sync` plus this
   repository's patches, and anything true of it that is not derivable from those two is a defect.
3. **Authority runs one way: repository → workspace.** The patch is what is authored, reviewed and
   applied; the workspace consumes it and teaches it nothing. **There is no workflow whose source of
   truth is an edited workspace.**

   Not a ban on tooling. Serialising a diff with `git format-patch`, or any script that emits a
   unified diff, is how a patch gets written down at all, and none of that is forbidden. What is
   forbidden is an edit made in the workspace becoming the authoritative version of a change.
4. **A patch that no longer applies is a failure, loudly.** After an upstream rebase, a gate checks
   every patch against the tree and fails on any that does not apply cleanly. It does not apply with
   fuzz, and it does not skip.

### Why part 3 is the one that carries the decision

Parts 1, 2 and 4 are mechanics. Part 3 is what the choice is actually about.

If a patch may be produced by editing a file in the workspace and exporting the result, then the
edited workspace is where the change really lives and the patch is a snapshot of it. The repository
becomes a record of something that happened elsewhere — which is the option this ADR rejects, wearing
the shape of the one it accepts. **The direction of authorship is the whole difference**, and it is
invisible in the finished artifact: the same patch file is produced either way.

So it is stated as a rule rather than left to habit, and stated about **authority** rather than
about tooling. The distinction matters because the tooling reading is both wrong and tempting: a
diff has to be produced by something, `git format-patch` is a reasonable something, and a rule that
appeared to forbid it would be quietly ignored — which is worse than not having one, because it
would take the real prohibition with it.

What may not happen is a workspace edit that anybody treats as the change itself. A scratch copy
used to check that a patch applies, or to emit a diff, is a tool being used; a scratch copy that
somebody would be upset to lose is the source of truth having moved.

### Applying, and what makes re-applying well defined

Because the workspace holds no state, applying always begins from pristine: the affected files are
restored from the checkout's own `repo`-managed git before patches are applied. Applying twice is
therefore the same as applying once, and there is no question of a half-applied tree — the failure
mode where a rebuild silently doubles a change cannot arise.

Ordering is by path, and two patches touching one file is a smell rather than an error: the rule is
one patch per logical change, and two logical changes to one file is a thing that happens.

### Where the gate can run, and where it cannot

On the VM only, because the AOSP tree exists only there. That is the exact mirror of gate 6 in
`verify-motion-evidence.sh`, which is workstation-only because `docs/` is never synced. Both say so
rather than failing meaninglessly on the machine that cannot see their input — a pattern this
project already settled once.

## Alternatives considered

**Track the modified upstream files in this repository, and add them to the sync paths.** The
simplest to explain and the only one needing no new tooling. Rejected because it contradicts the
reason `.gitignore` gives for not mirroring upstream, and because the cost is not paid once: every
AOSP rebase merges an entire vendor file — tens of thousands of lines Aurora did not write — in order
to carry the handful of lines it did. The signal-to-noise ratio of the diff, which is what makes
review possible at all, would be roughly zero.

**Do not modify `SystemServer`; find an existing extension point.** Not rejected on merit, and
possibly right — but it is an *implementation* decision belonging to Sprint 03's Question 0, and it
cannot be chosen here because nobody has yet shown an equivalent hook exists. The README names
`SystemServer` explicitly. If Sprint 03 finds a better initialisation point, this ADR is unaffected:
it governs how an upstream change is stored, not whether one is needed.

**Edit directly on the VM and keep nothing.** Fastest, and it is what happens by default when nobody
decides. Rejected on all three counts the other options are weighed against: the change is not
reviewable, not reproducible, and gone the first time the VM is rebuilt or replaced. It also inverts
the source of truth, which is the specific failure part 3 exists to name.

## Consequences

- `sync-to-vm.ps1` and `vm-apply-code.sh` gain a patches path. Until they do, this decision is
  written down and not yet operational.
- A patch gate joins the VM-side scripts, alongside `arch-test.sh` and `verify-motion-tests.sh`.
- **Aurora's diff against upstream becomes readable in one place.** Today the answer to "what has
  this project changed outside its own directory?" is *nothing*; after Sprint 03 it will be a
  directory listing rather than an investigation.
- An upstream rebase gets a defined failure mode. A patch that stops applying is a red gate naming
  the file, not a silently absent behaviour discovered later on a device.
- Sprint 03 keeps its own subject. **Where the runtime is initialised** is Question 0 of that sprint
  and is untouched by this decision; **how a change to somebody else's file is stored** is settled
  here and will not need deciding again when the file is a different one.
- The rule binds nothing that already exists. `frameworks/base/aurora` is Aurora's own directory and
  stays a tracked tree, synced as it is today.
