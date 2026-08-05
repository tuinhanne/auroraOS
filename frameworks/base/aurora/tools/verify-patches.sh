#!/usr/bin/env bash
# The patch gate. ADR-011, Sprint 03 Phase A.
#
#   bash frameworks/base/aurora/tools/verify-patches.sh
#
# ## Why this gate is calibrated before it is trusted
#
# It is the first assertion in this repository about a class of artifact that has never existed
# here, so RULE-018 binds it: until it has been seen to refuse, a green run says nothing. Gate 2
# is that calibration, and it runs whether or not there are any real patches - a gate that only
# ever agreed would be indistinguishable from one that agrees with everything.
#
# ## Why it self-tests on a scratch repository
#
# Gate 3 proves that apply-patches.sh restores before applying, by building a two-commit git
# repository in a temporary directory and applying the same patch twice. It could have been proved
# against a real AOSP file, but that would need a real patch, and a patch written so a gate has
# something to chew on is exactly the artifact Sprint 06C.0 forbids:
#
#   No production code may exist whose only purpose is to create a subject for an assertion.
#
# A patch is production. So acceptance against a real file is proved by the first patch that exists
# for its own reasons, and the mechanism is proved here against a tree nobody ships.
#
# ## Where this can run
#
# Gates 0 and 1 need the AOSP checkout and are VM-only; they say so on a workstation rather than
# failing where they cannot see their input, which is the mirror of gate 6 in
# verify-motion-evidence.sh. Gates 2 and 3 need nothing but git and run anywhere.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AURORA="$(cd "$HERE/.." && pwd)"
REPO_ROOT="$(cd "$AURORA/../../.." && pwd)"

# The AOSP checkout. On the VM the repository's trees are rsynced into it, so it is the parent of
# frameworks/. On a workstation it does not exist.
CHECKOUT="${AURORA_CHECKOUT:-/mnt/build/lineage}"
WITNESS="$AURORA/tests/patches/witness-cannot-apply.patch"

# Where the patches are, which is deliberately *not* inside the checkout.
#
# ADR-011 says the checkout consumes patches rather than containing them, so `patches/` is never
# rsynced into the AOSP tree - it is staged beside it and applied. That leaves two real locations
# and no third:
#
#   workstation   the repository itself
#   VM            /mnt/build/patches, refreshed from the repository on every sync
#
# The VM copy is a cache of the repository and never a source: `vm-apply-code.sh` replaces it
# wholesale from the archive that just arrived, so nothing can accumulate there.
if [ -n "${AURORA_PATCHES:-}" ]; then
  PATCHES="$AURORA_PATCHES"
elif [ -d "$REPO_ROOT/patches" ]; then
  PATCHES="$REPO_ROOT/patches"
else
  PATCHES="/mnt/build/patches"
fi

FAILURES=0
pass() { echo "  ok    $1"; }
fail() { echo "  FAIL  $1"; FAILURES=$((FAILURES + 1)); }
note() { echo "  note  $1"; }

echo "=== 0. Every patch names a git project that exists ==="
if [ ! -d "$CHECKOUT" ]; then
  CHECKOUT_PRESENT=0
  note "no AOSP checkout at $CHECKOUT, so gates 0 and 1 are VM-only and did not run"
else
  CHECKOUT_PRESENT=1
  echo "  note  patches: $PATCHES"
  if [ ! -d "$PATCHES" ]; then
    fail "no patches directory at $PATCHES; sync has not run, or ADR-011's staging step is missing"
  else
    mapfile -t ALL < <(find "$PATCHES" -name '*.patch' -type f | sort)
    if [ "${#ALL[@]}" -eq 0 ]; then
      pass "0 patches - Phase A's expected state, and stated rather than passed over"
    else
      BAD=0
      for P in "${ALL[@]}"; do
        REL="${P#"$PATCHES"/}"
        PROJ="$(dirname "$REL")"
        if [ -d "$CHECKOUT/$PROJ/.git" ]; then
          pass "$REL -> $PROJ"
        else
          fail "$REL names $PROJ, which is not a git project in the checkout"
          BAD=$((BAD + 1))
        fi
      done
      [ "$BAD" -eq 0 ] && pass "${#ALL[@]} patch(es), every project resolved"
    fi
  fi
