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
