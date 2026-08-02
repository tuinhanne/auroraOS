# Sprint 02 — Architecture Boundary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the Aurora layering rules from a README convention into machine-checked contracts, so a violation fails a script instead of being noticed in review — or not at all.

**Architecture:** Enforcement comes from two independent mechanisms that back each other up. The *primary* one already exists: `sdk_version: "core_current"` keeps `android.*`, `com.android.server.*` and `com.android.internal.*` off the compile classpath entirely, so a forbidden import cannot compile. The *secondary* one is new: declarative `.contract` files plus `tools/arch-test.sh`, which re-checks the rules by scanning sources and `Android.bp`, and proves the primary mechanism still bites by compiling negative fixtures that must fail. The second exists because the first can be silently disabled by one edit to `Android.bp`.

**Tech Stack:** Soong (`Android.bp`), bash, `javac` from the tree's prebuilt JDK, JUnit 4 (existing host test suite).

---

## Why this sprint is not "add enforcement"

Sprint 01 already satisfies every forbidden-import rule, by construction. Nothing in
`aurora.sdk`, `aurora.runtime` or `aurora.platform` can reach Android APIs, because none
are on the classpath.

What is missing is everything that keeps it true:

- The rules live in prose in a README. Nothing reads them.
- One person adding `framework` to `static_libs` in `Android.bp` silently removes the
  entire guarantee, and no test fails.
- Sprint 03 deliberately gives `aurora-platform` access to `framework`. From that moment
  the classpath no longer protects `aurora.runtime` by accident — it must be protected on
  purpose.

So the deliverable is a *tripwire*, and the exit criterion is that the tripwire fires when
the boundary is crossed. A test suite that only proves "everything is fine right now" would
be worthless; Task 6 exists to prove the checks actually fail on bad input.

---

## File Structure

```
frameworks/base/aurora/
├── contracts/
│   ├── README.md              Format documentation for .contract files
│   ├── sdk.contract           Rules for aurora.sdk
│   ├── runtime.contract       Rules for aurora.runtime
│   ├── platform.contract      Rules for aurora.platform
│   └── device.contract        Rules for aurora.device (layer not created yet)
├── tools/
│   └── arch-test.sh           Reads the contracts, enforces them, exits non-zero on violation
└── tests/
    └── arch/                  Negative fixtures. NOT in any Soong glob — see Task 5.
        ├── SdkImportsRuntime.java.txt
        ├── RuntimeImportsPlatform.java.txt
        ├── RuntimeImportsServer.java.txt
        └── RuntimeImportsInternal.java.txt
```

`tests/arch/` is safe from Soong because `Android.bp` globs `tests/java/**/*.java`, not
`tests/**`. The `.java.txt` suffix is a second, independent guard: even if the glob is
widened later, these files still are not `.java`.

---

## Decisions taken, with reasons

**Contract files are `key: value` text, not JSON or XML.** They are read by a bash script
in an AOSP tree, where adding a JSON parser dependency to run a lint check is a poor trade.
Repeated keys mean "add to the list", which keeps the format free of arrays.

**Forbidden imports are checked by prefix, not by exact match.** `forbid-import: android.`
must catch `android.content.Context` and `android.os.Build` alike.

**Aurora-internal imports use a whitelist; everything else uses a blacklist.** Trying to
whitelist every legal `java.*` import would generate constant false failures over things
like `java.util.concurrent.ConcurrentHashMap`. But layering violations are exactly the
thing this sprint is about, so any `aurora.*` import not explicitly allowed is a failure.

**`arch-test.sh` skips a layer with no sources instead of failing.** `device.contract`
describes a layer that does not exist. A missing layer is not a violation; silently
passing without saying so would be. It prints `skip` explicitly.

**Negative fixtures compile against only the Aurora jars.** Reconstructing the exact Soong
javac classpath in bash is fragile and would break on every toolchain bump. Compiling with
a deliberately minimal classpath tests the same property — the forbidden symbol is not
reachable — with far less to go wrong.

