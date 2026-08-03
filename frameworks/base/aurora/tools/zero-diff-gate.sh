#!/usr/bin/env bash
# Sprint 06A.5: prove the migration changed no runtime behaviour.
#
# Five files carry the engine's behaviour and none of them mentions progress, a sampler or a
# strategy in code. If any of them needs a real change to make the tests pass, the model is
# wrong somewhere and the sprint stops.
#
# Comments are excluded. Two of these files name AnimationStrategy in prose, and that prose has
# to be corrected or it points at a type that no longer exists. arch-test.sh already strips
# comments before checking forbidden calls; this uses the same idea.
#
#   bash frameworks/base/aurora/tools/zero-diff-gate.sh <base-ref>
set -uo pipefail

BASE="${1:-main}"
AURORA="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$AURORA/../../.." || exit 1

FILES=(
  frameworks/base/aurora/runtime/java/aurora/runtime/animation/AnimationStateMachine.kt
  frameworks/base/aurora/runtime/java/aurora/runtime/animation/AnimationRegistry.kt
  frameworks/base/aurora/runtime/java/aurora/runtime/animation/AnimationDriver.kt
  frameworks/base/aurora/runtime/java/aurora/runtime/animation/DefaultAnimationController.kt
  frameworks/base/aurora/runtime/java/aurora/runtime/animation/ExecutionTimeline.kt
)

# Reduce a file to the lines that carry behaviour, so prose may change and code may not.
#
# Two passes. The first drops comment-only lines and blanks. The second drops a trailing //
# comment from a line that also has code, because this sprint edits comments and a gate that
# flagged `foo() // old wording` -> `foo() // new wording` as a behaviour change would produce
# false failures - and a checker people learn to ignore is worse than none.
#
# The trailing strip skips any line containing a double quote, so a string literal holding //
# is never truncated. That leaves one blind spot: a comment-only edit to a line that ALSO holds a
# quoted string would be reported as a code change. No guarded line needs both today, and the
# check below proves it rather than asserting it. Refusing to guess is cheaper
# than a regex that tries to parse Kotlin.
# Reads stdin so both sides of the diff go through exactly one implementation. Duplicating it -
# a function for the working tree and an inline grep for the base - would let the two drift and
# report a difference that is only in the stripping.
stripStdin() {
  grep -vE '^[[:space:]]*(\*|//|/\*|$)' \
    | sed -E '/"/! s@[[:space:]]*//.*$@@' \
    | grep -vE '^[[:space:]]*$'
}

# The blind spot named above, checked instead of assumed. A guarded line holding both a quoted
# string and a trailing comment cannot have its comment edited without this gate calling it a
# code change. None exists today; if one ever does, say so loudly here rather than let a future
# task rediscover it as a mystery FAIL.
BLIND=0
for f in "${FILES[@]}"; do
  [ -f "$f" ] || continue
  HITS=$(grep -nE '"[^"]*".*//' "$f" 2>/dev/null | grep -vE '^[0-9]+:[[:space:]]*(\*|//|/\*)')
  if [ -n "$HITS" ]; then
    echo "  warn  $(basename "$f") has a line with both a string and a trailing comment;"
    echo "        editing that comment alone would read as a code change:"
    printf '%s\n' "$HITS" | sed 's/^/          /'
    BLIND=1
  fi
done
[ "$BLIND" -eq 0 ] && echo "  ok    no guarded line mixes a quoted string with a trailing comment"
echo

FAILURES=0
for f in "${FILES[@]}"; do
  if [ ! -f "$f" ]; then
    echo "  FAIL  $f no longer exists"
    FAILURES=$((FAILURES + 1))
    continue
  fi
  # Process substitution keeps both sides as files for diff. Both go through stripStdin.
  if diff -q <(git show "$BASE:$f" 2>/dev/null | stripStdin) \
            <(stripStdin < "$f") >/dev/null 2>&1; then
    echo "  ok    $(basename "$f") unchanged in code"
  else
    echo "  FAIL  $(basename "$f") changed in code, not just comments:"
    diff <(git show "$BASE:$f" 2>/dev/null | stripStdin) \
         <(stripStdin < "$f") | head -20 | sed 's/^/          /'
    FAILURES=$((FAILURES + 1))
  fi
done

echo
if [ "$FAILURES" -eq 0 ]; then
  echo "ZERO_DIFF_GATE PASS"
  exit 0
fi
echo "ZERO_DIFF_GATE FAIL ($FAILURES file(s))"
exit 1
