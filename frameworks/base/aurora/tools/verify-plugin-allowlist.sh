#!/usr/bin/env bash
# Aurora - artifact contract check for config_pluginAllowlist. ADR-015.
#
# The gate does not own the truth. It reads contracts/artifact/systemui-plugin-allowlist.contract
# and compares the declared set against what a real build produced. Changing what Aurora expects
# means editing the contract, which is a decision and is reviewed as one; editing this file only
# changes how the comparison is performed.
#
# Build-time only, with no device in it. That is licensed by a measurement rather than assumed:
# Sprint 09 Task 3.0b showed `cmd overlay lookup` on a booted emulator returns exactly what
# `aapt2 dump` reports for this resource. If the resolution path changes - a mutable overlay, a
# fabricated .frro, a /product/overlay/partition_order.xml appearing - that licence needs
# re-measuring and this script's claim shrinks with it.
#
# Deliberately NOT set -u: envsetup.sh does not survive it, and this script sources it.
# Deliberately no `2>/dev/null` on the build: hiding a tool's diagnostic is how three runs of the
# Task 3.0 measurement reported nothing useful.

AURORA_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONTRACT="$AURORA_DIR/contracts/artifact/systemui-plugin-allowlist.contract"
TOP="${ANDROID_BUILD_TOP:-$(cd "$AURORA_DIR/../../.." && pwd)}"
WORK="${TMPDIR:-/tmp}/aurora-allowlist.$$"
mkdir -p "$WORK"
trap 'rm -rf "$WORK"' EXIT

# Five verdicts, not two. Sprint 09 Task 3.0 ran three times before producing any evidence about
# the subject at all; folding those into FAIL would have reported a contract violation on the
# strength of a wrong lunch target.
verdict() { echo; echo "VERDICT=$1${2:+  ($2)}"; }
die()     { verdict "$1" "$2"; exit 2; }

values_of() { sed -n "s/^[[:space:]]*$1:[[:space:]]*//p" "$2" | sed 's/[[:space:]]*$//'; }
value_of()  { values_of "$1" "$2" | head -1; }

[ -f "$CONTRACT" ] || die NO_CONTRACT "$CONTRACT does not exist"
cd "$TOP" || die NO_TREE "cannot enter $TOP"

echo "contract: ${CONTRACT#"$TOP"/}"
echo "tree:     $TOP"

values_of expect-entry "$CONTRACT" | sort -u > "$WORK/expected"
[ -s "$WORK/expected" ] || die EMPTY_CONTRACT "the contract declares no expect-entry lines"

RES="$(value_of resource "$CONTRACT")"
ARRAY="${RES#array/}"

# ---- build ----------------------------------------------------------------
if [ -z "${TARGET_PRODUCT:-}" ]; then
  echo
  echo "== lunch =="
  [ -f build/envsetup.sh ] || die NO_TREE "build/envsetup.sh not found under $TOP"
  source build/envsetup.sh
  lunch "${AURORA_LUNCH_TARGET:-lineage_sdk_phone_x86_64-bp4a-userdebug}"
  [ -n "${TARGET_PRODUCT:-}" ] || die LUNCH_FAILED "TARGET_PRODUCT is empty after lunch"
fi
echo "product:  $TARGET_PRODUCT  release=${TARGET_RELEASE:-?}"

if [ "${AURORA_SKIP_BUILD:-0}" != "1" ]; then
  echo
  echo "== m SystemUI =="
  m SystemUI || die BUILD_FAILED "m SystemUI returned non-zero"
fi

# ---- locate the subject ---------------------------------------------------
APK=""
for pattern in "$(value_of subject-glob "$CONTRACT")" $(values_of also-check "$CONTRACT"); do
  for candidate in $pattern; do
    [ -f "$candidate" ] || continue
    APK="$candidate"
    break 2
  done
done
[ -n "$APK" ] || die NO_ARTIFACT "no built artifact matched the contract's subject-glob"

AAPT2="$(find out/host -name aapt2 -type f 2>/dev/null | head -1)"
[ -n "$AAPT2" ] || die NO_TOOL "aapt2 was not found under out/host"
echo
echo "subject:  $APK"

# ---- read the array out of the artifact -----------------------------------
# aapt2 prints the block as:  resource 0x… array/<name>\n  () (array) size=N\n    ["a", "b"]
"$AAPT2" dump resources "$APK" 2>/dev/null \
  | awk -v n="array/$ARRAY" '$0 ~ n {f=1; next} f && /^ *resource /{exit} f{print}' \
  | grep -oE '"[^"]+"' | tr -d '"' | sort -u > "$WORK/actual"

# An empty read is the instrument failing, not the subject. These are different facts and the
# script must never report the first as the second.
[ -s "$WORK/actual" ] || die UNREADABLE "$ARRAY was not found in the dump of $(basename "$APK")"

# ---- compare as a set -----------------------------------------------------
echo
echo "expected (contract):"; sed 's/^/    /' "$WORK/expected"
echo "actual   (artifact):"; sed 's/^/    /' "$WORK/actual"

MISSING="$(comm -23 "$WORK/expected" "$WORK/actual")"
EXTRA="$(comm -13 "$WORK/expected" "$WORK/actual")"

if [ -z "$MISSING" ] && [ -z "$EXTRA" ]; then
  verdict PASS "exact set match"
  exit 0
fi

echo
[ -n "$MISSING" ] && { echo "missing from the artifact:"; echo "$MISSING" | sed 's/^/    /'; }
[ -n "$EXTRA"   ] && { echo "present but not declared:";  echo "$EXTRA"   | sed 's/^/    /'; }

# Both directions matter and they mean different things. Losing drops Aurora; winning can drop
# another project's plugin, which is quieter and worse.
if [ -n "$EXTRA" ]; then
  echo
  echo "  An undeclared entry means AOSP or Lineage changed the array. That is a decision, not a"
  echo "  merge conflict: someone must say whether Aurora carries it forward, and record it in"
  echo "  ${CONTRACT#"$TOP"/}."
fi

verdict FAIL "the artifact does not satisfy the contract"
exit 1