---

## Task 1: Establish the baseline empirically

Before writing checks, confirm what the compiler currently allows. If this task's findings
contradict the assumptions above, stop and revise the plan rather than building checks on
a wrong premise.

**Files:**
- Create: none (investigation only)

- [ ] **Step 1: Start the build VM and enter the tree**

```bash
gcloud compute instances start instance-20260731-135250 --zone asia-southeast1-b
gcloud compute ssh instance-20260731-135250 --zone asia-southeast1-b --command 'bash -lc "
cd /mnt/build/lineage
source build/envsetup.sh >/dev/null 2>&1
lunch lineage_sdk_phone_x86_64-bp4a-userdebug >/dev/null 2>&1
echo TARGET_PRODUCT=\$TARGET_PRODUCT
"'
```

Expected: `TARGET_PRODUCT=lineage_sdk_phone_x86_64`

- [ ] **Step 2: Prove `android.*` is not on the classpath today**

Create `/tmp/probe/aurora/runtime/Probe.java` on the VM:

```java
package aurora.runtime;
import android.content.Context;
public final class Probe { Context c; }
```

Temporarily add it to `runtime/java/aurora/runtime/`, run `m aurora-runtime`, and record
the error. Then delete it.

Expected: build FAILS with `error: package android.content does not exist`

If it *succeeds*, the premise of this sprint is wrong: something already puts Android on
the classpath. Stop and investigate before continuing.

- [ ] **Step 3: Record the current Soong dependency graph**

```bash
grep -A6 'name: "aurora-' frameworks/base/aurora/Android.bp
```

Expected: `aurora-sdk` has no `static_libs`; `aurora-runtime` has `["aurora-sdk"]`;
`aurora-platform` has `["aurora-runtime"]`; all three have `sdk_version: "core_current"`.

- [ ] **Step 4: Commit nothing**

This task produces knowledge, not files. Write the findings into the PR description or a
scratch note. If Step 2 behaved unexpectedly, revise this plan before Task 2.

---

## Task 2: Contract format and the four contract files

**Files:**
- Create: `frameworks/base/aurora/contracts/README.md`
- Create: `frameworks/base/aurora/contracts/sdk.contract`
- Create: `frameworks/base/aurora/contracts/runtime.contract`
- Create: `frameworks/base/aurora/contracts/platform.contract`
- Create: `frameworks/base/aurora/contracts/device.contract`

- [ ] **Step 1: Write the format documentation**

`contracts/README.md`:

```markdown
# Layer contracts

One file per Aurora layer. Read by `../tools/arch-test.sh`; nothing else parses them.

## Format

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
| `allow-dep` | yes | Soong dependencies this module may declare |
| `forbid-dep` | yes | Soong dependencies that must never be declared |

## Rules

`allow-aurora-import` is a whitelist: any `aurora.*` import outside it, and outside the
layer's own `package-root`, is a violation. Non-Aurora imports are governed only by
`forbid-import`, because whitelisting every legal `java.*` import would produce constant
false failures.

`forbid-import` matches by prefix, so `android.` catches `android.content.Context`.

A layer whose `source-root` does not exist is reported as `skip`, not as a failure. A
layer that has not been built yet is not a violation.
```

- [ ] **Step 2: Write `sdk.contract`**

```
# aurora.sdk - public, stable surface.
# Depends on nothing. Everything else may depend on it.

layer:          sdk
module:         aurora-sdk
package-root:   aurora.sdk
source-root:    sdk/java

# The bottom layer imports no other Aurora package.

forbid-import:  android.
forbid-import:  com.android.server.
forbid-import:  com.android.internal.
forbid-import:  aurora.runtime.
forbid-import:  aurora.platform.
forbid-import:  aurora.device.

forbid-dep:     framework
forbid-dep:     framework-minus-apex
forbid-dep:     services
forbid-dep:     aurora-runtime
forbid-dep:     aurora-platform
```

