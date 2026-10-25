#!/usr/bin/env bash
# Return the device to its snapshot: re-enable everything that was enabled then,
# re-enable the stock launcher and clear any custom HOME preference.
source "$(dirname "$0")/_common.sh"
require_device
SNAP="${1:-$ROOT/snapshots/latest-${ANDROID_SERIAL//[:.]/_}}"
[[ -f "$SNAP/packages-disabled.txt" ]] || { echo "No snapshot at $SNAP" >&2; exit 1; }
now_disabled="$(mktemp)"; pkgs -d > "$now_disabled"
# Disabled now but not disabled in snapshot -> re-enable
comm -23 "$now_disabled" "$SNAP/packages-disabled.txt" | while read -r p; do
  [[ -n "$p" ]] && "$ADB" shell pm enable --user 0 "$p" >/dev/null && echo "re-enabled $p"
done
"$ADB" shell pm enable --user 0 "$STOCK_LAUNCHER" >/dev/null 2>&1 || true
"$ADB" shell pm clear-preferred-activities familytv.launcher >/dev/null 2>&1 || true
# The mic button back to Google Assistant (Ask the TV took it with scripts/mic-button.sh ask)
if "$ADB" shell pm list packages | grep -q "package:com.google.android.katniss"; then
  "$ADB" shell cmd role add-role-holder android.app.role.ASSISTANT com.google.android.katniss >/dev/null 2>&1 || true
  echo "Mic button: $("$ADB" shell settings get secure assistant | tr -d '\r')"
fi
echo "HOME now resolves to: $("$ADB" shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tr -d '\r' | tail -1)"
echo "Restore complete."
