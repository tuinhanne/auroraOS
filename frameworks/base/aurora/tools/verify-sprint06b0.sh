#!/usr/bin/env bash
# Sprint 06B.0 gate: the physics contract is stated, enforced, and shipped with no solver.
#
#   bash frameworks/base/aurora/tools/verify-sprint06b0.sh
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
echo "=== 2. The sprint ships no solver ==="
SRC_COUNT=$(find sdk runtime platform -name '*.kt' | wc -l)
if [ "$SRC_COUNT" -lt 20 ]; then
  fail "only $SRC_COUNT Kotlin sources found; the tree is not where this script thinks it is"
else
  pass "$SRC_COUNT Kotlin sources under sdk/ runtime/ platform/"
  SOLVERS=$(grep -rlE 'class (Spring|Decay|Snap|Fling)Sampler' runtime platform 2>/dev/null)
  if [ -n "$SOLVERS" ]; then
    fail "a solver reached production code:"
    printf '%s\n' "$SOLVERS" | sed 's/^/          /'
  else
    pass "no solver under runtime/ or platform/"
  fi
  # The fixtures are wrong on purpose and must never leave the test tree.
  STRAY=$(grep -rlE 'class (NaNAfterConvergence|SharedCounter|WrongDerivative|IncreasingEnvelope|NonConverging)Sampler' sdk runtime platform 2>/dev/null)
  if [ -n "$STRAY" ]; then
    fail "a deliberately broken fixture escaped the test tree:"
    printf '%s\n' "$STRAY" | sed 's/^/          /'
  else
    pass "every broken fixture is confined to tests/"
  fi
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
echo "=== 4. RULE-015: every contract property has a fixture that violates it ==="
SELFTEST=tests/java/aurora/testing/animation/ContractSelfTest.kt
HARNESS="tests/java/aurora/testing/animation/SamplerContract.kt tests/java/aurora/testing/animation/PhysicsContract.kt"
FIXTURES=tests/java/aurora/testing/animation/BrokenSamplers.kt

PAIRS=$(grep -oE 'assert[A-Za-z]+[[:space:]]+<-[[:space:]]+[A-Za-z-]+' "$SELFTEST" 2>/dev/null)
DECLARED=$(printf '%s\n' "$PAIRS" | awk '{print $1}' | sort -u)
DEFINED=$(grep -rhoE '^[[:space:]]*fun (assert[A-Za-z]+)' $HARNESS 2>/dev/null | awk '{print $2}' | sort -u)

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

  # c. every named fixture must exist, unless the pair is an explicit tier exemption.
  # The summary line is conditional: a green "every fixture exists" printed under a red one
  # naming a missing fixture contradicts itself, and a reader skimming for green would believe
  # the wrong half.
  BAD_FIXTURES=0
  while read -r LEFT _ RIGHT; do
    [ -z "${RIGHT:-}" ] && continue
    if [ "$RIGHT" = "solver-tier" ]; then
      note "$LEFT is solver-tier and needs no fixture (RULE-015 binds the contract tier)"
    elif ! grep -q "class $RIGHT" "$FIXTURES"; then
      fail "$LEFT names fixture $RIGHT, which is not a class in BrokenSamplers.kt"
      BAD_FIXTURES=$((BAD_FIXTURES + 1))
    fi
  done <<< "$PAIRS"
  [ "$BAD_FIXTURES" -eq 0 ] && pass "every declared fixture exists or is an explicit exemption"
fi
note "whether each pair is actually exercised is enforced by review; grep cannot see it"

echo
echo "=== 5. Physics is still refused at runtime ==="
REFUSALS=$(grep -rc 'UnsupportedOperationException(' runtime/java/aurora/runtime/animation/AnimationHandleImpl.kt 2>/dev/null)
TOTAL=$(grep -rl 'UnsupportedOperationException(' sdk runtime platform 2>/dev/null | wc -l)
if [ "${REFUSALS:-0}" -eq 1 ] && [ "$TOTAL" -eq 1 ]; then
  pass "samplerFor still refuses a PhysicsSpec, in exactly one place"
else
  fail "expected one refusal in AnimationHandleImpl.kt; found $REFUSALS there and $TOTAL file(s) overall"
fi

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
for rule in RULE-015 RULE-016 RULE-017; do
  if grep -q "\*\*$rule" README.md; then pass "$rule is in the README"; else fail "$rule is missing"; fi
done

echo
echo "======================================"
if [ "$FAILURES" -eq 0 ]; then
  if [ "$DOCS_CHECKED" -eq 1 ]; then
    echo "SPRINT06B0 PASS"
  else
    echo "SPRINT06B0 PASS (partial: documentation gate not run on this machine)"
  fi
  echo
  echo "Not checked here, and deliberately so:"
  echo "  - the spring envelope has no trajectory subject until 06B.1 (RULE-017)"
  echo "  - RULE-016 and RULE-017 are enforced by review; no script can decide either"
  exit 0
fi
echo "SPRINT06B0 FAIL ($FAILURES)"
exit 1