- [ ] **Step 3: Write `runtime.contract`**

```
# aurora.runtime - process lifecycle and environment abstraction.
# May use the SDK. Must stay free of Android so it unit tests on a host JVM.

layer:          runtime
module:         aurora-runtime
package-root:   aurora.runtime
source-root:    runtime/java

allow-aurora-import: aurora.sdk.

forbid-import:  android.
forbid-import:  com.android.server.
forbid-import:  com.android.internal.
forbid-import:  aurora.platform.
forbid-import:  aurora.device.

allow-dep:      aurora-sdk

forbid-dep:     framework
forbid-dep:     framework-minus-apex
forbid-dep:     services
forbid-dep:     aurora-platform
```

- [ ] **Step 4: Write `platform.contract`**

```
# aurora.platform - system integration.
# The only layer that will be allowed to reach into android.* (from Sprint 03).
# Until then the forbid-import list below keeps it as clean as the layers beneath it.

layer:          platform
module:         aurora-platform
package-root:   aurora.platform
source-root:    platform/java

allow-aurora-import: aurora.sdk.
allow-aurora-import: aurora.runtime.

# Sprint 03 will delete the `android.` line below and replace it with a narrower
# allow list. com.android.internal. stays forbidden permanently: it is unstable
# private API, and depending on it makes every AOSP rebase a liability.
forbid-import:  android.
forbid-import:  com.android.internal.
forbid-import:  aurora.device.

allow-dep:      aurora-runtime

forbid-dep:     services
forbid-dep:     aurora-device
```

- [ ] **Step 5: Write `device.contract`**

```
# aurora.device - per-device behaviour.
#
# NO MODULE EXISTS YET, deliberately. With a single target device there is nothing
# to abstract, and inventing DeviceProfile / DeviceCapabilities now would mean guessing
# what varies between devices before a second device exists to tell us. This file records
# the rules that will apply when the layer is created; arch-test.sh reports it as `skip`
# until then.

layer:          device
module:         aurora-device
package-root:   aurora.device
source-root:    device/java

allow-aurora-import: aurora.sdk.
allow-aurora-import: aurora.runtime.
allow-aurora-import: aurora.platform.

forbid-import:  com.android.internal.

allow-dep:      aurora-platform
```

- [ ] **Step 6: Commit**

```bash
git add frameworks/base/aurora/contracts/
git commit -m "Sprint 02: declare layer contracts"
```

---

## Task 3: arch-test.sh — forbidden import check

Build the script one check at a time, verifying each against a deliberately broken input
before moving on.

**Files:**
- Create: `frameworks/base/aurora/tools/arch-test.sh`

- [ ] **Step 1: Write the script skeleton and the import check**

