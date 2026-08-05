#!/usr/bin/env bash
# Motion evidence gate: the physics contract is stated, enforced, and shipped with no solver.
#
#   bash frameworks/base/aurora/tools/verify-motion-evidence.sh
#
# Every gate fails loudly when it matches nothing. Sprint 06A shipped a verify script whose
# greps found nothing on an empty directory and reported clean, so each count here is checked
# against an expectation rather than against zero.
set -uo pipefail

AURORA="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$AURORA" || exit 1
DOCS="$AURORA/../../../docs"

FAILURES=0
pass() { echo "  ok    $1"; }
fail() { echo "  FAIL  $1"; FAILURES=$((FAILURES + 1)); }
note() { echo "  note  $1"; }

# Reduce Kotlin to the lines that carry behaviour. Gate 3 needs this: the old threshold names
# survive on purpose in three KDoc passages that explain what was replaced and why, which is the
# most useful comment in the file. A gate grepping raw source would fail forever on a clean tree
# - turning a semantic guard into a text lint, and training people to ignore it.
stripComments() {
  grep -vE '^[[:space:]]*(\*|//|/\*)' \
    | sed -E '/"/! s@[[:space:]]*//.*$@@'
}

echo "=== 1. Architecture wall ==="
if bash "$AURORA/tools/arch-test.sh" >/tmp/06b0-arch.log 2>&1; then
  pass "arch-test.sh passes ($(grep -oE 'checks passed: [0-9]+' /tmp/06b0-arch.log | tail -1))"
else
  fail "arch-test.sh"
  tail -15 /tmp/06b0-arch.log | sed 's/^/          /'
fi

echo
echo "=== 2. Retired in Sprint 06B.3 ==="
# "Only the sprints that wrote a solver have one." Recorded rather than deleted, because a gate
# that vanishes is indistinguishable from one nobody noticed had stopped running.
#
# It made three claims and each was overtaken:
#
#   - no Snap or Fling sampler in production. Neither can exist now. FlingFactory produces a
#     DecaySpec, and Sprint 06B.3 found snap is a selection followed by a spring, so SnapSpec left
#     the AnimationSpec hierarchy (ADR-009) and there is nothing for a SnapSampler to sample.
#   - SpringSampler and DecaySampler are present. Now enforced by the compiler: samplerFor's `when`
#     is exhaustive over a sealed hierarchy with no `else`, so removing a branch fails the build
#     rather than this gate. A type error beats a grep.
#   - the broken fixtures never leave the test tree. **This one was still live**, and it moved into
#     gate 4 rather than retiring - it belongs to RULE-015 by role, and it needed rewriting anyway.
#     Gate 2 grepped a hardcoded list of five class names which had not been extended when Sprint
#     06B.1 added four spring witnesses, so it covered none of them while `WrongSprings.kt` carried
#     a KDoc line promising it did. A normative claim nothing enforced, which is precisely the
#     failure 06B.1 caught elsewhere.
note "gate 2 retired in Sprint 06B.3; its one live check is now gate 4d, and derived not hardcoded"

# The tree-sanity check gate 2 opened with, kept because gate 4d greps the same directories and a
# grep over a directory that is not there reports clean.
SRC_COUNT=$(find sdk runtime platform -name '*.kt' 2>/dev/null | wc -l)
if [ "$SRC_COUNT" -lt 20 ]; then
  fail "only $SRC_COUNT Kotlin sources found; the tree is not where this script thinks it is"
else
  pass "$SRC_COUNT Kotlin sources under sdk/ runtime/ platform/"
fi

echo
echo "=== 3. The replaced thresholds are gone from code ==="
LIVE=$(for f in $(grep -rl 'restDelta\|restVelocity' sdk runtime platform tests 2>/dev/null); do
         if stripComments < "$f" | grep -q 'restDelta\|restVelocity'; then echo "$f"; fi
       done)
