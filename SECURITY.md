# Security

This app runs on a TV in a family home, so the bar is high.

- **No secrets in the repo or the APK.** Anthropic and TMDB keys are typed on the TV (Parents → Keys) and stored
  encrypted with the Android Keystore. Tests use obviously fake values.
- **Local API:** LAN-only, browsers rejected, every command needs a token from a device paired on the TV itself.
  See `docs/controller-api.md`.
- **TV control** (key presses through the TV's own adbd) is off by default and needs USB debugging. Turn it on only if you
  accept that trade-off, and only on a network you trust.
- **Changes to the TV are reversible:** `scripts/snapshot.sh` first, `scripts/restore.sh` to undo, apps disabled never
  uninstalled, and a protected-packages list that the scripts refuse to touch.

Found a vulnerability? Please open a private security advisory on GitHub (Security → Report a vulnerability) rather than a
public issue.
