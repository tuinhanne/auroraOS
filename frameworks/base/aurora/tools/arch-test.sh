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
  # Java: terminated by a semicolon, may be `import static`.
  grep -rhE "^import (static )?[a-zA-Z0-9_.]+;" "$1" --include='*.java' 2>/dev/null \
    | sed -E 's/^import (static )?//; s/;.*$//'
  # Kotlin: no semicolon, no `static`, may end in `.*` and may carry an `as` alias.
  # Kotlin sources must be scanned too, or a layer could import anything it liked
  # simply by being written in Kotlin.
  grep -rhE "^import +[a-zA-Z0-9_.]+(\.\*)?( +as +[A-Za-z0-9_]+)?[[:space:]]*$" "$1" --include='*.kt' 2>/dev/null \
    | sed -E 's/^import +//; s/ +as +.*$//; s/[[:space:]]+$//'
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

# RULE-007: forbidden platform calls.
#
# Import checks cannot catch these. Nothing has to be imported to write
# System.nanoTime() or Thread.sleep(), so the boundary has to be enforced on the
# call text itself.
#
# Exactly one exemption exists, declared in the contract as
#   call-exemption: <call>@<path relative to frameworks/base/aurora>
# because a rule that forbids reading the clock must still let the one sanctioned
# clock adapter read it. Written into the tool rather than remembered, or it gets
# copied elsewhere within a few sprints.
check_forbidden_calls() {
  local contract="$1"
  local layer src
  layer="$(value_of layer "$contract")"
  src="$AURORA_DIR/$(value_of source-root "$contract")"
  [ -d "$src" ] || return

  local call exempt_paths hits filtered p
  while IFS= read -r call; do
    [ -z "$call" ] && continue

    # Paths allowed to make this particular call.
    exempt_paths="$(values_of call-exemption "$contract" \
      | grep -F "$call@" | sed "s|^.*@||")"

    # Drop comment lines. Documentation has to be able to name what it forbids —
    # the rule's own explanation mentions every one of these — and a checker that
    # cannot tell a rule from a violation of it is worse than no checker, because
    # people learn to ignore its output.
    #
    # Matches on `path:line:` then leading whitespace then a comment opener. Code
    # with a trailing comment is still scanned, which is the right way round.
    hits="$(grep -rnF "$call" "$src" --include='*.java' --include='*.kt' 2>/dev/null \
      | grep -vE '^[^:]*:[0-9]+:[[:space:]]*(\*|//|/\*)' || true)"

    filtered="$hits"
    while IFS= read -r p; do
      [ -z "$p" ] && continue
      filtered="$(printf '%s\n' "$filtered" | grep -vF "$AURORA_DIR/$p" || true)"
    done <<< "$exempt_paths"

    if [ -n "$filtered" ]; then
      fail "$layer must not call '$call':"
      printf '%s\n' "$filtered" | sed 's/^/          /'
    elif [ -n "$exempt_paths" ]; then
      ok "$layer: '$call' only in $(printf '%s' "$exempt_paths" | tr '\n' ' ')"
    else
      ok "$layer: no call to $call"
    fi
  done < <(values_of forbid-call "$contract")
}

# RULE-009: forbidden calls, scoped to a path rather than to a whole layer.
#
# Declared in a contract as
#   forbid-call-under: <call>@<path relative to frameworks/base/aurora>
#
# forbid-call covers an entire layer, which is right for RULE-007 - nothing in sdk or runtime
# may read the system clock. RULE-009 is narrower: the hazard is specific to the animation
# packages, and one of the two entries is specific to a single file. Banning a hash container
# across the whole runtime would reject a perfectly good lookup map.
check_forbidden_calls_under() {
  local contract="$1"
  local layer entry call rel target hits
  layer="$(value_of layer "$contract")"

  while IFS= read -r entry; do
    [ -z "$entry" ] && continue
    call="${entry%%@*}"
    rel="${entry#*@}"
    target="$AURORA_DIR/$rel"

    if [ ! -e "$target" ]; then
      skip "$layer: '$call' under $rel (path does not exist yet)"
      continue
    fi

    # Same comment-stripping as check_forbidden_calls: documentation must be able to name
    # what it forbids, and this rule's own explanation names every one of these.
    #
    # -H forces the filename prefix even when $target is a single file rather than a
    # directory: plain grep -r omits it for a lone file, which would leave the comment
    # regex below unable to find the leading "path:line:" it expects.
    hits="$(grep -rnHF "$call" "$target" --include='*.java' --include='*.kt' 2>/dev/null \
      | grep -vE '^[^:]*:[0-9]+:[[:space:]]*(\*|//|/\*)' || true)"

    if [ -n "$hits" ]; then
      fail "$layer must not call '$call' under $rel:"
      printf '%s\n' "$hits" | sed 's/^/          /'
    else
      ok "$layer: nowhere under $rel calls $call"
    fi
  done < <(values_of forbid-call-under "$contract")
}

# ---------------------------------------------------------------------------
# Negative compiles.
#
# The checks above are textual: they read sources and Android.bp. This one asks
# javac, which is the mechanism the whole design actually relies on. The fixtures
# under tests/arch/ are named .java.txt so no Soong glob can ever pick them up;
# they are copied to a temp directory as .java and must fail to compile.
# ---------------------------------------------------------------------------

# Tree root, derived from this script's own location rather than from
# ANDROID_BUILD_TOP, which is empty unless the caller has run lunch.
tree_root() {
  local root="$AURORA_DIR/../../.."
  ( cd "$root" 2>/dev/null && pwd ) || echo "${ANDROID_BUILD_TOP:-}"
}

# Locate a built jar for a module, if the tree has been built.
find_jar() {
  local root
  root="$(tree_root)"
  [ -n "$root" ] || return 0
  find "$root/out/soong/.intermediates/frameworks/base/aurora/$1" \
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
  mkdir -p "$tmp/out"

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

  if ! command -v javac >/dev/null 2>&1; then
    skip "negative compiles: javac not on PATH"
    return
  fi

  local sdk_jar runtime_jar
  sdk_jar="$(find_jar aurora-sdk)"
  runtime_jar="$(find_jar aurora-runtime)"
  if [ -z "$sdk_jar" ] || [ -z "$runtime_jar" ]; then
    skip "negative compiles: aurora jars not built (run: m aurora-sdk aurora-runtime)"
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

main() {
  echo "Aurora architecture test"
  echo "contracts: $CONTRACTS_DIR"
  echo
  local contract
  for contract in "$CONTRACTS_DIR"/*.contract; do
    echo "--- $(basename "$contract") ---"
    check_forbidden_imports "$contract"
    check_aurora_layering "$contract"
    check_forbidden_calls "$contract"
    check_forbidden_calls_under "$contract"
    check_soong_deps "$contract"
    echo
  done

  echo "--- negative compiles ---"
  check_negative_compiles
  echo

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
