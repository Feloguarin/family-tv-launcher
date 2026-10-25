#!/usr/bin/env bash
# Which app answers the phone remote's mic button (docs/NOTES.md "Ask the TV").
#   scripts/mic-button.sh ask      → the launcher (Ask the TV); Google Assistant stops answering the mic
#   scripts/mic-button.sh google   → Google Assistant again (the TV's original setting)
#   scripts/mic-button.sh          → show who has it
# The assistant role is exclusive, so setting one replaces the other. restore.sh also puts Google back.
source "$(dirname "$0")/_common.sh"
require_device
PKG=familytv.launcher
GOOGLE=com.google.android.katniss
ROLE=android.app.role.ASSISTANT

case "${1:-}" in
  ask)
    "$ADB" shell pm list packages | grep -q "package:$PKG\$" || { echo "Install the launcher first" >&2; exit 1; }
    "$ADB" shell pm grant "$PKG" android.permission.RECORD_AUDIO
    "$ADB" shell cmd role add-role-holder "$ROLE" "$PKG" ;;
  google)
    "$ADB" shell cmd role add-role-holder "$ROLE" "$GOOGLE" ;;
  "") ;;
  *) echo "usage: $0 [ask|google]" >&2; exit 2 ;;
esac
s() { "$ADB" shell settings get secure "$1" | tr -d '\r'; }
echo "assistant:           $(s assistant)"
echo "voice interaction:   $(s voice_interaction_service)"
echo "speech recognizer:   $(s voice_recognition_service)"