```bash
#!/usr/bin/env bash
# Enforce the Aurora layer contracts. Run from anywhere:
#   frameworks/base/aurora/tools/arch-test.sh
# Exits 0 when every contract holds, 1 otherwise.
set -uo pipefail

AURORA_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONTRACTS_DIR="$AURORA_DIR/contracts"
FAILURES=0
CHECKS=0

fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
ok()   { echo "  ok    $*"; CHECKS=$((CHECKS + 1)); }
skip() { echo "  skip  $*"; }

# Single-valued key.
value_of() { grep -E "^$1:" "$2" 2>/dev/null | head -1 | sed -E "s/^$1:[[:space:]]*//" | tr -d '\r'; }
# Repeated key, one value per line.
values_of() { grep -E "^$1:" "$2" 2>/dev/null | sed -E "s/^$1:[[:space:]]*//" | tr -d '\r'; }

# Every import statement in a source tree, as bare package paths.
imports_in() {
  grep -rhE "^import (static )?[a-zA-Z0-9_.]+;" "$1" --include='*.java' 2>/dev/null \
    | sed -E 's/^import (static )?//; s/;.*$//'
}

check_forbidden_imports() {
  local contract="$1"
  local layer src
  layer="$(value_of layer "$contract")"
  src="$AURORA_DIR/$(value_of source-root "$contract")"

  if [ ! -d "$src" ]; then
    skip "$layer: no sources at ${src#"$AURORA_DIR"/} (layer not created yet)"
    return
  fi

  local prefix hits escaped
  while IFS= read -r prefix; do
    [ -z "$prefix" ] && continue
    # Anchor at the start: 'android.' must match android.content.Context but not
    # a package such as myandroid.foo. Dots are escaped so they are literal.
    escaped="${prefix//./\\.}"
    hits="$(imports_in "$src" | grep -E "^$escaped" || true)"
    if [ -n "$hits" ]; then
      fail "$layer must not import '$prefix':"
      echo "$hits" | sed 's/^/          /'
    else
      ok "$layer: no import of $prefix"
    fi
  done < <(values_of forbid-import "$contract")
}

main() {
  echo "Aurora architecture test"
  echo "contracts: $CONTRACTS_DIR"
  echo
  local contract
  for contract in "$CONTRACTS_DIR"/*.contract; do
    echo "--- $(basename "$contract") ---"
    check_forbidden_imports "$contract"
    echo
  done

  echo "======================================"
  echo "checks passed: $CHECKS   failures: $FAILURES"
  if [ "$FAILURES" -eq 0 ]; then
    echo "ARCH TEST PASS"
    return 0
  fi
  echo "ARCH TEST FAIL"
  return 1
}

main "$@"
```

- [ ] **Step 2: Make it executable and run it**

```bash
chmod +x frameworks/base/aurora/tools/arch-test.sh
frameworks/base/aurora/tools/arch-test.sh
```

Expected: every line `ok`, `device.contract` reports `skip`, final line `ARCH TEST PASS`,
exit code 0.

- [ ] **Step 3: Prove the check actually fails on a violation**

A check that has never failed is not known to work. Temporarily add to
`runtime/java/aurora/runtime/AuroraRuntime.java`, directly under the existing import:

```java
import com.android.internal.util.Preconditions;
```

Run: `frameworks/base/aurora/tools/arch-test.sh`

Expected: `FAIL  runtime must not import 'com.android.internal.'` and exit code 1.

- [ ] **Step 4: Revert the deliberate violation**

```bash
git checkout frameworks/base/aurora/runtime/java/aurora/runtime/AuroraRuntime.java
frameworks/base/aurora/tools/arch-test.sh
```

Expected: `ARCH TEST PASS` again.

- [ ] **Step 5: Commit**

```bash
git add frameworks/base/aurora/tools/arch-test.sh
git commit -m "Sprint 02: arch-test.sh - forbidden import check"
```

---

## Task 4: arch-test.sh — Aurora layering check

Task 3 catches Android imports. This catches inverted Aurora dependencies, which is the
layering rule proper.

**Files:**
- Modify: `frameworks/base/aurora/tools/arch-test.sh`

- [ ] **Step 1: Add the layering check function**

Insert after `check_forbidden_imports`:

```bash
# Any aurora.* import must be either the layer's own package or explicitly allowed.
check_aurora_layering() {
  local contract="$1"
  local layer src own allowed imp violation=0
  layer="$(value_of layer "$contract")"
  src="$AURORA_DIR/$(value_of source-root "$contract")"
  own="$(value_of package-root "$contract")."

  [ -d "$src" ] || return

  allowed="$(values_of allow-aurora-import "$contract")"

  while IFS= read -r imp; do
    [ -z "$imp" ] && continue
    case "$imp" in aurora.*) ;; *) continue ;; esac
    case "$imp" in "$own"*) continue ;; esac

    local match=0 a
    while IFS= read -r a; do
      [ -z "$a" ] && continue
      case "$imp" in "$a"*) match=1; break ;; esac
    done <<< "$allowed"

    if [ "$match" -eq 0 ]; then
      fail "$layer imports '$imp', which no allow-aurora-import permits"
      violation=1
    fi
  done < <(imports_in "$src" | sort -u)

  [ "$violation" -eq 0 ] && ok "$layer: all aurora.* imports are within the allowed layers"
}
```

