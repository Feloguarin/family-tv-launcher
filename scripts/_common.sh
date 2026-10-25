# Shared helpers. Target device: ANDROID_SERIAL (e.g. emulator-5554 or 192.168.1.50:5555).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(dirname "$HERE")"
ADB="${ADB:-adb}"
STOCK_LAUNCHER="com.google.android.apps.tv.launcherx"

require_device() {
  if [[ -z "${ANDROID_SERIAL:-}" ]]; then
    echo "Set ANDROID_SERIAL first (see: adb devices)" >&2; exit 1
  fi
  "$ADB" get-state >/dev/null 2>&1 || { echo "Device $ANDROID_SERIAL not reachable" >&2; exit 1; }
}

is_protected() {
  grep -v '^\s*#' "$HERE/protected-packages.txt" | grep -qx "$1"
}

pkgs() { "$ADB" shell pm list packages "$@" | sed 's/^package://' | tr -d '\r' | sort; }
