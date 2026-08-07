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

AAPT2="$(find out/host -name aapt2 -type f 2>/dev/null | head -1)"
[ -n "$AAPT2" ] || die NO_TOOL "aapt2 was not found under out/host"

# aapt2 prints the block as:  resource 0x… array/<name>\n  () (array) size=N\n    ["a", "b"]
array_from() {
  "$AAPT2" dump resources "$1" 2>/dev/null \
    | awk -v n="array/$ARRAY" '$0 ~ n {f=1; next} f && /^ *resource /{exit} f{print}' \
    | grep -oE '"[^"]+"' | tr -d '"'
}

# ---- AURORA: the artifact that actually wins ------------------------------
AURORA_APK=""
for candidate in $(value_of aurora-artifact "$CONTRACT"); do
  [ -f "$candidate" ] && { AURORA_APK="$candidate"; break; }
done
[ -n "$AURORA_APK" ] || die NO_ARTIFACT "no built artifact matched aurora-artifact"
echo
echo "aurora:   $AURORA_APK"
array_from "$AURORA_APK" | sort -u > "$WORK/aurora"
[ -s "$WORK/aurora" ] || die UNREADABLE "$ARRAY not found in $(basename "$AURORA_APK")"

# ---- UPSTREAM: what AOSP and Lineage declare, unioned ---------------------
: > "$WORK/upstream"
FOUND_UPSTREAM=0
while IFS= read -r pattern; do
  [ -z "$pattern" ] && continue
  for candidate in $pattern; do
    [ -f "$candidate" ] || continue
    echo "upstream: $candidate"
    array_from "$candidate" >> "$WORK/upstream"
    FOUND_UPSTREAM=1
  done
done < <(values_of upstream-artifact "$CONTRACT")
[ "$FOUND_UPSTREAM" -eq 1 ] || die NO_ARTIFACT "no built artifact matched any upstream-artifact"
sort -u -o "$WORK/upstream" "$WORK/upstream"
[ -s "$WORK/upstream" ] || die UNREADABLE "$ARRAY not found in any upstream artifact"

values_of aurora-owns-entry "$CONTRACT" | sort -u > "$WORK/ours"
cat "$WORK/upstream" "$WORK/ours" | sort -u > "$WORK/union"

echo
echo "declared (contract):"; sed 's/^/    /' "$WORK/expected"
echo "aurora   (winning artifact):"; sed 's/^/    /' "$WORK/aurora"
echo "upstream (AOSP + Lineage):"; sed 's/^/    /' "$WORK/upstream"
echo "aurora's own entries:"; sed 's/^/    /' "$WORK/ours"

RC=0

# 1. The tautological check. Kept because it still catches an RRO edited without the contract.
if ! diff -q "$WORK/expected" "$WORK/aurora" >/dev/null; then
  echo
  echo "MISMATCH  the winning artifact does not match the contract:"
  comm -23 "$WORK/expected" "$WORK/aurora" | sed 's/^/    declared but absent from the RRO: /'
  comm -13 "$WORK/expected" "$WORK/aurora" | sed 's/^/    in the RRO but not declared:     /'
  RC=1
fi

# 2. The real gate. expect-entry must be exactly upstream plus what Aurora owns.
if ! diff -q "$WORK/expected" "$WORK/union" >/dev/null; then
  echo
  echo "DRIFT  the declared set is not (upstream + aurora's own):"
  comm -13 "$WORK/expected" "$WORK/union" | sed 's/^/    upstream has it, Aurora does not carry it: /'
  comm -23 "$WORK/expected" "$WORK/union" | sed 's/^/    Aurora declares it, nobody upstream has:  /'
  echo
  echo "  An upstream entry Aurora does not carry means AOSP or Lineage changed the array. Because"
  echo "  an overlay REPLACES the array, Aurora's RRO would silently drop it. That is a decision,"
  echo "  not a merge conflict: someone must say whether Aurora carries it forward, and record the"
  echo "  answer in ${CONTRACT#"$TOP"/}."
  RC=1
fi

if [ "$RC" -eq 0 ]; then
  verdict PASS "the winning artifact matches the contract, and the contract is upstream + aurora's own"
  exit 0
fi
verdict FAIL "see the mismatches above"
exit 1
