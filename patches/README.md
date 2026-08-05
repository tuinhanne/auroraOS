# Upstream patches

Aurora's changes to source it does not own. ADR-011: **the repository stores the delta and never
the base.**

Empty of real patches today, and that is Sprint 03 Phase A's correct end state rather than an
unfinished one — the machinery exists, and the first patch arrives when something actually needs
one.

## Layout

```
patches/<project>/<nnn>-<slug>.patch
```

`<project>` is the path of the AOSP git project the patch applies to, relative to the checkout
root, and it is a real directory path rather than a label:

```
patches/frameworks/base/0001-start-aurora-runtime.patch
        └────┬─────┘
             └── git -C $CHECKOUT/frameworks/base apply <the patch>
```

So a listing of this tree answers *what has Aurora changed outside its own directory* without
opening anything. The number orders application within a project; the slug says what the change is
for.

Paths inside a patch are relative to the project root, which is what `git diff` and
`git format-patch` produce when run from inside it.

## Rules

- **One patch per logical change.** Two patches touching one file is a smell, not an error — two
  logical changes to one file happens.
- **Authority runs one way.** The patch is authored and reviewed here; the checkout consumes it.
  There is no workflow whose source of truth is an edited workspace. Emitting a diff with a tool is
  fine; treating an edit in the checkout as the change itself is not.
- **Apply starts from pristine.** `apply-patches.sh` restores every file a patch touches from the
  project's own git before applying, so applying twice is the same as applying once.
- **A patch that no longer applies is a red gate**, naming the file. No fuzz, no skip.

## Tools

| | |
|---|---|
| `frameworks/base/aurora/tools/apply-patches.sh` | restore, then apply. Called by the VM's apply step |
| `frameworks/base/aurora/tools/verify-patches.sh` | the gate. VM-only, because the checkout is only there |

The witness that proves the gate can refuse is
`frameworks/base/aurora/tests/patches/witness-cannot-apply.patch`. It lives with the other
witnesses and never under this directory, because everything here gets applied.
