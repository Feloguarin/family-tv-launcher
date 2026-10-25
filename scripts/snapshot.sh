#!/usr/bin/env bash
# Save the device's current state so restore.sh can return to it.
source "$(dirname "$0")/_common.sh"
require_device
DIR="$ROOT/snapshots/$(date +%Y%m%d-%H%M%S)-${ANDROID_SERIAL//[:.]/_}"
mkdir -p "$DIR"
pkgs > "$DIR/packages-all.txt"
pkgs -d > "$DIR/packages-disabled.txt"
"$ADB" shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tr -d '\r' > "$DIR/home.txt"
"$ADB" shell getprop | tr -d '\r' > "$DIR/getprop.txt"
for ns in system secure global; do "$ADB" shell settings list $ns | tr -d '\r' > "$DIR/settings-$ns.txt"; done
ln -sfn "$DIR" "$ROOT/snapshots/latest-${ANDROID_SERIAL//[:.]/_}"
echo "Snapshot saved: $DIR"
echo "Packages: $(wc -l < "$DIR/packages-all.txt"), disabled: $(wc -l < "$DIR/packages-disabled.txt")"
