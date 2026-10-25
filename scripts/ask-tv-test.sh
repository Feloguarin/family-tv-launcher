#!/usr/bin/env bash
# Ask the TV on the real TV (docs/NOTES.md "Ask the TV", build step 0). Run each step when told to.
#   scripts/ask-tv-test.sh install    in-use check, install the debug build, mic permission (invisible if nothing plays)
#   scripts/ask-tv-test.sh switch     the phone's mic button → Ask the TV (assistant role)
#   scripts/ask-tv-test.sh watch [s]  read the log while someone presses the phone mic (default 90 s), then a verdict
#   scripts/ask-tv-test.sh variant    if the plain entry fails or pauses the show: turn on the voice-interaction entry
#   scripts/ask-tv-test.sh rollback   the mic back to Google Assistant (and the variant off)
# Add --force to install over a playing show (it doesn't interrupt playback, but asks first by default).
source "$(dirname "$0")/_common.sh"
require_device
PKG=familytv.launcher
APK="$ROOT/launcher/app/build/outputs/apk/debug/app-debug.apk"
VARIANT=(".ask.AskVoiceService" ".ask.AskVoiceSessionService" ".ask.AskRecognitionStub")

# (greps that find nothing are fine here: this file runs under set -e and pipefail)
playing() { "$ADB" shell dumpsys media_session | tr -d '\r' | grep -B1 "state=PlaybackState {state=3" | grep -o 'package=[^ ]*' | cut -d= -f2 | sort -u | paste -sd, - || true; }

case "${1:-}" in
  install)
    p="$(playing)"
    if [[ -n "$p" && "${2:-}" != "--force" ]]; then echo "Playing now: $p. Try later (or --force)." >&2; exit 1; fi
    [[ -f "$APK" ]] || { echo "Build first: cd launcher && ./gradlew assembleDebug" >&2; exit 1; }
    "$ADB" install -r "$APK" | tail -1
    "$ADB" shell pm grant "$PKG" android.permission.RECORD_AUDIO
    sleep 3 # the app restarts its bedtime clock by itself after an update (MY_PACKAGE_REPLACED)
    echo "Installed: $("$ADB" shell dumpsys package "$PKG" | grep -m1 lastUpdateTime | xargs)"
    echo "Bedtime clock running: $("$ADB" shell dumpsys activity services "$PKG" | grep -A12 'bedtime.BedtimeService}' | grep -c 'isForeground=true' || true)"
    ;;
  switch)
    "$(dirname "$0")/mic-button.sh" ask
    ;;
  variant)
    for c in "${VARIANT[@]}"; do "$ADB" shell pm enable "$PKG/$c" >/dev/null; done
    "$ADB" shell cmd role add-role-holder android.app.role.ASSISTANT com.google.android.katniss
    "$ADB" shell cmd role add-role-holder android.app.role.ASSISTANT "$PKG"
    "$(dirname "$0")/mic-button.sh"
    ;;
  rollback)
    for c in "${VARIANT[@]}"; do "$ADB" shell pm disable "$PKG/$c" >/dev/null 2>&1 || true; done
    "$(dirname "$0")/mic-button.sh" google
    ;;
  watch)
    secs="${2:-90}"
    log="$(mktemp)"
    before="$("$ADB" shell dumpsys media_session | tr -d '\r' | grep -E 'package=|state=PlaybackState' | paste - - | sed -E 's/.*package=([^ ]*).*state=([0-9]+).*/\1=\2/' | paste -sd' ' - || true)"
    "$ADB" logcat -c
    echo "Press the phone's mic now and ask something (e.g. \"who plays Mark?\"). Watching for ${secs}s…"
    "$ADB" logcat -v time AskTheTv:I AskSpeech:V ClaudeClient:I SleepTimer:I ActivityTaskManager:I '*:S' > "$log" &
    pid=$!
    sleep "$secs"; kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true
    after="$("$ADB" shell dumpsys media_session | tr -d '\r' | grep -E 'package=|state=PlaybackState' | paste - - | sed -E 's/.*package=([^ ]*).*state=([0-9]+).*/\1=\2/' | paste -sd' ' - || true)"
    grep -E "AskTheTv|AskSpeech|ClaudeClient|SleepTimer|ASSIST|VoiceInteraction" "$log" | sed -E 's/^[0-9-]+ //' || true
    echo
    yes() { grep -qE "$1" "$log" && echo "yes" || echo "NO"; }
    echo "Verdict"
    echo "  (a) mic opened Ask the TV:   $(yes 'start: look=')  $(grep -m1 -oE 'START u0 \{act=android.intent.action.ASSIST[^}]*' "$log" | cut -c1-80 || true)"
    echo "      recognizer ready:        $(yes 'AskSpeech.*ready')"
    echo "  (b) live words from phone:   $(yes 'AskSpeech.*partial')   final: $(grep -m1 -oE 'final \+[0-9]+ms: .*' "$log" || true)"
    echo "      errors:                  $(grep -oE 'error [0-9]+ \([a-z ]+\)' "$log" | sort -u | paste -sd, - || true)"
    echo "      Claude:                  $(grep -m1 -oE 'claude-haiku-5-5: .*' "$log" || echo none)"
    echo "  players before: $before"
    echo "  players after:  $after   (3 = playing, 2 = paused; a 3 that became 2 means the mic paused the show)"
    rm -f "$log"
    ;;
  *) sed -n '2,9p' "$0"; exit 2 ;;
esac
