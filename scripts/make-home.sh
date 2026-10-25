#!/usr/bin/env bash
# Make the Family TV launcher the Home app. Reversible with scripts/restore.sh.
#   1. snapshot (if none yet) so restore knows the original state
#   2. disable Google TV's home (launcherx); nothing is uninstalled
#   3. if the setup wizard's RecoveryActivity then wins Home (Hisense TV has it at priority 1),
#      disable it too, but only with --allow-setupwraith (it's on the protected list for a reason;
#      a factory reset or restore.sh brings it back)
source "$(dirname "$0")/_common.sh"
require_device
PKG=familytv.launcher
WRAITH=com.google.android.tungsten.setupwraith
home() { "$ADB" shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tr -d '\r' | tail -1; }

"$ADB" shell pm list packages | grep -q "package:$PKG\$" || { echo "Install the launcher first" >&2; exit 1; }
ls -d "$ROOT"/snapshots/latest-${ANDROID_SERIAL//[:.]/_} >/dev/null 2>&1 || "$HERE/snapshot.sh"

"$HERE/disable.sh" "$STOCK_LAUNCHER"
now="$(home)"
if [[ "$now" == "$WRAITH/"* ]]; then
  if [[ "${1:-}" != "--allow-setupwraith" ]]; then
    echo "Home now goes to the setup wizard ($now)."
    echo "Re-run with --allow-setupwraith to disable it too, or scripts/restore.sh to undo."
    exit 2
  fi
  "$ADB" shell pm disable-user --user 0 "$WRAITH" >/dev/null
  echo "$(date -u +%FT%TZ) $WRAITH (make-home --allow-setupwraith)" >> "$ROOT/snapshots/disabled-log-${ANDROID_SERIAL//[:.]/_}.txt"
  now="$(home)"
fi

if [[ "$now" == "$PKG/"* ]]; then
  "$ADB" shell input keyevent HOME
  echo "Home is now the Family TV launcher. Undo any time: ANDROID_SERIAL=$ANDROID_SERIAL scripts/restore.sh"
else
  echo "Home resolves to: $now (not the launcher). Run scripts/restore.sh and check." >&2
  exit 1
fi