- [ ] **Step 2: Call it from `main`**

Change the loop body to:

```bash
    check_forbidden_imports "$contract"
    check_aurora_layering "$contract"
```

- [ ] **Step 3: Run it**

Run: `frameworks/base/aurora/tools/arch-test.sh`

Expected: `ok    runtime: all aurora.* imports are within the allowed layers`, and
`ARCH TEST PASS`.

- [ ] **Step 4: Prove it fails on an inverted dependency**

Temporarily add to `sdk/java/aurora/sdk/AuroraVersion.java`:

```java
import aurora.runtime.AuroraRuntime;
```

Run: `frameworks/base/aurora/tools/arch-test.sh`

Expected: `FAIL  sdk imports 'aurora.runtime.AuroraRuntime', which no allow-aurora-import permits`
and exit 1. Note this fires even though `sdk.contract` also lists `aurora.runtime.` under
`forbid-import` — the two checks overlap on purpose, because `forbid-import` is a finite
list and the whitelist catches layers nobody thought to forbid.

- [ ] **Step 5: Revert and confirm green**

```bash
git checkout frameworks/base/aurora/sdk/java/aurora/sdk/AuroraVersion.java
frameworks/base/aurora/tools/arch-test.sh
```

Expected: `ARCH TEST PASS`

- [ ] **Step 6: Commit**

```bash
git add frameworks/base/aurora/tools/arch-test.sh
git commit -m "Sprint 02: arch-test.sh - aurora layering check"
```

---

## Task 5: arch-test.sh — Soong dependency check

The import checks are defeated by anyone who adds `framework` to `static_libs`, because
then the import becomes legal. This check guards `Android.bp` itself.

**Files:**
- Modify: `frameworks/base/aurora/tools/arch-test.sh`

- [ ] **Step 1: Add block extraction and the dependency check**

```bash
# Print the Android.bp block whose name: field matches $1.
module_block() {
  awk -v want="\"$1\"" '
    /^[a-z_]+ \{/        { block = ""; inblock = 1 }
    inblock              { block = block $0 "\n" }
    inblock && /^\}/     { if (block ~ ("name: *" want)) printf "%s", block; inblock = 0 }
  ' "$AURORA_DIR/Android.bp"
}

check_soong_deps() {
  local contract="$1"
  local layer module block dep
  layer="$(value_of layer "$contract")"
  module="$(value_of module "$contract")"
  block="$(module_block "$module")"

  if [ -z "$block" ]; then
    skip "$layer: no Soong module named $module"
    return
  fi

  local bad=0
  while IFS= read -r dep; do
    [ -z "$dep" ] && continue
    if grep -qE "\"$dep\"" <<< "$block"; then
      fail "$module declares forbidden dependency '$dep'"
      bad=1
    fi
  done < <(values_of forbid-dep "$contract")
  [ "$bad" -eq 0 ] && ok "$module: no forbidden Soong dependency"

  # sdk_version must stay core_current until a contract says otherwise.
  if grep -q 'sdk_version: "core_current"' <<< "$block"; then
    ok "$module: sdk_version is core_current"
  else
    fail "$module: sdk_version is not core_current - the classpath guarantee is gone"
  fi
}
```

- [ ] **Step 2: Call it from `main`**

```bash
    check_forbidden_imports "$contract"
    check_aurora_layering "$contract"
    check_soong_deps "$contract"
```

- [ ] **Step 3: Run it**

Run: `frameworks/base/aurora/tools/arch-test.sh`

