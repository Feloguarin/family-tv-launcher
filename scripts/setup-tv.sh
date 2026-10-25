#!/usr/bin/env bash
# One-time grants the launcher needs on a device (TV or emulator). Additive and reversible:
#   - "display over other apps" (cover screen, bedtime pill)
#   - media session access (what's playing), added to existing notification listeners
#   - screen reader (accessibility) for verifying Disney+ pages, added to existing services
#   - microphone (Ask the TV: the TV's recognizer checks its caller may record)
# Undo: scripts/setup-tv.sh --undo
source "$(dirname "$0")/_common.sh"
require_device
PKG=familytv.launcher
A11Y="$PKG/$PKG.playback.ScreenReaderService"
LISTENER="$PKG/$PKG.playback.MediaListenerService"

get() { "$ADB" shell settings get secure "$1" | tr -d '\r' | sed 's/^null$//'; }
add_to() { # add_to <key> <component>: append to a ':'-separated list once
  local cur; cur="$(get "$1")"
  [[ ":$cur:" == *":$2:"* ]] && return
  "$ADB" shell settings put secure "$1" "${cur:+$cur:}$2"
}
remove_from() {
  local cur; cur="$(get "$1")"
  local new; new="$(tr ':' '\n' <<<"$cur" | grep -vx "$2" | paste -sd: -)"
  if [[ -z "$new" ]]; then "$ADB" shell settings delete secure "$1" >/dev/null; else "$ADB" shell settings put secure "$1" "$new"; fi
}

if [[ "${1:-}" == "--undo" ]]; then
  "$ADB" shell appops set "$PKG" SYSTEM_ALERT_WINDOW default
  remove_from enabled_accessibility_services "$A11Y"
  [[ -z "$(get enabled_accessibility_services)" ]] && "$ADB" shell settings put secure accessibility_enabled 0
  "$ADB" shell cmd notification disallow_listener "$LISTENER" 2>/dev/null || remove_from enabled_notification_listeners "$LISTENER"
  "$ADB" shell pm revoke "$PKG" android.permission.RECORD_AUDIO 2>/dev/null || true
  echo "Launcher grants removed."
  exit 0
fi

"$ADB" shell pm list packages | grep -q "package:$PKG\$" || { echo "Install the launcher first" >&2; exit 1; }
"$ADB" shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow
"$ADB" shell cmd notification allow_listener "$LISTENER" 2>/dev/null || add_to enabled_notification_listeners "$LISTENER"
add_to enabled_accessibility_services "$A11Y"
"$ADB" shell settings put secure accessibility_enabled 1
"$ADB" shell pm grant "$PKG" android.permission.RECORD_AUDIO

echo "overlay:   $("$ADB" shell appops get "$PKG" SYSTEM_ALERT_WINDOW | tr -d '\r')"
echo "listeners: $(get enabled_notification_listeners)"
echo "a11y:      $(get enabled_accessibility_services)"
echo "mic:       $("$ADB" shell dumpsys package "$PKG" | grep -m1 'RECORD_AUDIO: granted' | tr -d '\r' | xargs)"
