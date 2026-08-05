# ADR-010 — The minimum seam for a service implementation

**Status:** accepted · 2026-08-06 · Sprint 04.1

## Context

Sprint 04 shipped seven service interfaces into `aurora.sdk.service` and none of the
`aurora.platform` half it defined. Sprint 04.1 asks whether any of them can be implemented at all,
with `VolumeService` as the first subject.

Task 1 answered Question 0 as **B**, and the finding is stated as a capability rather than as a
type, because a type is already a choice of shape:

> **Task 1 found exactly one missing capability common to all seven members** — a source of audio
> state that can be read, written and observed. Nothing in `aurora.sdk` or `aurora.runtime` reads
> or writes any device state; the count was zero of seven, not a shortfall at the margin.

It also found that the second candidate for a missing abstraction was **already built**.
`AuroraDispatcher`, added in Sprint 06A and unused by anything until now, covers the threading half
of this problem — `runtime.contract` forbids `Handler(`, `Looper` and `Thread.sleep`, and the
dispatcher is what makes that survivable. A seam built for one reason reduced the number of new
abstractions here from two to one.

This ADR answers the question Task 2 exists for:

> **What is the smallest seam that makes all seven members implementable without changing the SDK?**

It is answered by elimination. A design that merely *works* is not the subject; every smaller
option has to fail for a reason that outlives this sprint.

## Decision

**One state source, declared in `aurora.runtime`, speaking in the platform's own units, implemented
in `aurora.platform`.** Working name `VolumeSource`.

- **Declared in `aurora.runtime`, not `aurora.sdk`.** RULE-007: define the interface in the layer
  that needs it. `aurora.sdk` is the public surface a third party writes against, and nothing
  outside the runtime has a reason to supply audio state.
- **Enough information for `stepCountOf(stream)` to remain derivable.** A source exposing only
  normalised values cannot satisfy the existing SDK contract, because the service must answer that
  member and normalisation has already discarded what it needs.

  **This constrains information content, not interface shape.** Task 1 proved a source cannot be
  *only* normalised; it did not prove a source must be a pair of `Int` accessors. Separate
  `rawLevel`/`maxLevel` calls, a snapshot type, and a per-stream state object all carry the same
  information and all satisfy it. Which of them is written is Task 3's decision, made while writing
  the thing, and this ADR does not pre-empt it.
- **`DefaultVolumeService` lives in `aurora.runtime`**, holding everything that is not device
  state: normalisation, clamping, the listener list, and dispatch through `AuroraDispatcher`.
- **Only the source implementation lives in `aurora.platform`**, and it is the one artifact this
  sprint cannot build — `platform.contract` forbids `android.` until Sprint 03.

### The sentence about where implementations live, stated rather than left to inference

*"The implementations live in `aurora.platform`"* appears in **two** documents, not one —
`ServiceProvider`'s KDoc and `AuroraService`'s, which says it while explaining why the interfaces
sit in the SDK. Task 3 found the second while writing against it. What follows narrows both; there
is no third site.

Read literally the sentence forbids this decision, and the reconciliation must be written down or
the next reader finds a service implementation in `runtime` and a document saying otherwise:

**The sentence describes the expected case — a service that wraps Android directly — and is not a
rule about every part of every service.** The pattern the same KDoc prescribes two paragraphs later
is this decision exactly: *"define the interface in the layer that needs it, implement it in the
layer that can."* `ServiceProvider` is itself built that way, declared in `aurora.runtime` and
implemented in `aurora.platform`, and RULE-007 states it normatively.

**This ADR narrows the interpretation rather than changing the document.** The KDoc is not amended,
overridden or contradicted; its sentence stays true of the case it was written about. What is added
is a boundary on how far it reaches.

## Alternatives considered

Each is smaller than the decision, and each fails for its own reason.

**Reach through `AuroraContext.hostContext()`.** No new type at all — the context already carries
an `Object` that is the real platform context on device. Rejected twice over: extracting anything
from it requires an `android.` import, which `runtime.contract` forbids permanently and
`platform.contract` forbids until Sprint 03, and `Object` carries no contract, so every call site
would cast and every test would have to fake a whole Android class.

**Deliver state over the event bus.** The bus exists, `AuroraDispatcher` is already wired to it, and
volume changes are events. Rejected because **a bus has no query path**: `levelOf`, `stepCountOf`,
`isMuted` and `activeStream` are questions asked at an arbitrary moment, and a bus can only deliver
what it has already been told. An implementation would have to cache every stream's state from
events it may have started listening for too late, and answer from the cache — inventing a source of
truth rather than reading one. `AuroraEvent` is also an empty marker interface with no volume event
declared, so this is not the smaller option it looks like.

**Inject seven callbacks instead of one interface.** Genuinely smaller in type count, and it avoids
naming a new abstraction. Rejected because it **splits ownership of one thing into seven**: the
seven members are views of a single piece of device state, and nothing would keep seven separately
supplied functions consistent with each other. The change-notification member has no home in this
shape at all — it is not a value to read but a subscription, and a bare callback cannot say which
source it belongs to.

**No seam: put the whole service in `aurora.platform` and wait for Sprint 03.** The reading
`ServiceProvider`'s sentence suggests, and the honest alternative rather than a straw one. It costs
the thing RULE-007 exists to protect: clamping, normalisation, listener management and the
`Disposable` question all become untestable without a device, and every one of them is logic this
sprint would otherwise verify on a host today. It also leaves Sprint 04.1 with nothing to deliver
until an unrelated sprint lands.

**And the cost compounds past this sprint.** With the logic in `platform`, `VolumeService` stops
being runtime logic, so every later consumer — a volume overlay, a volume shortcut, a volume
gesture — has to fake Android in order to test itself. With the seam, each of them fakes one
interface:

```
Android ──► VolumeSource ──► DefaultVolumeService ──► feature      feature fakes one interface
Android ─────────────────────► VolumeService ──────► feature      feature fakes Android
```

That is not a volume argument. It is what the layer split is for, and this is the first production
subject in a position to demonstrate it.

**One source, but mirroring `VolumeService` exactly.** Not rejected so much as clarified, because a
reviewer will ask why a seam with seven-ish members earns its place beside an interface with seven.
The two are not the same shape: the source speaks in raw steps, is single-implementation, has no
listener list and no clamping, and exists to be faked. The service normalises, clamps, owns
subscription and is the public contract. What the seam moves is not method count — it is the
Android boundary.

## Consequences

- `VolumeService` becomes implementable **today**, on a host, with every member covered by tests
  and no Android anywhere. Only the source implementation waits, and it waits on Sprint 03, which
  was already blocking everything else.
- The first row of the device-runnable inventory — *any service implementation at all* — closes for
  a feature's reason rather than because the row was blank.
- Six services remain unimplemented. This ADR does not claim they need the same shape; it claims
  this one does, for reasons found by looking at seven members rather than by analogy.
- **Question 2 narrows and does not close.** Raw units are now the source's responsibility. Whether
  the platform implementation converts anything on the way, or hands over exactly what the audio
  API gives it, is decided in Task 3 by whoever writes it.
- **Question 1 is untouched.** Whether `VolumeService`'s `add`/`remove` listener pair is deliberate
  or unfinished is answered by writing the listener list, not by this decision. If it turns out the
  pair cannot work as declared, that is an SDK signature change and needs an ADR of its own.
- Nothing is built by this ADR. `VolumeSource` does not exist until this is accepted, deliberately:
  writing it first would have made **B** true by construction and Question 0 unanswerable.
