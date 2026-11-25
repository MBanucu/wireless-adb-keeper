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
`wifi_mode`, `wifi_passphrase`, `suggestion_held`, `port_5555` and the kernel
boot id used to recognise the next boot. It never holds an SSID, passphrase,
token or pairing code.

`record` only reads the diagnostics; it does not reboot the phone. Use
`phone-link-tools/reboot.sh` or the normal power menu.

## Pre-unlock samples

A post-unlock snapshot cannot tell a 5555 that was pinned while the phone was
still locked from one that only arrived after the unlock. Boot 135 is the
shape: Wi-Fi associated at 28 s, `pin.waiting` at 44 s, `svc.unlocked` at
139 s, `pin.state listening` only at 174 s. The snapshot taken after the
unlock looks identical in both cases.

`check` therefore samples the phone over USB while it is still booting, at
~27, 45, 60 and 90 s after boot, and again when
`sys.user.0.ce_available` first turns true. Each sample reads
`sys.user.0.ce_available`, `ip -4 addr show wlan0`, `ss -ltn`,
`getprop service.adb.tcp.port`, `getprop service.adb.tls.port`, and probes the
TLS port from the host with `adb connect` (the existing adb key).

Start the check while the phone reboots, with a bound:

```bash
tools/reboot-verdict.sh record
adb reboot
tools/reboot-verdict.sh check --preunlock-wait 180
```

`--preunlock-wait` is how long check may wait for the phone to answer over USB
and, after the last sample, for the first unlock. The default is 0: check does
not wait for a phone that is not there yet, and an unlock later than the 90 s
mark is left to the journal. Reaching the last mark can still take until 90 s
of uptime. A check started after the unlock samples nothing and says why; it
still reports the journal timings.

```text
pre-unlock samples (USB):
  target=27 at=28 late=1 ce=0 wifi=up ip=192.168.77.232 tcp_port=5555 tls_port=33801 port5555=closed tls_probe=ok
  target=45 at=46 late=1 ce=0 wifi=up ip=192.168.77.232 tcp_port=5555 tls_port=33801 port5555=closed tls_probe=ok
  ...
  target=unlock at=139 late=0 ce=1 wifi=up ip=192.168.77.232 tcp_port=5555 tls_port=33801 port5555=closed tls_probe=ok
preunlock.wifi=up
preunlock.tls=33801
preunlock.port5555=closed
unlock_at_s=139
pin_at_s=174
```

| Field | Meaning |
|---|---|
| `target` | the sample mark (27/45/60/90 s) or `unlock` |
| `at` | the uptime when the sample was actually read |
| `late` | `at - target`; a late sample is still labelled with its real time |
| `ce` | `1` when `sys.user.0.ce_available` is true, else `0` |
| `wifi` | `up` when wlan0 has an IPv4 address, `down` otherwise, `unknown` when `ip` is unavailable |
| `ip` | the wlan0 IPv4 address, or `-` |
| `tcp_port` | `getprop service.adb.tcp.port`, or `-` |
| `tls_port` | the TLS port the host `adb connect` proved, or `-` |
| `port5555` | `open`, `closed`, or `unknown` when `ss` is unavailable |
| `tls_probe` | `ok`, `fail`, or `skipped` (no reachable TLS candidate) |

`preunlock.wifi`, `preunlock.tls` and `preunlock.port5555` summarize the
samples taken while `ce_available` was false; the `unlock` sample is not part
of that summary. `preunlock.port5555=open` wins when 5555 was seen open in any
pre-unlock sample. `unlock_at_s` and `pin_at_s` prefer the journal's first
`svc.unlocked` and `pin.state listening` timestamps, so they are also available
when the run started too late to sample.

A check run before the reboot does not sample the old boot as if it were the
new one: `record` keeps the kernel boot id, and an unchanged id stops the
sampler.

### Require a pre-unlock port

```bash
tools/reboot-verdict.sh check --preunlock-wait 180 --require-preunlock-port
```

`--require-preunlock-port` is off by default (until the boot pin lands). When
it is set, a pre-unlock sample that saw 5555 **closed** while Wi-Fi was up and
`ce_available` was false fails the verdict. A run that captured no pre-unlock
sample warns that the window was not measured rather than failing: the flag
never turns missing evidence into a pass or a failure.

## What `check` asserts

- `boot_id` changed (a new boot, not a stale reply); a non-decreasing
  `uptime_s` is reported as a warning.
- The monitor service is running and credential storage is unlocked.
- The process started soon after boot: `process_started_after_boot_s` must be
  at most `--max-start-delay` seconds (default 90). A later start fails and
  names the two gates that let a boot start slip:
  - the DokeOS **Auto Start Management** allowlist, and
  - the **Doze/battery optimization** exemption.