Expected: `ok    aurora-runtime: no forbidden Soong dependency`,
`ok    aurora-runtime: sdk_version is core_current`, `device.contract` reports `skip` for
the module, `ARCH TEST PASS`.

- [ ] **Step 4: Prove it fails when Android.bp is weakened**

Temporarily change `aurora-runtime` in `Android.bp`:

```
    static_libs: ["aurora-sdk", "framework"],
```

Run: `frameworks/base/aurora/tools/arch-test.sh`

Expected: `FAIL  aurora-runtime declares forbidden dependency 'framework'`, exit 1.

- [ ] **Step 5: Revert and confirm green**

```bash
git checkout frameworks/base/aurora/Android.bp
frameworks/base/aurora/tools/arch-test.sh
```

Expected: `ARCH TEST PASS`

- [ ] **Step 6: Commit**

```bash
git add frameworks/base/aurora/tools/arch-test.sh
git commit -m "Sprint 02: arch-test.sh - Soong dependency check"
```

---

## Task 6: Negative compile fixtures

The checks so far are textual. This task proves the *compiler* still rejects the forbidden
code, which is the guarantee the whole design rests on.

**Files:**
- Create: `frameworks/base/aurora/tests/arch/SdkImportsRuntime.java.txt`
- Create: `frameworks/base/aurora/tests/arch/RuntimeImportsPlatform.java.txt`
- Create: `frameworks/base/aurora/tests/arch/RuntimeImportsServer.java.txt`
- Create: `frameworks/base/aurora/tests/arch/RuntimeImportsInternal.java.txt`
- Modify: `frameworks/base/aurora/tools/arch-test.sh`

- [ ] **Step 1: Write the four fixtures**

`SdkImportsRuntime.java.txt` — the SDK must not see the runtime:

```java
// Negative fixture. MUST NOT COMPILE. Not part of any Soong module; the .java.txt
// suffix keeps it invisible to the build even if a glob is widened.
package aurora.sdk;

import aurora.runtime.AuroraRuntime;

public final class SdkImportsRuntime {
    AuroraRuntime mRuntime;
}
```

`RuntimeImportsPlatform.java.txt` — the runtime must not see the platform:

```java
// Negative fixture. MUST NOT COMPILE.
package aurora.runtime;

import aurora.platform.AuroraServiceRegistry;

public final class RuntimeImportsPlatform {
    AuroraServiceRegistry mRegistry;
}
```

`RuntimeImportsServer.java.txt` — the runtime must not see system server internals:

```java
// Negative fixture. MUST NOT COMPILE.
package aurora.runtime;

import com.android.server.SystemService;

public final class RuntimeImportsServer {
    SystemService mService;
}
```

`RuntimeImportsInternal.java.txt` — the runtime must not see hidden framework API:

```java
// Negative fixture. MUST NOT COMPILE.
package aurora.runtime;

import com.android.internal.util.Preconditions;

public final class RuntimeImportsInternal {
    Object mUnused = Preconditions.class;
}
```

- [ ] **Step 2: Add the negative-compile check to arch-test.sh**

