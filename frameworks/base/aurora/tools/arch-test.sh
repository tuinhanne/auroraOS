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

main() {
  echo "Aurora architecture test"
  echo "contracts: $CONTRACTS_DIR"
  echo
  local contract
  for contract in "$CONTRACTS_DIR"/*.contract; do
    echo "--- $(basename "$contract") ---"
    check_forbidden_imports "$contract"
    check_aurora_layering "$contract"
    check_soong_deps "$contract"
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
