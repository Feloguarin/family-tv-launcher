#!/usr/bin/env bash
# Walk through the launcher's local API with curl (protocol: docs/controller-api.md).
#   scripts/api-demo.sh <tv-ip> pair      pair this laptop: the TV shows a code, you type it here, get a token
#   scripts/api-demo.sh <tv-ip> <token>   status, shows, play, voice, ambiguous, not found, home, auth checks
# Talks only to the launcher on port 8765 (PORT=… to change; never adb). Steps that change the TV ask first.
set -euo pipefail

if [[ $# -ne 2 ]]; then
  sed -n '2,5p' "$0" | sed 's/^# \{0,1\}//' >&2
  exit 1
fi
TV="$1"
TOKEN="$2"
BASE="http://$TV:${PORT:-8765}"

pretty() { # JSON on stdin → indented if jq or python3 is around
  local body; body="$(cat)"
  if command -v jq >/dev/null 2>&1 && printf '%s' "$body" | jq . 2>/dev/null; then return; fi
  if command -v python3 >/dev/null 2>&1 && printf '%s' "$body" | python3 -m json.tool 2>/dev/null; then return; fi
  printf '%s\n' "$body"
}

field() { # field NAME < json → value of a top-level string field
  python3 -c 'import json,sys; print(json.load(sys.stdin).get(sys.argv[1]) or "")' "$1" 2>/dev/null \
    || sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p"
}

BODY="" STATUS=""
# call METHOD PATH [JSON] [auth|noauth] [content-type]: shows the curl, the HTTP status and the reply.
call() {
  local method="$1" path="$2" data="${3:-}" auth="${4:-auth}" type="${5:-application/json}"
  local args=(-sS -X "$method" --max-time 25 -w '\n%{http_code}')
  local shown="curl -X $method $BASE$path"
  if [[ "$auth" == auth ]]; then
    args+=(-H "Authorization: Bearer $TOKEN")
    shown+=" -H 'Authorization: Bearer \$TOKEN'"
  fi
  if [[ "$method" == POST ]]; then
    [[ -z "$data" ]] && data='{}'
    args+=(-H "Content-Type: $type" --data-binary "$data")
    shown+=" -H 'Content-Type: $type' -d '$data'"
  fi
  echo "\$ $shown"
  local out
  out="$(curl "${args[@]}" "$BASE$path")" || { echo "(no answer from $BASE: is the launcher running?)"; exit 1; }
  STATUS="${out##*$'\n'}"
  BODY="${out%$'\n'*}"
  echo "→ HTTP $STATUS"
  printf '%s' "$BODY" | pretty
  echo
}

step() { echo; echo "== $1"; }
ask() { # ask "question" → true if the answer is y
  local reply
  read -rp "$1 [y/N] " reply
  [[ "$reply" == [yY]* ]]
}

if [[ "$TOKEN" == pair ]]; then
  step "1. Ask the TV for a pairing code (no token needed)"
  call POST /pair/start '{}' noauth
  [[ "$STATUS" == 200 ]] || exit 1
  echo "The TV now shows a 6-digit code for 2 minutes."
  read -rp "Code on the TV: " CODE
  read -rp "Name for this device [laptop]: " NAME
  NAME="${NAME:-laptop}"
  [[ "$CODE" =~ ^[0-9]{6}$ ]] || { echo "The code is 6 digits." >&2; exit 1; }
  [[ "$NAME" =~ ^[A-Za-z0-9\ _.-]{1,32}$ ]] || { echo "Name: 1-32 letters, digits, spaces, _ . -" >&2; exit 1; }

  step "2. Send the code back; the TV answers with a token (shown once, keep it)"
  call POST /pair/finish "{\"code\":\"$CODE\",\"deviceName\":\"$NAME\"}" noauth
  [[ "$STATUS" == 200 ]] || exit 1
  echo "Paired. Next:"
  echo "  export TOKEN='$(printf '%s' "$BODY" | field token)'"
  echo "  $0 $TV \"\$TOKEN\""
  exit 0
fi

step "1. What is the TV doing?"
call GET /status

step "2. The kids list (what the e-ink menu shows)"
call GET /shows

step "3. Requests the TV refuses"
echo "No token:"
call GET /status '' noauth
echo "Not JSON:"
call POST /play 'query=bluey' auth application/x-www-form-urlencoded
echo "Unknown show (nothing plays):"
call POST /play '{"query":"paw patrol"}'
echo "Two shows at once (nothing plays, the controller would list the candidates):"
call POST /voice '{"text":"play bluey and peppa pig"}'

step "4. Play by name (opens Bluey on the TV)"
if ask "Play Bluey now?"; then
  call POST /play '{"query":"bluey"}'
fi

step "5. Play from a voice transcript, in Spanish, with a mishearing (opens Peppa Pig)"
if ask "Send \"pon a pepa pig por favor\"?"; then
  call POST /voice '{"text":"pon a pepa pig por favor"}'
fi

step "6. Back to the launcher's Home screen"
if ask "Go Home now?"; then
  call POST /home
fi

echo
echo "Done. Protocol: docs/controller-api.md"
