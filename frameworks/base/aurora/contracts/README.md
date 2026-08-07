# Contracts

Two families, same authority, different observers. **Both are the truth; neither is the checker.**

| family | location | subject | read by | needs a build? |
|---|---|---|---|---|
| **source contracts** | `*.contract` here | Aurora's own source and Soong modules | `../tools/arch-test.sh` | no |
| **artifact contracts** | `artifact/*.contract` | something a build produces, including files Aurora does not write | one script per contract, e.g. `../tools/verify-plugin-allowlist.sh` | yes |

The second family arrived with ADR-015 and is **not an exception to the first**. A contract about
Aurora's own source can be checked by reading the source; a contract about what survives another
project's resource overlay cannot be, because its subject does not exist until something is built.
Same kind of claim, different reach, so a different observer.

They are in separate directories for a mechanical reason as well as a taxonomic one: `arch-test.sh`
globs `*.contract` in this directory and runs five source checks against each match. An artifact
contract has no `source-root`, so it would be reported as *"layer not created yet"* — a true
sentence from a check that was never asked, about a layer that does not exist. Separating the
directories is cheaper than teaching one tool to ignore half its input.

The source family is flat and the artifact family is nested, which is asymmetric. Moving the five
existing files into `source/` would touch `arch-test.sh` and every reference to it, for no gain in
what either family checks; it is deferred rather than overlooked.

## The rule the two families exist to enforce

> **An observer may only speak about the subject its contract names.**

Not a tooling convention. It is what keeps a check from being trusted outside the range where it can
see anything, and Sprint 09 produced the failure it prevents four times in one sprint — each time as
*a correct answer used to answer a different question*:

| true | but it does not answer |
|---|---|
| `ExtensionController` is the plugin seam | where the APK lives — that is decided by **process** |
| overlay ordering predicts which overlay wins | *why* it wins — the runtime derives priority from the partition |
| the resource writer determines the value | who owns the contract the value expresses |
| `arch-test.sh` reports a layer has no sources | nothing, if the contract has no layer |

`arch-test.sh` is not wrong about merged resources; **it has no standing to speak about them**, the
same way `aapt2` has no standing to speak about dependency layering. Each tool's reach is fixed by
the subject of the contract it reads, and a tool pointed at a subject it cannot observe produces
statements that are true and worthless — the hardest kind to notice, because nothing goes red.

**Practical consequence, and the order matters:** a new contract answers *where does my subject
live?* before anyone writes its observer. Sprint 09 arrived at that backwards — the first allowlist
measurement read `SystemUI.apk`, which is not where the merged array lives, and reported `size=1` as
if the subject had failed.

---

## Source contracts

One file per Aurora layer. Read by `../tools/arch-test.sh`; nothing else parses them.

### Format

Plain text. One `key: value` pair per line. `#` starts a comment. A repeated key adds
another entry to that key's list — there is no array syntax.

| Key | Repeats | Meaning |
|---|---|---|
| `layer` | no | Short layer name, e.g. `runtime` |
| `module` | no | Soong module name, e.g. `aurora-runtime` |
| `package-root` | no | Java package prefix owned by this layer |
| `source-root` | no | Source directory, relative to `frameworks/base/aurora/` |
| `allow-aurora-import` | yes | `aurora.*` package prefixes this layer may import |
| `forbid-import` | yes | Import prefixes that must never appear |
| `allow-dep` | yes | Soong dependencies this module may declare. **A whitelist**: every entry in the module's `static_libs` and `libs` must appear here, or the gate fails |
| `forbid-dep` | yes | Soong dependencies that must never be declared |

### Rules

`allow-aurora-import` is a whitelist: any `aurora.*` import outside it, and outside the
layer's own `package-root`, is a violation. Non-Aurora imports are governed only by
`forbid-import`, because whitelisting every legal `java.*` import would produce constant
false failures.

`forbid-import` matches by prefix, anchored at the start of the imported package path, so
`android.` catches `android.content.Context` but not a package named `myandroid.foo`.

A layer whose `source-root` does not exist is reported as `skip`, not as a failure. A
layer that has not been built yet is not a violation.

`allow-dep` was documentation until Sprint 10 Task 3 — the table above already called it *"may
declare"*, but only `forbid-dep` was checked, so a dependency on neither list passed silently. It is
now enforced, and enforcing it immediately found that `platform-android.contract` had omitted
`aurora-platform` since Sprint 03. **A rule written down and not checked reads as a rule for as long
as nothing disagrees with it.**

Two keys govern a module that deliberately holds no source:

| Key | Meaning |
|---|---|
| `expect-no-source` | `yes` asserts the `source-root` contains no `.kt` or `.java`. Import checks are then skipped with an accurate reason instead of a misleading one |

Without it, an empty or missing `source-root` is a **failure**, not a pass. Before Sprint 10 an
omitted `source-root` made every `forbid-import` report `ok` while reading nothing at all, and a
typo'd one did the same.

---

## Artifact contracts

In `artifact/`. Same `key: value` format, different keys, because the subject is a built file
rather than a source tree.

| Key | Repeats | Meaning |
|---|---|---|
| `artifact` | no | Short name of the thing being constrained |
| `target-package` | no | The package whose resources are the subject, where that applies |
| `resource` | no | The specific resource, e.g. `array/config_pluginAllowlist` |
| `read-by` | no | The runtime path that consumes it — so a reader can check the claim is still true |
| `comparison` | no | `set` or `sequence`. Which one is a fact about the consumer, not a preference |
| `expect-entry` | yes | The declared contents |
| `subject-glob` | yes | Where the built artifact is found |
| `expect-status` | no | Present only when the contract is knowingly red, with the reason |

### Rules

**The contract is the truth and the script is not.** An expected value that lives inside the
checking tool is not a contract — it is an implementation detail that happens to be enforced, and
it will be changed by whoever is editing the tooling rather than by whoever is deciding what Aurora
guarantees. This is the whole reason the family exists as files.

**`comparison` is derived, not chosen.** `set` is correct for `config_pluginAllowlist` because
`PluginManager.Config` reads the list into two `Set`s before any consumer sees it, so a gate that
compared order would be stricter than the thing it protects. A different resource may genuinely be
ordered; that has to be established from the consumer.

**Both directions of a set comparison are failures**, and they are not the same failure. A missing
entry means Aurora lost. An *undeclared* entry means an upstream changed the array — and because a
resource overlay replaces rather than merges, Aurora winning can drop another project's entries just
as silently as Aurora losing. Auto-accepting the second direction would return the contract to being
a hope.

**A knowingly-red contract is legitimate and must say so.** `expect-status` records that the claim
is correct and the artifact does not yet satisfy it. What is not legitimate is weakening the
contract so the gate goes green.
