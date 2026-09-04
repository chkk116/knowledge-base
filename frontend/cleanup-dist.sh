#!/usr/bin/env bash
# ============================================================
# Cleanup old dist-* build folders. Keeps the newest N.
#
# Usage:  ./cleanup-dist.sh [keep] [go]
#           keep = how many newest folders to keep (default 5)
#           go   = actually delete; omit for dry-run
#
# Examples:
#   ./cleanup-dist.sh              - dry run, keep newest 5
#   ./cleanup-dist.sh 5 go         - really delete, keep newest 5
#   ./cleanup-dist.sh 10 go        - keep newest 10
#
# NOTE: two legacy folders are NOT matched by the dist-* pattern
# and are never touched by this script:
#   dist       (old default output dir)
#   dist_bak   (old backup)
# Remove them manually if you no longer need them.
# ============================================================
set -uo pipefail

KEEP="${1:-5}"
GO="${2:-}"

if ! [[ "$KEEP" =~ ^[0-9]+$ ]] || [ "$KEEP" -lt 1 ]; then
  echo "ERROR: keep count must be a positive number. Got: $KEEP"
  exit 1
fi

cd "$(dirname "${BASH_SOURCE[0]}")" || exit 1

if [ "$GO" = "go" ]; then
  echo "=== DELETING, keeping newest $KEEP ==="
else
  echo "=== DRY RUN, keeping newest $KEEP ==="
fi
echo

# newest first (ls -t sorts by mtime)
mapfile -t DIRS < <(ls -1dt dist-* 2>/dev/null)

if [ ${#DIRS[@]} -eq 0 ]; then
  echo "No dist-* folders found."
  exit 0
fi

COUNT=0
DELCOUNT=0
for d in "${DIRS[@]}"; do
  COUNT=$((COUNT + 1))
  if [ "$COUNT" -le "$KEEP" ]; then
    echo "[KEEP] $d"
  else
    DELCOUNT=$((DELCOUNT + 1))
    if [ "$GO" = "go" ]; then
      echo "[DELETE] $d"
      rm -rf "$d" || echo "  ! FAILED: $d"
    else
      echo "[WOULD DELETE] $d"
    fi
  fi
done

echo
echo "Total dist-* folders: $COUNT  Kept: $KEEP  Removed: $DELCOUNT"
if [ "$GO" != "go" ]; then
  echo
  echo "Nothing was deleted. To really delete, run:"
  echo "  ./cleanup-dist.sh $KEEP go"
fi