fi

echo
echo "=== 1. Every patch applies to a freshly restored tree ==="
if [ "$CHECKOUT_PRESENT" -eq 0 ]; then
  note "VM-only, skipped for the same reason as gate 0"
else
  OUT=$(bash "$HERE/apply-patches.sh" "$CHECKOUT" "$PATCHES" 2>&1)
  RC=$?
  echo "$OUT" | sed 's/^/    /'
  if [ "$RC" -eq 0 ]; then
    pass "apply-patches.sh restored and applied without a failure"
  else
    fail "apply-patches.sh reported a failure (rc=$RC)"
  fi
fi

echo
echo "=== 2. The gate can refuse (RULE-018) ==="
# Calibration. The witness targets a file no checkout contains, so it must fail everywhere - and
# a scratch repository is enough to ask, which keeps this gate honest on a workstation too.
if [ ! -f "$WITNESS" ]; then
  fail "the declared witness is missing: $WITNESS"
else
  W=$(mktemp -d)
  git -C "$W" init -q 2>/dev/null
  git -C "$W" -c user.email=a@b -c user.name=c commit -q --allow-empty -m base 2>/dev/null
  if git -C "$W" apply --check "$WITNESS" 2>/dev/null; then
    fail "the witness applied cleanly; this gate cannot tell a bad patch from a good one"
  else
    pass "witness-cannot-apply.patch was refused, as it must be"
  fi
  rm -rf "$W"
fi

echo
echo "=== 3. Applying twice equals applying once ==="
# Built here rather than borrowed from AOSP, so this proves the mechanism without any patch that
# ships. See the header.
S=$(mktemp -d)
mkdir -p "$S/checkout/proj" "$S/patches/proj"
(
  cd "$S/checkout/proj" || exit 1
  git init -q
  # No line-ending translation. On a workstation git would rewrite LF to CRLF on checkout, and the
  # comparison below would fail on a difference this gate is not about. The VM never sees it, which
  # is exactly why it is worth pinning here rather than discovering later.
  git config core.autocrlf false
  git config core.eol lf
  printf 'alpha\nbeta\ngamma\n' > f.txt
  git add f.txt
  git -c user.email=a@b -c user.name=c commit -q -m base
)
cat > "$S/patches/proj/0001-change-beta.patch" <<'PATCH'
diff --git a/f.txt b/f.txt
--- a/f.txt
+++ b/f.txt
@@ -1,3 +1,3 @@
 alpha
-beta
+BETA
 gamma
PATCH

bash "$HERE/apply-patches.sh" "$S/checkout" "$S/patches" > /dev/null 2>&1
ONCE=$(cat "$S/checkout/proj/f.txt")
bash "$HERE/apply-patches.sh" "$S/checkout" "$S/patches" > /dev/null 2>&1
TWICE=$(cat "$S/checkout/proj/f.txt")

if [ "$ONCE" != "$TWICE" ]; then
  fail "the tree differs after a second apply; the restore step is not doing its job"
elif [ "$ONCE" != "$(printf 'alpha\nBETA\ngamma')" ]; then
  fail "the patch did not apply as written: got [$ONCE]"
else
  pass "one apply and two applies produce the same tree"
fi
rm -rf "$S"

echo
echo "======================================"
if [ "$FAILURES" -eq 0 ]; then
  if [ "$CHECKOUT_PRESENT" -eq 1 ]; then
    echo "PATCH GATE PASS"
  else
    echo "PATCH GATE PASS (partial: the checkout gates did not run on this machine)"
  fi
  echo
  echo "Not checked here, and deliberately so:"
  echo "  - whether a patch is a good idea. A patch that applies cleanly and is wrong is review's"
  echo "  - whether authority ran the right way. ADR-011 part 3 is not grep-decidable: the same"
  echo "    patch file comes out of an authored diff and an exported workspace edit"
  exit 0
fi
echo "PATCH GATE FAIL ($FAILURES)"
exit 1
