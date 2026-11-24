# Reboot verdict

`tools/reboot-verdict.sh` records a good pre-reboot state, then checks the
phone's boot-surviving diagnostics after the reboot. It exists because the
session that starts a reboot may die: the evidence has to be on the phone, not
in the process.

## Record, reboot, check

```bash
tools/reboot-verdict.sh record
# reboot the phone and let it unlock
tools/reboot-verdict.sh check
```

The record is a small `key=value` file, by default:

```text
${XDG_STATE_HOME:-$HOME/.local/state}/wireless-adb-keeper/reboot-verdict.state
```

It holds `boot_id`, `uptime_s`, `journal_events`, `mode`, `service`,
`wifi_mode`, `wifi_passphrase`, `suggestion_held` and `port_5555`. It never
holds an SSID, passphrase, token or pairing code.

`record` only reads the diagnostics; it does not reboot the phone. Use
`phone-link-tools/reboot.sh` or the normal power menu.

## What `check` asserts

- `boot_id` changed (a new boot, not a stale reply); a non-decreasing
  `uptime_s` is reported as a warning.
- The monitor service is running and credential storage is unlocked.
- The saved mode is unchanged and `wifi_mode` says `applied`.
- `wifi_passphrase` has no `missing` or `unreadable` profiles.
- In auto-connect mode, a suggestion is held.
- `port_5555` says `listening`.
- The current boot's journal section — from the first `boot.received` at or
  after the recorded time through the end of the live journal; the appended
  previous-install section is ignored — contains:
  - `svc.start`,
  - `svc.mode` matching the saved mode,
  - `pin.state listening`,
  - an active suggestion (`status 0`) or `wifi.suggest.added`/`wifi.suggest.job`.
- No boot-critical failure appears after that boot marker — and nothing from
  the previous-install journal section counts. Codes:
  `svc.start_failed`, `svc.start_refused`, `pin.script_failed`,
  `pin.run_refused`, `pin.port_closed`, `wifi.suggest.decrypt_failed`,
  `wifi.suggest.no_password`, `wifi.suggest.build_failed`,
  `wifi.suggest.refused`, `wifi.secret.undecryptable`.

`boot.start_failed` is reported as a warning when the service later recovered;
it is not a failure by itself because the boot path has a fallback.

## Machine-readable verdict

```bash
tools/reboot-verdict.sh check --json
```

```json
{"schema":1,"ok":true,"boot_id_before":"132","boot_id_after":"133",
 "checks":15,"failed":0,"warnings":0,"failures":[],"warning_messages":[]}
```

## Exit codes

| Code | Meaning |
|---|---|
| 0 | the post-reboot verdict passed |
| 1 | a check failed; the `FAIL` lines name it |
| 2 | usage |
| 3 | blocked: no adb/device, no diagnostics, or no recorded state |

## Limits

- The journal is a 200-line ring. Check soon after boot and leave verbose
  logging off unless the extra detail is needed.
- Selection is wall-clock based: the first `boot.received` at/after the
  recorded time starts the section. If the clock jumps backwards, the tool
  falls back to the last `LOCKED_BOOT_COMPLETED`; a boot with neither marker
  fails the section check.
- The verdict proves the app's side: boot receiver, service, mode, suggestion,
  pin probe and journal. It does not prove the laptop's Wi-Fi path or a remote
  `adb connect`; test that separately when the phone is reachable.
- If the phone is locked, `check` fails on `unlocked`; unlock it and re-run.