- The saved mode is unchanged and `wifi_mode` says `applied`.
- `wifi_passphrase` has no `missing` or `unreadable` profiles.
- In auto-connect mode, a suggestion is held.
- `port_5555` says `listening`.
- The current boot's journal section — from the first `boot.received` at or
  after the recorded time through the end of the live journal; the appended
  previous-install section is ignored — starts near the boot instant:
  `marker_epoch - uptime_at_marker` is the boot instant, so the marker's own
  uptime is how late it ran. More than `--max-start-delay` seconds after boot
  fails: a marker that late looks like the app was launched by hand. A clean
  boot runs the receiver at ~27 s and the service at ~30 s.
- The section also contains:
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

`boot.received LOCKED_BOOT_COMPLETED ... before unlock` is the expected shape
and passes. `after unlock` means the phone stayed locked past the broadcast:
that is a **warning** on its own, never a failure, because the delayed marker
is legitimate. When it is paired with a late process start (over
`--max-start-delay`) it fails, since the app was then started by the unlock or
a hand launch rather than by the system broadcast.

## Start-delay bound

The default bound is 90 s. Override it when a slower device legitimately needs
more time:

```bash
tools/reboot-verdict.sh check --max-start-delay 150
```

The same bound applies to the process start and to the first boot marker. A
non-numeric value is a usage error (exit 2).

A late process start fails only when the journal has no on-time marker to
contradict it. When the first `boot.received` did arrive inside the bound, the
broadcast started the app and a late `process_started_after_boot_s` is a
*restart* — a force-stop, a crash, a reinstall or Doze — so it is reported as a
warning that says so, not as an autostart-gate failure.

## Machine-readable verdict

```bash
tools/reboot-verdict.sh check --json
```

```json
{"schema":1,"ok":true,"verdict":"pass","boot_id_before":"132","boot_id_after":"133",
 "checks":18,"failed":0,"warnings":0,"max_start_delay_s":90,"start_delay_s":29,
 "boot_marker_delay_s":27,"boot_epoch":1795510860,
 "preunlock_wifi":"up","preunlock_tls":33801,"preunlock_port5555":"closed",
 "unlock_at_s":139,"pin_at_s":174,"preunlock_samples":[
   {"target":"27","at_s":28,"ce_available":false,"wifi":"up",
    "ip":"192.168.77.232","tcp_port":5555,"tls_port":33801,
    "port5555":"closed","tls_probe":"ok"}],
 "failures":[],"warning_messages":[]}
```

| Field | Meaning |
|---|---|
| `ok` / `verdict` | `true`/`pass`, or `false`/`fail` |
| `start_delay_s` | `process_started_after_boot_s`, or `null` when unreadable |
| `boot_marker_delay_s` | how many seconds after boot the first `boot.received` ran, or `null` |
| `boot_epoch` | the boot instant derived from that marker (`marker_epoch - uptime`), or `null` |
| `max_start_delay_s` | the bound in force for this run |
| `preunlock_wifi` | `up`/`down`, or `null` when no pre-unlock sample was taken |
| `preunlock_tls` | the TLS port reached before unlock, or `null` |
| `preunlock_port5555` | `open`/`closed`, or `null` when no sample or no `ss` |
| `unlock_at_s` / `pin_at_s` | journal uptime of `svc.unlocked` / `pin.state listening`, or `null` |
| `preunlock_samples` | the per-sample objects; empty when the window was missed |

`checks` counts `ok` plus `FAIL` lines; warnings do not add to it.

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
- The live `unlocked` field must be `true` at check time; a locked phone fails
  that check, so unlock it and re-run. This is separate from the journal's
  `before unlock`/`after unlock` marker, which only warns on its own.
- The pre-unlock window is only visible while `check` is running. A run started
  after the unlock reports the journal's `unlock_at_s`/`pin_at_s` but no
  `preunlock.*` values; with `--require-preunlock-port` that is a warning, not
  a pass.
- The TLS probe uses the host adb server's key. A blocked port or a host that
  is not on the phone's network shows `tls_probe=fail` or `skipped`; the
  sample still records Wi-Fi, `port5555` and the TCP port.

## See also

[README](../README.md) · [Build](build.md) · [Install](install.md) · [Control](control.md) · [Boot](boot.md) · [Architecture](architecture.md) · [Limits](limits.md) · [Diagnostics](diagnostics.md) · [Pre-unlock connect](preunlock-connect.md) · [Termux build](termux-build.md)