```bash
# Locate a built jar for a module, if the tree has been built.
find_jar() {
  find "${ANDROID_BUILD_TOP:-.}/out/soong/.intermediates/frameworks/base/aurora/$1" \
       -name "$1.jar" -path "*javac*" 2>/dev/null | head -1
}

# Compile a fixture that MUST fail, and check it failed for the stated reason.
expect_compile_failure() {
  local fixture="$1" expect="$2" classpath="$3"
  local name tmp src
  name="$(basename "$fixture" .java.txt)"
  tmp="$(mktemp -d)"
  src="$tmp/$name.java"
  cp "$fixture" "$src"

  if javac -nowarn -cp "$classpath" -d "$tmp/out" "$src" >"$tmp/out.log" 2>&1; then
    fail "$name compiled, but the boundary requires it to fail"
  elif grep -q "$expect" "$tmp/out.log"; then
    ok "$name rejected by javac ($expect)"
  else
    fail "$name failed, but not for the expected reason '$expect':"
    sed 's/^/          /' "$tmp/out.log" | head -5
  fi
  rm -rf "$tmp"
}

check_negative_compiles() {
  local arch_dir="$AURORA_DIR/tests/arch"
  [ -d "$arch_dir" ] || { skip "no negative fixtures"; return; }

  local sdk_jar runtime_jar
  sdk_jar="$(find_jar aurora-sdk)"
  runtime_jar="$(find_jar aurora-runtime)"
  if [ -z "$sdk_jar" ] || [ -z "$runtime_jar" ]; then
    skip "negative compiles: aurora jars not built yet (run: m aurora-sdk aurora-runtime)"
    return
  fi

  # aurora.sdk sees nothing but itself.
  expect_compile_failure "$arch_dir/SdkImportsRuntime.java.txt" \
      "package aurora.runtime does not exist" "$sdk_jar"

  # aurora.runtime sees the sdk, and nothing above or beside it.
  expect_compile_failure "$arch_dir/RuntimeImportsPlatform.java.txt" \
      "package aurora.platform does not exist" "$sdk_jar:$runtime_jar"
  expect_compile_failure "$arch_dir/RuntimeImportsServer.java.txt" \
      "package com.android.server does not exist" "$sdk_jar:$runtime_jar"
  expect_compile_failure "$arch_dir/RuntimeImportsInternal.java.txt" \
      "package com.android.internal.util does not exist" "$sdk_jar:$runtime_jar"
}
```

- [ ] **Step 3: Call it once from `main`, after the per-contract loop**

```bash
  echo "--- negative compiles ---"
  check_negative_compiles
  echo
```

- [ ] **Step 4: Build the jars, then run**

```bash
cd /mnt/build/lineage
source build/envsetup.sh && lunch lineage_sdk_phone_x86_64-bp4a-userdebug
m aurora-sdk aurora-runtime
frameworks/base/aurora/tools/arch-test.sh
```

Expected: four `ok    ... rejected by javac ...` lines, then `ARCH TEST PASS`.

- [ ] **Step 5: Prove the negative check itself works**

Temporarily edit `RuntimeImportsServer.java.txt` to remove the forbidden import and the
field, leaving a class that compiles cleanly.

Run: `frameworks/base/aurora/tools/arch-test.sh`

Expected: `FAIL  RuntimeImportsServer compiled, but the boundary requires it to fail`

- [ ] **Step 6: Revert and confirm green**

```bash
git checkout frameworks/base/aurora/tests/arch/RuntimeImportsServer.java.txt
frameworks/base/aurora/tools/arch-test.sh
```

Expected: `ARCH TEST PASS`

- [ ] **Step 7: Commit**

```bash
git add frameworks/base/aurora/tests/arch/ frameworks/base/aurora/tools/arch-test.sh
git commit -m "Sprint 02: negative compile fixtures for the layer boundary"
```

---

## Task 7: Harden Android.bp with visibility

Soong cannot express "runtime may not depend on platform", but it can express who is
allowed to depend on a module at all. This stops code outside Aurora from reaching into
the layers.

**Files:**
- Modify: `frameworks/base/aurora/Android.bp`

- [ ] **Step 1: Add visibility to the three libraries**

Add to each of `aurora-sdk`, `aurora-runtime` and `aurora-platform`:

```
    visibility: [
        "//frameworks/base/aurora:__subpackages__",
    ],
```

Leave `aurora-platform-tests` alone; a test module needs no visibility list.

Note for Sprint 03: when Aurora is installed into a product, `aurora-platform` will need
its visibility widened to the product that installs it. `aurora-sdk` and `aurora-runtime`
must stay restricted.

- [ ] **Step 2: Rebuild to confirm nothing broke**

```bash
m aurora-sdk aurora-runtime aurora-platform aurora-platform-tests
```

