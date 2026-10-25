#!/usr/bin/env bash
# Turns off the TV's telemetry we found on Oct 24 (docs/NOTES.md "TV telemetry"). Reversible.
#   scripts/privacy.sh          apply: Hisense ACR off and disabled, Google usage & diagnostics and crash reports off,
#                               VIDAA content tab disabled
#   scripts/privacy.sh --check  show the current state (run after a Hisense update: it may turn ACR back on)
#   scripts/privacy.sh --undo   put everything back as the TV shipped
# Packages go through disable.sh (logged; restore.sh re-enables them too).
source "$(dirname "$0")/_common.sh"
require_device
ACR=com.hisense.evservice
VIDAA_TAB=com.vidaa.oemtabcontentprovider

g() { "$ADB" shell settings get "$1" "$2" | tr -d '\r'; }
state() { "$ADB" shell pm list packages -d | grep -q "package:$1\$" && echo disabled || echo enabled; }
check() {
  echo "ACR app ($ACR):              $(state $ACR), acr_service_enable=$(g global acr_service_enable)"
  echo "ACR connection open:          $("$ADB" shell 'cat /proc/net/tcp /proc/net/tcp6' | awk '$4=="01" && $3 ~ /:075B$/' | grep -c . | tr -d ' ') (MQTT port 1883)"
  echo "Google usage & diagnostics:   multi_cb=$(g global multi_cb)"
  echo "Crash reports to Google:      global=$(g global send_action_app_error) secure=$(g secure send_action_app_error)"
  echo "VIDAA content tab:            $(state $VIDAA_TAB)"
}

case "${1:-}" in
  --check) check; exit 0 ;;
  --undo)
    "$ADB" shell pm enable --user 0 "$ACR" >/dev/null
    "$ADB" shell pm enable --user 0 "$VIDAA_TAB" >/dev/null
    "$ADB" shell settings put global acr_service_enable 1
    "$ADB" shell settings put global multi_cb 1
    "$ADB" shell settings put global send_action_app_error 1
    "$ADB" shell settings put secure send_action_app_error 1
    check; exit 0 ;;
  "") ;;
  *) echo "usage: $0 [--check|--undo]" >&2; exit 2 ;;
esac

# The switch first, so the ACR service sees it and stops cleanly; then the app itself.
"$ADB" shell settings put global acr_service_enable 0
"$(dirname "$0")/disable.sh" "$ACR" "$VIDAA_TAB"
"$ADB" shell settings put global multi_cb 0
"$ADB" shell settings put global send_action_app_error 0
"$ADB" shell settings put secure send_action_app_error 0
check