DOCUMENTED=$(grep -rl 'restDelta\|restVelocity' sdk runtime platform tests 2>/dev/null | wc -l)
if [ -n "$LIVE" ]; then
  fail "restDelta or restVelocity still referenced in code:"
  printf '%s\n' "$LIVE" | sed 's/^/          /'
else
  pass "no code references restDelta or restVelocity"
  [ "$DOCUMENTED" -gt 0 ] && note "$DOCUMENTED file(s) still name them in comments, which is why this gate strips first"
fi

echo
echo "=== 4. RULE-015: every assertion has a witness that violates it ==="
# Rewritten in Sprint 06B.3, once Question 3 closed. The argument is in docs/evidence-model.md.
#
# This gate used to identify a witness by its syntactic form - `class X` in BrokenSamplers.kt - and
# an assertion by which of two named files it sat in. Both identified a thing by the shape the
# *first* subject happened to take. RULE-015 says "one deliberately wrong subject"; it names no
# file and no shape, and never did.
#
# The counterexample was already in the tree. Three integration witnesses are functions and the
# policy witnesses are vals holding SAM conversions - rewrite one as an object expression and every
# grep's answer changes while its red set, its test and its proof do not. All were invisible here
# while being exactly what the rule describes.
#
# So both columns stop asking about shape. What a manifest can honestly check is that it is
# complete and that its names resolve; whether an artifact does any witnessing is not
# grep-decidable and stays with review, which the closing note says.
#
# Calibrated before being trusted, on 2026-08-05, because a rewritten gate that has only ever been
# seen green is the failure this project keeps building gates to prevent. Each check was shown to
# refuse by breaking the tree one way at a time and restoring it:
#
#   a. an assertion defined under tests/ and left out of the manifest   -> red, names it
#   b. a declaration naming an assertion that does not exist            -> red, names both halves
#   c. a declaration naming a witness declared nowhere                  -> red, names it
#   d. a witness declared under runtime/                                -> red, names it and the file
#
# Two of those cover holes the previous version had. In (a) the assertion was added to a file the
# old gate did not read, so it would have passed unnoticed. In (d) the escaping witness was
# UndampedEnvelopeSpring, which gate 2's hardcoded list did not contain and never had.
SELFTEST=tests/java/aurora/testing/animation/ContractSelfTest.kt
TESTS=tests

PAIRS=$(grep -oE 'assert[A-Za-z]+[[:space:]]+<-[[:space:]]+[A-Za-z-]+' "$SELFTEST" 2>/dev/null)
DECLARED=$(printf '%s\n' "$PAIRS" | awk '{print $1}' | sort -u)
# Every assertion in the test tree, wherever it lives and whatever shape it is written in.
#
# assertRejects is excluded by name, not by location. It is the harness's inversion helper - it
# asserts that some *other* assertion refused - so it has no subject of its own and nothing to
# witness. Excluding one named helper is a statement about that helper; excluding a directory would
# be the mistake this gate was just rewritten to stop making.
DEFINED=$(grep -rhoE '\bfun (assert[A-Za-z]+)' "$TESTS" --include='*.kt' 2>/dev/null \
            | awk '{print $2}' | grep -vx 'assertRejects' | sort -u)

if [ -z "$PAIRS" ] || [ -z "$DEFINED" ]; then
  fail "found no pairing block or no assertions; the gate is looking in the wrong place"