Expected: exit 0.

- [ ] **Step 3: Run the arch test**

Run: `frameworks/base/aurora/tools/arch-test.sh`

Expected: `ARCH TEST PASS`

- [ ] **Step 4: Commit**

```bash
git add frameworks/base/aurora/Android.bp
git commit -m "Sprint 02: restrict Aurora module visibility to the aurora package"
```

---

## Task 8: Documentation and sprint bookkeeping

**Files:**
- Modify: `frameworks/base/aurora/README.md`
- Modify: `README.md` (repository root)

- [ ] **Step 1: Add an "Architecture enforcement" section to the module README**

Insert after the `Dependency` section:

```markdown
## Architecture enforcement

The layering rules are not a convention; they are checked.

```bash
frameworks/base/aurora/tools/arch-test.sh
```

Enforcement has two independent parts. The compiler is the real barrier:
`sdk_version: "core_current"` keeps `android.*`, `com.android.server.*` and
`com.android.internal.*` off the classpath, so a forbidden import cannot compile. That
guarantee can be removed by a single edit to `Android.bp`, which is what `arch-test.sh`
exists to catch — it re-checks the rules declared in `contracts/`, and compiles negative
fixtures under `tests/arch/` that must fail.

Change a rule by editing the relevant `.contract` file. Nothing else reads them.
```

- [ ] **Step 2: Correct the Future Extension section**

The existing text describes Sprint 02 as wiring Aurora into `SystemServer`. That is now
Sprint 03. Renumber, and add before it:

```markdown
**Sprint 02 — Architecture boundary.** Declare the layer rules in `contracts/`, enforce
them with `tools/arch-test.sh`, and prove the compiler still rejects forbidden imports with
negative fixtures. Done before the system wiring on purpose: once `aurora.platform` gains
access to `framework` in Sprint 03, the classpath stops protecting the lower layers by
accident, so the tripwire has to exist first.
```

- [ ] **Step 3: Update the repository root README status table**

```markdown
**Sprint 02 — Architecture boundary: complete.**

| Exit criterion | Result |
|---|---|
| `arch-test.sh` PASS | All contracts hold; four negative fixtures rejected by javac |
```

- [ ] **Step 4: Run the full check once more**

```bash
frameworks/base/aurora/tools/arch-test.sh
m aurora-platform-tests && atest aurora-platform-tests
```

Expected: `ARCH TEST PASS`, and 25 unit tests still passing.

- [ ] **Step 5: Commit and push**

```bash
git add frameworks/base/aurora/README.md README.md
git commit -m "Sprint 02: document architecture enforcement"
git push origin main
```

- [ ] **Step 6: Stop the build VM**

```bash
gcloud compute instances stop instance-20260731-135250 --zone asia-southeast1-b
```

---

## Exit Criteria

| Criterion | How it is verified |
|---|---|
| `arch-test.sh` PASS | Task 8 Step 4 — exit code 0 |
| Each check demonstrably fails on a violation | Tasks 3.3, 4.4, 5.4, 6.5 each introduce a real violation and confirm exit 1 |
| Contracts exist for all four layers | Task 2 |
| Build still passes | Task 7 Step 2, Task 8 Step 4 |
| Existing unit tests unaffected | Task 8 Step 4 — 25 passing |

## Deliberately out of scope

**Wiring Aurora into `SystemServer`.** That is Sprint 03. Doing it here would mean changing
boot behaviour in the same sprint that builds the tool meant to police it.

**Creating the `aurora.device` layer.** `device.contract` records the rules; no module is
created. With one target device there is nothing real to abstract, and guessing now means
guessing wrong.

**Hooking `arch-test.sh` into the Soong build graph.** Making the build itself fail on a
violation is worth doing, but it needs a `genrule` or a test module and would slow every
build. Leave it as a script until it has proven itself; revisit once Sprint 03 makes the
boundary genuinely fragile.
