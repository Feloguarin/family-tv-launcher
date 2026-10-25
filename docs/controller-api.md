# Controller API (TV launcher, port 8765)

The kids launcher on the TV runs a small HTTP server so the Waveshare ESP32-S3 e-paper controller
(and, later, voice) can say "play Bluey". This page is the contract for the firmware.

- **Base URL:** `http://<tv-ip>:8765` (the TV's IP is reserved in the router, e.g. `192.168.1.50`).
- **Format:** JSON in and out, UTF-8. Responses always have a JSON body.
- **Plain HTTP on the home Wi-Fi.** WPA2 keeps outsiders out; the token keeps other devices on the LAN out.

## Rules every request must follow

| Rule | Otherwise |
|---|---|
| Come from the home network (10/8, 172.16/12, 192.168/16, IPv6 link-local/ULA, loopback) | connection closed / `403` |
| `Host:` is the TV's IP (or a local name like `tv.local`) — ESP32 HTTP clients do this by default | `403` |
| No `Origin:` header (only browsers send it; browsers are not allowed) | `403` |
| Every `POST` sends `Content-Type: application/json` and a `Content-Length` (no chunked bodies), even with an empty body | `415` / `411` |
| Body at most 4096 bytes | `413` |
| Everything except `/pair/*` sends `Authorization: Bearer <token>` | `401` |

The server answers **one request per connection** and then closes it (`Connection: close`). Open a new
connection for each call; don't rely on keep-alive.

## Pairing (once per controller)

```
Controller                                  TV
    |  POST /pair/start                      |
    |--------------------------------------->|  shows "Pairing code 482 913" for 2 minutes
    |  200 {"result":"code_shown",           |
    |       "expiresIn":120}                 |
    |                                        |
    |  (person reads the code off the TV and types it on the controller)
    |                                        |
    |  POST /pair/finish                     |
    |  {"code":"482913","deviceName":"Kids controller"}
    |--------------------------------------->|  stores only SHA-256(token)
    |  200 {"token":"q3V…43 chars…",         |
    |       "deviceName":"Kids controller"}  |
```

- The code is never sent over the network; it only appears on the TV screen.
- `code` is a 6-digit **string** (keep leading zeros: `"004321"`). A JSON number is accepted too.
- `deviceName`: 1–32 letters, digits, spaces, `_ . ' -`. Pairing again with the same name replaces the
  old token (use this if the controller loses its token).
- **Store the token in NVS** (use NVS encryption if enabled). It is shown only once; the TV can't recover it.
  The token is 32 random bytes, base64url without padding (43 characters).
- Calling `/pair/start` again while a code is showing keeps the same code (it doesn't make a new one).
- **Wrong codes:** 5 wrong codes kill the current code and lock pairing for 1 minute (`429`, `Retry-After`).
  Wrong codes add up across codes, and each further lockout doubles (2, 4, 8 … minutes, up to 1 hour).
  A successful pairing resets all of this.
- Controllers can be revoked on the TV (Parents). A revoked token gets `401` at once; pair again.

| Response to `/pair/finish` | Meaning | What the firmware should do |
|---|---|---|
| `200 {"token","deviceName"}` | Paired | Save the token |
| `401 {"error":"wrong_code","attemptsLeft":3}` | Wrong code | Let the person retype it |
| `409 {"error":"not_pairing"}` | No code showing (expired or never started) | Start over with `/pair/start` |
| `409 {"error":"too_many_devices"}` | 16 controllers already paired | Revoke one on the TV |
| `429 {"error":"locked","retryAfter":60}` | Too many wrong codes | Show "Try again in N s" |
| `400 {"error":"bad_request"}` | Missing/invalid `code` or `deviceName` | Fix the request |

## Endpoints

All examples assume `TV=192.168.1.50` and `TOKEN=<your token>`.

### `GET /status` — what the TV is doing

```sh
curl -s http://$TV:8765/status -H "Authorization: Bearer $TOKEN"
```
```json
{"screen":"kids","nowPlaying":"Bluey","bedtime":"19:40","minutesToBedtime":null}
```
- `screen`: `"home"`, `"kids"`, `"parents"` or `"app"` (a streaming app is in front).
- `nowPlaying`: title of the show the launcher opened, or `null`.
- `minutesToBedtime`: `null` until bedtime is less than an hour away, then counts down (show a moon).

### `GET /shows` — the kids list, for the e-ink menu

```json
{"shows":[{"id":"bluey","title":"Bluey","service":"Disney+"},
          {"id":"daniel-tiger","title":"Daniel Tiger","service":null}, …]}
```
Use `id` when calling `/play` from a menu: ids always match exactly.

### `POST /play` — play a show by name or id

```sh
curl -s http://$TV:8765/play -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"query":"bluey"}'
```

| Field | | |
|---|---|---|
| `query` | required | Show id (`"puffin-rock"`), title or a loose name (`"super kitty"`), up to 200 characters |
| `audience` | optional | `"kids"` (default) or `"parents"`. **A kids request can only play shows on the kids list.** |

Outcomes (`result` field):

| Status | Body | Meaning | Controller shows |
|---|---|---|---|
| `200` | `{"result":"playing","show":{…}}` | Verified playing | "Now playing: Bluey" |
| `200` | `{"result":"opened_page","show":{…}}` | The show's page is open; the app could not be pressed to Play safely | "Bluey is on the TV — press Play" |
| `202` | `{"result":"started","show":{…}}` | Still opening after 15 s (profile pickers, slow app) | "Opening Bluey…", then poll `/status` |
| `409` | `{"result":"ambiguous","candidates":[{…},{…}]}` | Several shows fit, or a weak guess ("did you mean…?") | The candidates; on pick, `POST /play {"query":"<id>"}` |
| `404` | `{"result":"not_found"}` | No show on the list fits | "I don't know that show" |
| `502` | `{"result":"failed","show":{…}\|null,"reason":"…"}` | The TV tried and failed | "Couldn't open it" |
| `503` | `{"error":"busy"}` | Another show is still being opened | Wait and try again |

`show` objects are `{"id":"bluey","title":"Bluey","service":"Disney+"}` (`service` may be `null`).

**Timeouts:** give `/play` and `/voice` at least **20 s** before giving up (the TV answers within ~15 s).
Don't auto-retry a play that timed out: it is probably still opening. Check `/status` instead.

### `POST /voice` — play from a raw transcript

The voice entry point. Same as `/play`, but `text` is whatever speech-to-text heard, in English or
Spanish, with the command words still in it. Every response also echoes `"heard"` so the e-ink screen
can show what was understood.

```sh
curl -s http://$TV:8765/voice -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"text":"Pon Bluey, por favor"}'
```
```json
{"result":"playing","show":{"id":"bluey","title":"Bluey","service":"Disney+"},"heard":"Pon Bluey, por favor"}
```
`text` is required (up to 500 characters); `audience` works as in `/play`.

### `POST /home` — back to the launcher's Home screen

```sh
curl -s -X POST http://$TV:8765/home -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{}'
```
```json
{"result":"home"}
```

## Errors

Anything that isn't a play outcome is `{"error":"<code>","message":"<human text>"}`:

| Status | `error` | Cause |
|---|---|---|
| 400 | `bad_request` | Body isn't a JSON object, a field is missing/too long, bad `audience`, bad `Content-Length` |
| 401 | `unauthorized` | Missing, wrong or revoked token (re-pair) |
| 403 | `forbidden` | Not on the LAN, or a browser (`Origin` / foreign `Host`) |
| 404 | `no_route` | Unknown endpoint |
| 405 | `method_not_allowed` | e.g. `GET /play` |
| 411 | `length_required` | Chunked body |
| 413 | `too_large` | Body over 4096 bytes |
| 415 | `unsupported_media_type` | `POST` without `Content-Type: application/json` |
| 429 | `locked` | Pairing locked (`retryAfter` seconds, also in `Retry-After`) |
| 500 | `internal` | Bug on the TV side |
| 503 | `busy` | A play is already running |

## How voice plugs in

```
 hold button        16 kHz mono PCM          text                     JSON
 ┌────────┐  mic   ┌──────────────┐  STT   ┌──────────────┐  HTTP   ┌──────────────────┐
 │ ESP32  │ ─────▶ │ record while │ ─────▶ │ speech-to-   │ ──────▶ │ TV: POST /voice  │
 │ button │        │ held (≤ 5 s) │        │ text service │         │ resolver → play  │
 └────────┘        └──────────────┘        └──────────────┘         └──────────────────┘
```

1. While the talk button is held, record 16 kHz mono PCM (≤ 5 s is plenty for "pon Bluey").
2. Send the audio to a speech-to-text service: a cloud API (e.g. Whisper or Google STT) or a local
   Whisper on the laptop/phone. Use language auto-detect (or `es` + `en`); the family speaks both.
   Speech-to-text runs on the controller/phone side; **the TV only ever receives text**.
3. `POST /voice {"text":"<transcript>"}` with the token. The TV strips command words ("play", "put on",
   "pon", "quiero ver", "por favor", "on Netflix"…), tolerates mishearings ("blue e" → Bluey,
   "super kitty" → SuperKitties, "pepa" → Peppa Pig) and plays only from the kids list.
4. Show the outcome: `playing` → "Now playing"; `ambiguous` → list `candidates` and let the child pick
   (then `POST /play {"query":"<id>"}`); `not_found` → "Try again", showing `heard`.

## Minimal Arduino (ESP32) example

```cpp
#include <HTTPClient.h>
#include <ArduinoJson.h>

String playShow(const String& tvIp, const String& token, const String& query) {
  HTTPClient http;
  http.setTimeout(20000);
  http.begin("http://" + tvIp + ":8765/play");
  http.addHeader("Content-Type", "application/json");
  http.addHeader("Authorization", "Bearer " + token);
  JsonDocument req;
  req["query"] = query;
  String body;
  serializeJson(req, body);
  int status = http.POST(body);             // 200, 202, 404, 409, 502, 503…
  String reply = http.getString();
  http.end();
  if (status == 401) return "pair-again";
  JsonDocument res;
  if (deserializeJson(res, reply)) return "error";
  if (res["result"].is<const char*>()) return res["result"].as<String>();  // "playing", "ambiguous"…
  return res["error"] | "error";                                          // "busy", "bad_request"…
}
```

## Trying it from a laptop

`scripts/api-demo.sh <tv-ip> pair` pairs and prints a token; `scripts/api-demo.sh <tv-ip> <token>` walks
through every endpoint with curl.
