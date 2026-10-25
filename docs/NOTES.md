# Design notes and findings

What we decided while building Family TV, and what we learned on a real Hisense Google TV (Android TV 11, US,
October 2026). Code comments point to the sections below.

## Guiding principles (safety)
1. **Nothing is deleted.** Apps are only turned off with `pm disable-user --user 0`; `scripts/restore.sh` puts the TV
   back to its snapshot.
2. **Emulator first.** Every feature runs on the Android TV emulator before it touches the real TV.
3. **Never disturb a TV in use.** Before any install, key press or navigation over adb: no media session playing, no
   recent remote keys. If it's in use, wait.
4. **Never press OK/Play blind.** The launcher only presses Play after it has verified the screen shows the intended
   title (see "Deep links").
5. **Controllers are paired.** The local API accepts only paired devices with a token, only on the home network, and
   pairing can only be started on the TV.
6. **Small, reversible steps,** each with a checkpoint.

## Never-disable list
If the physical remote is lost, the phone remote (Google Home / Google TV app) is the only remote. Its service
(`com.google.android.tv.remote.service`), Google Play services, the keyboard, Bluetooth, network and setup packages,
and the TV vendor's core (tuner, inputs, picture, updates) must stay on. `scripts/protected-packages.txt` lists them and
`scripts/disable.sh` refuses to touch them.

## Architecture
```
Phone remote (Google TV app) ──► TV Remote Service ──► assistant role ──► Ask the TV (mic)
E-paper remote / curl ──Wi-Fi──► Local API :8765 (paired) ──┐
                                                            ▼
            Family TV launcher (Kotlin, Compose for TV, runs on the TV)
             ├─ Home · Kids · Parents screens
             ├─ Playback engine: deep links, cover screen, verification (accessibility), kids guard
             ├─ TV control: key presses through the TV's own adbd on 127.0.0.1 (dadb), off by default
             ├─ Bedtime service: countdown pill → Goodnight → pause → TV off
             ├─ Show resolver (EN/ES) · watch log · recommendations (TMDB + Claude)
             └─ Ask the TV: speech (TV recognizer) → router → Claude Haiku → overlay card
```
The launcher runs on the TV, so it opens other apps directly with Android intents; no hub computer is needed.

## Profiles
Each streaming service has a "Who's watching?" screen, and a link sent to a cold app lands there first. The launcher
picks a fixed profile per service: edit `Family.kt` (`DISNEY_PROFILE`, `NETFLIX_PARENT_ROW`, `NETFLIX_KIDS_ROW`).
Prime Video focuses the last profile used.

## Content policy
The goal isn't just a rating cutoff: **no previews of shows nobody chose, and no shows built to sell toys.**
- The kids' side shows shows, not apps. Each tile deep-links straight into the show, behind a full-screen paper
  "OPENING · <show>" cover, so the kids never see an app's home page, rows or trailers.
- Allowlist only. A franchise blocklist (`recs/FranchiseBlocklist.kt`) is a second check when picks are suggested.
- In each app, as a second layer: kids profiles, autoplay previews off, and title restrictions where the app has them.
- Kids' picks come only from ad-free services.

