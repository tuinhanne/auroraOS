#!/usr/bin/env bash
# Unpack the archive uploaded from the workstation and rsync it into the
# LineageOS tree on this VM.
set -uo pipefail

LINEAGE_DIR=/mnt/build/lineage
STAGING=/mnt/build/staging
ARCHIVE="$HOME/code-sync.tgz"
RSLOG=/mnt/build/rsync-last.log

# Only these exact paths are synced. Never rsync --delete at the device/ or
# frameworks/ level: that would wipe the sibling trees that repo manages.
PATHS=(
  "device/samsung/beyond2lte"
  "device/samsung/exynos9820-common"
  "frameworks/base/aurora"
)

[ -f "$ARCHIVE" ] || { echo "ERROR: $ARCHIVE not found"; exit 1; }
[ -d "$LINEAGE_DIR" ] || { echo "ERROR: $LINEAGE_DIR not found"; exit 1; }

echo "=== Unpacking ==="
rm -rf "$STAGING"
mkdir -p "$STAGING"
tar -xzf "$ARCHIVE" -C "$STAGING" || { echo "ERROR: unpack failed"; exit 1; }

echo
echo "=== Syncing into the tree ==="
: > "$RSLOG"
TOTAL_CHANGED=0
for p in "${PATHS[@]}"; do
  SRC="$STAGING/$p"
  DST="$LINEAGE_DIR/$p"
  if [ ! -d "$SRC" ]; then
    echo "  SKIP (not in archive): $p"
    continue
  fi
  mkdir -p "$DST"
  # --exclude .git  : leave repo-managed git metadata alone. rsync never deletes
  #                   excluded files, so .git survives --delete.
  # --no-perms      : Windows cannot store the executable bit. Letting rsync sync
  #                   permissions would strip +x from extract-files.py and
  #                   setup-makefiles.py and show them as modified in git.
  # Write straight to a file; piping through `head` sends SIGPIPE and kills
  # rsync partway through.
  rsync -a --no-perms --delete --exclude '.git' --itemize-changes "$SRC/" "$DST/" >> "$RSLOG" 2>&1
  RC=$?
  N=$(grep -c . "$RSLOG" 2>/dev/null)
  echo "  $p  -> rsync rc=$RC"
  TOTAL_CHANGED=$N
done

echo
echo "=== Changes ($TOTAL_CHANGED lines, showing up to 20) ==="
if [ "$TOTAL_CHANGED" -eq 0 ]; then
  echo "  (none - the VM tree already matches the workstation)"
else
  head -n 20 "$RSLOG" | sed 's/^/    /'
  [ "$TOTAL_CHANGED" -gt 20 ] && echo "    ... $((TOTAL_CHANGED - 20)) more lines in $RSLOG"
fi

echo
echo "=== Git status on the VM ==="
for p in "${PATHS[@]}"; do
  D="$LINEAGE_DIR/$p"
  if [ -d "$D/.git" ]; then
    CHANGED=$(git -C "$D" status --porcelain 2>/dev/null | grep -c .)
    echo "  $p: $CHANGED files differ from upstream"
  else
    echo "  $p: no .git of its own (new directory inside a parent repo)"
  fi
done

rm -rf "$STAGING"
echo
echo "=== APPLY_DONE_OK ==="
