#!/usr/bin/env bash
# Apply Aurora's upstream patches to an AOSP checkout. ADR-011.
#
#   bash apply-patches.sh <checkout-root> <patches-root>
#
# ## Restore, then apply
#
# Every file a patch touches is restored from its own project's git before anything is applied.
# That is what makes re-application well defined instead of carefully avoided: the tree this runs
# against is always pristine, so applying twice produces exactly what applying once produces, and
# the half-applied tree - where a rebuild silently doubles a change - cannot arise.
#
# It is not an optimisation to skip. A run that reused the previous result would reintroduce the
# accumulated state ADR-011 exists to remove.
#
# ## Layout
#
#   <patches-root>/<project>/<nnn>-<slug>.patch
#
# <project> is the path of the AOSP git project, relative to the checkout root, so the directory
# structure says where each patch applies. Paths inside a patch are relative to the project root.
#
# Exits non-zero on the first failure. A patch that does not apply is not skipped.
set -uo pipefail

ROOT="${1:-}"
PATCHES="${2:-}"

if [ -z "$ROOT" ] || [ -z "$PATCHES" ]; then
  echo "usage: apply-patches.sh <checkout-root> <patches-root>" >&2
  exit 2
fi
[ -d "$ROOT" ] || { echo "ERROR: checkout root not found: $ROOT" >&2; exit 2; }

if [ ! -d "$PATCHES" ]; then
  echo "  no patches directory at $PATCHES - nothing to apply"
  exit 0
fi

# Sorted, so application order is the same everywhere and readable from the filenames.
mapfile -t FILES < <(find "$PATCHES" -name '*.patch' -type f | sort)

if [ "${#FILES[@]}" -eq 0 ]; then
  echo "  0 patches under $PATCHES - nothing to apply"
  exit 0
fi

FAILED=0
for P in "${FILES[@]}"; do
  REL="${P#"$PATCHES"/}"          # frameworks/base/0001-foo.patch
  PROJ="$(dirname "$REL")"        # frameworks/base
  DIR="$ROOT/$PROJ"

  if [ ! -d "$DIR/.git" ]; then
    echo "  FAIL  $REL: $PROJ is not a git project in this checkout"
    FAILED=$((FAILED + 1))
    continue
  fi

  # --numstat parses the patch without touching anything, so this is safe to ask before restoring.
  if ! TOUCHED=$(git -C "$DIR" apply --numstat "$P" 2>/dev/null | awk '{print $3}'); then
    echo "  FAIL  $REL: not a patch git can read"
    FAILED=$((FAILED + 1))
    continue
  fi

  # Restore only what this patch touches, and only what HEAD actually has - a patch that adds a
  # file has nothing to restore, and asking git to check out a path it does not know is an error
  # rather than a no-op.
  for F in $TOUCHED; do
    if git -C "$DIR" cat-file -e "HEAD:$F" 2>/dev/null; then
      git -C "$DIR" checkout -- "$F" 2>/dev/null
    fi
  done

  if ! git -C "$DIR" apply --check "$P" 2>/dev/null; then
    echo "  FAIL  $REL: does not apply to a pristine $PROJ"
    FAILED=$((FAILED + 1))
    continue
  fi

  if git -C "$DIR" apply "$P" 2>/dev/null; then
    echo "  ok    $REL"
  else
    echo "  FAIL  $REL: --check passed but apply did not"
    FAILED=$((FAILED + 1))
  fi
done

echo "  ${#FILES[@]} patch(es), $FAILED failed"
[ "$FAILED" -eq 0 ] || exit 1