## Parents screen
No PIN, Apple-style (Inter, white rounded cards). Apps, Continue Watching (from the launcher's own launch log; other
apps' progress needs a system-only permission), For You (Claude + TMDB), the kids' educational picks to approve, the
bedtime editor, devices (pairing, TV control) and keys.

## Kids list and kids recommendations
- **"Our shows"**: the shows the kids already watch (`Catalog.kt`), growing as they watch from the launcher.
- **"Something new"**: candidates from TMDB (kids genre, TV-Y, on your services, US), toy franchises removed before
  Claude sees them. Claude scores educational value and writes one line on what they learn. Picks wait in Parents
  for Approve / Skip.

## Secrets and network security
- Use a dedicated Anthropic API key for the TV, with a monthly spend limit set in the Claude Console.
- **Keys are never in the code or the APK.** They're typed once in Parents (the phone keyboard works) and stored
  encrypted with the Android Keystore (`secrets/SecretStore.kt`).
- The local API has no endpoint that returns secrets, requires a pairing token, accepts LAN addresses only, rejects
  browsers (`Origin`, `Host` checks) and allows only fixed commands.
- HTTP clients never follow redirects (a redirect could carry a key to another host), and nothing logs requests.

## Deep links
Findings from the real TV:
- **Disney+:** `https://www.disneyplus.com/browse/entity-<uuid>` works; old `/series/<slug>/<id>` links stop at the series
  page. Its screen is readable through accessibility, so the launcher verifies the profile screen and the show page
  before pressing Play, then checks the media session title.
- **Prime Video:** `https://app.primevideo.com/detail?gti=amzn1.dv.gti.<uuid>` (the gti from the primevideo.com page; store
  ASINs don't work). Every incoming link shows "Who's watching?". Its screen can't be read, so the media title is checked
  right after Play and anything else is paused. Watch out for shows that need a paid channel: their focused button can be
  a free-trial or purchase button. Only add shows included with your subscription.
- **Netflix:** blocks screenshots, exposes no accessibility tree and reports no media title, so nothing can be verified.
  A blind OK once started the home-screen billboard on a kids' profile instead of the requested show. That's why the
  launcher never presses OK on Netflix and Netflix launches are off by default (`NETFLIX_VERIFIED`).
- **Screenshots** on some Hisense builds: `adb exec-out screencap` output gets corrupted. Use
  `screencap -p /sdcard/s.png` and `adb pull`.

## TV control
The TV Remote Service refuses connections from the TV itself ("is local, ignoring connection"), so the launcher can't
use the phone-remote protocol to press keys. The on-device option is the TV's own adbd on 127.0.0.1 (dadb): Parents →
Devices → TV control → Turn on shows "Allow USB debugging?" once. It only sends `input keyevent <ints>`. It needs USB
debugging to stay on, so it's off by default and a parent's choice.

## Ask the TV
**How the phone's mic button reaches an app** (from the TV Remote Service's behavior and logs):
- The phone mic makes the remote service call `SearchManager.launchAssist`, which opens **the TV's assistant app**.
- When the assistant isn't Google's, the service registers an ad hoc `AudioPolicy` mix matched on the
  `VOICE_RECOGNITION` capture preset and plays the phone's audio into it. Any recognizer recording with that source hears
  the phone.
- So making the launcher the assistant gives it the mic everywhere: `scripts/mic-button.sh ask` (undo: `google`).
  Google Assistant stops answering the mic button; the TV's speech recognizer (Katniss) keeps working and is what Ask the
  TV uses.

**Flow:** mic → `AssistActivity` (no window, starts a microphone foreground service) → "Listening…" pill → Android
`SpeechRecognizer` bound to the TV's recognizer → `AskRouter`:
1. Kids context (kids screen, or a kids show playing): only an approved show by name, or pause. Never Claude.
2. Local commands in EN/ES: pause, resume, sleep timer ("TV off at 10:42"), TV off, volume, Home, open an app, play a show.
3. Questions → Claude Haiku 5.5 at low effort: one structured-output call (`short | answer | picks | search`) with
   what's playing, TMDB facts fetched while you talk, the watch history, For You picks, and the last turn
   (follow-ups within a minute).
4. When Claude says it needs today's facts, a second call uses Anthropic's web search (at most 2 searches) while the
   card keeps "thinking". It's plain text, because web search always returns citations and citations can't be
   combined with structured output. The cited site becomes the card's source line.

**Display:** the UI only uses the top edge; the show keeps the bottom (subtitles, player bar). Over a show it's an ink
card at the top left (no blur: Android 11 can't blur behind overlays). On Home it's a paper card over the dimmed
screen. The window takes key focus so Back closes the card instead of leaving the player; volume and media keys fall
through to the playing app.

**Found on the real TV:**
- The mic opened Ask the TV through SystemUI on the first try, and the phone's audio reached the recognizer.
- The remote service opens the assistant twice per press, 1.5–4 s apart. Restarting on the second open cancelled
  the recognizer mid-sentence, so a second open while listening is now ignored.
- The TV's recognizer takes exclusive audio focus while listening, so the player pauses for those seconds and resumes
  after, the same as Google Assistant.

**Cost:** about $0.0005 per question on Haiku 5.5; a question that needs the web adds about 1–2¢.

## TV telemetry
What a Hisense Google TV sends, found with `dumpsys netstats` (upload per app), `/proc/net/tcp` (live connections) and
the vendor APKs:
- **Hisense ACR** (`com.hisense.evservice`, `/system_ext/app/ACR`): a foreground service that fingerprints the TV's audio
  and keeps an MQTT connection on port 1883 (unencrypted) to the vendor's cloud. Its code has an upload switch for an ad
  partner.
- Google usage & diagnostics (`global multi_cb`) and crash reports (`send_action_app_error`) were on.
- `scripts/privacy.sh` turns ACR off (setting plus disabling the app), turns Google diagnostics and crash reports off
  and disables the VIDAA content tab. `--check` shows the state (a vendor update may turn ACR back on) and `--undo`
  restores everything.