else
  pass "$(printf '%s\n' "$DECLARED" | wc -l) pairing(s) declared, $(printf '%s\n' "$DEFINED" | wc -l) assertion(s) defined"

  # a. no assertion may exist without being declared
  MISSING=$(comm -23 <(printf '%s\n' "$DEFINED") <(printf '%s\n' "$DECLARED"))
  if [ -n "$MISSING" ]; then
    fail "assertion(s) with no RULE-015 declaration:"
    printf '%s\n' "$MISSING" | sed 's/^/          /'
  else
    pass "every assertion is declared"
  fi

  # b. no declaration may name an assertion that does not exist
  PHANTOM=$(comm -13 <(printf '%s\n' "$DEFINED") <(printf '%s\n' "$DECLARED"))
  if [ -n "$PHANTOM" ]; then
    fail "declaration(s) naming an assertion that does not exist:"
    printf '%s\n' "$PHANTOM" | sed 's/^/          /'
  else
    pass "every declaration names a real assertion"
  fi

  # c. every named witness must resolve to a declaration somewhere in the test tree, in any shape.
  # A class, a function, an object and a val holding a lambda are representations of one role, and
  # a gate recognising one of them would confuse a representation for the thing.
  #
  # The summary line is conditional: a green "every witness resolves" printed under a red one
  # naming a missing witness contradicts itself, and a reader skimming for green would believe
  # the wrong half.
  BAD_WITNESSES=0
  while read -r LEFT _ RIGHT; do
    [ -z "${RIGHT:-}" ] && continue
    if [ "$RIGHT" = "solver-tier" ]; then
      note "$LEFT is solver-tier and needs no witness (RULE-015 binds the contract tier)"
    elif ! grep -rqE "\b(class|object|fun|val)[[:space:]]+$RIGHT\b" "$TESTS" --include='*.kt'; then
      fail "$LEFT names witness $RIGHT, which is declared nowhere under $TESTS/"
      BAD_WITNESSES=$((BAD_WITNESSES + 1))
    fi
  done <<< "$PAIRS"
  [ "$BAD_WITNESSES" -eq 0 ] && pass "every declared witness resolves or is an explicit exemption"

  # d. no witness may be declared outside the test tree.
  #
  # Moved here from gate 2 when that gate retired, and rewritten on the way. The witness set is
  # derived from two declarations rather than hardcoded: every name the manifest pairs, plus every
  # type declared in the fixture files. Adding a witness to either extends this check without
  # anyone having to remember to - which is what the old hardcoded list failed to do for four
  # sprints.
  #
  # Location is the subject here, so grepping by location is not the mistake Question 3 named.
  # Containment *is* a property of where a thing is written. What that question forbids is
  # inferring a witness's identity from its shape, and the set below is read from declarations.
  FIXTURE_FILES="$TESTS/java/aurora/testing/animation/BrokenSamplers.kt"
  FIXTURE_FILES="$FIXTURE_FILES $TESTS/java/aurora/testing/animation/WrongSprings.kt"
  # LinearSampler is excluded: it lives among the fixtures but is the correct baseline the self
  # test compares them against, not a deliberately wrong subject.
  WITNESSES=$( { printf '%s\n' "$PAIRS" | awk '{print $3}'
                 grep -hoE '^class [A-Za-z]+' $FIXTURE_FILES 2>/dev/null | awk '{print $2}'
               } | grep -vx 'solver-tier' | grep -vx 'LinearSampler' | sort -u )
  ESCAPED=0
  for W in $WITNESSES; do
    HITS=$(grep -rlE "\b(class|object|fun|val)[[:space:]]+$W\b" sdk runtime platform \
             --include='*.kt' 2>/dev/null)
    if [ -n "$HITS" ]; then
      fail "witness $W is declared in production code:"
      printf '%s\n' "$HITS" | sed 's/^/          /'
      ESCAPED=$((ESCAPED + 1))
    fi
  done
  [ "$ESCAPED" -eq 0 ] &&
    pass "all $(printf '%s\n' "$WITNESSES" | wc -l) witnesses are confined to $TESTS/"
fi
# The honest limit of a manifest, and the reason the shape check was not replaced by a cleverer
# one. RULE-015 also says "no fixture that nothing uses"; that a name resolves is not that it
# witnesses, and neither half is grep-decidable.
note "whether each pair is exercised, and whether a witness's red set is what it claims, is"
note "enforced by review; grep can see that the manifest is complete, and nothing more"

