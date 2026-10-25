#!/usr/bin/env bash
# Safely disable packages (never uninstalls). Usage: disable.sh pkg [pkg...]
source "$(dirname "$0")/_common.sh"
require_device
ls "$ROOT"/snapshots/latest-${ANDROID_SERIAL//[:.]/_} >/dev/null 2>&1 || { echo "No snapshot yet — run snapshot.sh first" >&2; exit 1; }
LOG="$ROOT/snapshots/disabled-log-${ANDROID_SERIAL//[:.]/_}.txt"
for p in "$@"; do
  if is_protected "$p"; then echo "REFUSED (protected): $p"; continue; fi
  "$ADB" shell pm disable-user --user 0 "$p" && echo "$(date -u +%FT%TZ) $p" >> "$LOG"
done
