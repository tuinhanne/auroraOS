# ADR-015 — `config_pluginAllowlist` is a composite contract, and that changes what ownership means

**Status:** accepted · 2026-08-07 · Sprint 09, after Task 3.0b

## The one question

> **Is `config_pluginAllowlist` a resource with a single owner, or a composite contract?**

Everything else follows from the answer, and the answer does not depend on any measurement still
outstanding. In particular it is independent of where `system_ext` ranks among overlay partitions,
which Task 3.0 could not measure and which nothing here extrapolates.

## Context — what the artifact already established

Task 3.0 and 3.0b measured, rather than inferred:

- AOSP declares the array with **one** entry.
- Lineage's overlay replaces it with **three** — and the first of those three is AOSP's. Lineage did
  not add an entry; **it rewrote the array and carried AOSP's entry forward.**
- A resource overlay **replaces** an array; it does not merge into one.
- Aurora appended a fourth overlay directory and lost. The merged artifact contains Lineage's three.
- The runtime resolves to exactly what the artifact contains — build merge and runtime agree.

Two of those are the whole context. The value is a union of three parties' entries; the mechanism
that writes it can only write the whole thing.

## Decision

**It is a composite contract, implemented by a single-owner mechanism.** That mismatch — not overlay
precedence — is the actual problem, and it is why "who owns this resource" is the wrong first
question. Whoever writes the array owns *every* entry in it, including the ones they did not choose
and are not qualified to judge.

So Aurora does not get to "add an entry". On any product where Aurora writes this array, **Aurora
becomes responsible for AOSP's and Lineage's entries too** — for noticing when either changes, and
for carrying the change forward.

### The obligation is discharged by a gate, not by remembering

A responsibility that depends on someone remembering to check is not a responsibility, it is a hope.
This project has already produced the counter-example twice in one sprint: a note saying
`# KHONG dung set -e/-u` existed in the tree and did not reach a new script; two overlay files each
record a fact about this array and neither knows the other exists.

**So accepting ownership is conditional on the check existing.** The gate:

```
build SystemUI  →  aapt2 dump the RRO  →  compare the array as a SET against a declared expectation
```

Set comparison, not `contains`. Order is provably irrelevant — `PluginManager.Config` reads the list
into two `Set`s and `isPrivileged` does membership tests — and an overlay replaces the array, so
**winning can drop entries just as silently as losing.** Both directions have to be read.

And it is a build-time gate with no device in it. That is licensed by a measured fact rather than an
assumption: Task 3.0b showed `cmd overlay lookup` returns exactly what `aapt2 dump` reports for this
resource, so the cheap measurement is a sound proxy for the expensive one. **If that equivalence ever
breaks, the gate's licence breaks with it** — which is a thing to re-measure when the resolution path
changes, not a permanent grant.

### The third verdict is the point

A gate that only asked *"is Aurora's entry present?"* would answer the easy question. This one must
distinguish three outcomes:

| outcome | meaning | what happens |
|---|---|---|
| exact set match | the contract holds | pass |
| Aurora's entry missing | Aurora lost the merge | fail — Aurora is not loadable on a user build |
| an upstream entry missing or added | AOSP or Lineage changed the array | **fail, and it must** |

The third is why the gate exists. When Lineage adds a plugin, Aurora's copy is silently wrong, and
the only acceptable outcome is that a human decides whether to carry the new entry. **An auto-accept
would convert the gate back into the hope it replaced.**

## What this does not decide

**The mechanism.** Overlay directory ordering, a dedicated Aurora RRO, or something else — that
question waits on the one measurement Task 3.0 could not make: where `system_ext` ranks. No
extrapolation from `vendor 0–4, product 5–38`; this sprint has twice shown that a model can predict
the right outcome from the wrong mechanism, and a third time would be a pattern rather than bad luck.

What this ADR does is make the mechanism question smaller. Any candidate is now judged on one added
criterion — *does the gate above still work under it?* — and every candidate that writes the whole
array satisfies it equally, so the mechanism can be chosen on cost and portability alone.

## Rejected — patching Lineage's overlay file

The genuine alternative, and it has a real argument behind it that deserves stating rather than
waving away: **a patch fails loudly.** If Lineage edits the array, the patch conflicts,
`verify-patches.sh` goes red, and someone must look. That is exactly the failure characteristic a
composite contract wants, and it is better than what an overlay does on its own.

It is refused for two reasons, in this order:

1. **The gate supplies the same property without borrowing another project's file.** Once drift is
   caught by a set comparison in Aurora's own tree, the patch's advantage disappears — and what
   remains is a modification to a file Aurora does not own, to obtain something Aurora can obtain
   itself.
2. **ADR-013 forbids it, correctly.** Delete such a patch and Aurora stops loading on user builds:
   the patch would carry the *subject*, not the observer. Note this is a reason and not the reason —
   an ADR that had only rule-compliance to offer would be worth re-examining, and ADR-013 was itself
   corrected today for a false premise.

## Consequences

- **`verify-plugin-allowlist.sh` moves from "consider later" to required.** It is no longer an
  optional hardening; this ADR's acceptance of ownership is conditional on it. Task 3.0's measurement
  script is its prototype and already does build → dump → set-compare.
- **The expected set becomes a declared artifact**, not a constant buried in a script — it is the
  contract this ADR names, and it changes only by decision.
- **Aurora now tracks two upstreams for one resource.** That is a new kind of dependency for this
  project: not a build dependency, not an API dependency, but an obligation to notice someone else's
  edit. It is the first, and the gate is the only reason it is acceptable.
- The mechanism decision is deferred, and the sprint stops rather than guessing — the same way
  Sprint 03 stopped for ADR-012.