echo
echo "=== 5. Retired in Sprint 06B.3 ==="
# "Only the solved families have samplers." Recorded rather than deleted, and the record matters
# more here than anywhere else in this file, because this gate has the most instructive history.
#
# It watched which families `samplerFor` refused. Sprint 06B.1 rewrote it after finding it could
# not fail: it counted `UnsupportedOperationException(` occurrences and required exactly one, so
# adding `is SpringSpec -> SpringSampler(spec)` above the throw took springs out of the refused set
# without changing the count. Green while the invariant it guarded had changed. Rewritten to name
# the families instead, it then went red exactly when Sprint 06B.2 solved decay - the gate working.
#
# It retires because its subject is gone rather than because it stopped mattering. Sprint 06B.3
# found snap is a target selection followed by a spring, so SnapSpec left the AnimationSpec
# hierarchy (ADR-009), `samplerFor` refuses nothing, and there is no refused set left to watch.
# Both surviving halves were also inherited by something stronger: the `when` is exhaustive over a
# sealed hierarchy with no `else`, so a family losing its sampler, or a spec kind arriving without
# one, is now a compile error rather than a grep that has to be remembered.
#
# What no compiler can check is that the `else` never comes back, since adding one is legal Kotlin
# that silently restores the hole. That is asserted instead by
# `AnimationLifecycleTest.everySpecTheEngineCanReceiveHasASolver`, which builds one animation of
# every kind and would fail if any were refused.
note "gate 5 retired in Sprint 06B.3; exhaustiveness and one lifecycle test replaced it"

echo
echo "=== 6. The contract is written down ==="
# The build VM receives device/ and frameworks/ only, so docs/ is not there. Sprint 06A.5 put a
# gate where its input did not exist and it failed safe but meaninglessly; this one says which
# machine it can run on instead of reporting a red that carries no information.
DOCS_CHECKED=1
if [ ! -d "$DOCS/adr" ]; then
  DOCS_CHECKED=0
  note "docs/ is not on this machine, so this gate is workstation-only and did not run"
else
for doc in "$DOCS/contracts/motion-sampler-contract.md" "$DOCS/adr/ADR-008-physics-contract-domain.md"; do
  if [ -f "$doc" ]; then pass "$(basename "$doc")"; else fail "$(basename "$doc") is missing"; fi
done
if grep -q 'Amended by Sprint 06B.0' "$DOCS/adr/ADR-002-sealed-animation-spec.md" 2>/dev/null; then
  pass "ADR-002 records its amendment"
else
  fail "ADR-002 does not record the 06B.0 amendment"
fi
fi
for rule in RULE-015 RULE-016 RULE-017 RULE-018; do
  if grep -q "\*\*$rule" README.md; then pass "$rule is in the README"; else fail "$rule is missing"; fi
done

echo
echo "======================================"
# The marker was SPRINT06B0 while this script was verify-sprint06b0.sh. Sprint 06B.3 renamed the
# file for guarding three sprints rather than one, and the marker had been left behind naming the
# sprint it was born in - the same "origin, not role" slip the rename existed to correct.
if [ "$FAILURES" -eq 0 ]; then
  if [ "$DOCS_CHECKED" -eq 1 ]; then
    echo "MOTION EVIDENCE PASS"
  else
    echo "MOTION EVIDENCE PASS (partial: documentation gate not run on this machine)"
  fi
  echo
  echo "Not checked here, and deliberately so:"
  echo "  - RULE-016 and RULE-017 are enforced by review; no script can decide either"
  echo "  - RULE-015's other half - that a witness is exercised, and refuses where it claims to -"
  echo "    is review's for the same reason (gate 4)"
  echo "  - snap has no trajectory subject and never will: it is a selection and then a spring,"
  echo "    so the spring's subject is the only one there is (ADR-009)"
  exit 0
fi
echo "MOTION EVIDENCE FAIL ($FAILURES)"
exit 1
