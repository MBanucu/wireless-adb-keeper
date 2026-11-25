# Architecture

```bash
./adb-keepalive.sh     # the Termux watchdog, one of the components below
```

## Components

**App (`src/`)** — `io.github.mbanucu.wirelessadbkeeper`, targetSdk 35, compiled by
both the canonical Gradle build and the on-device bootstrap.
The name says whose app it is: `com.example` is the placeholder the docs reserve for
samples and cannot be published, so it is the GitHub-handle form instead.

- `MonitorService` — foreground service; watches Wi-Fi via `NetworkCallback`, watches the
  secure setting via `ContentObserver`, and re-enables wireless debugging when needed.
- `Ensurer` — the actual setting write, with a 3 s quiet window so its own change
  notification cannot cause a write/notify loop (which would restart adbd repeatedly).
- `WifiSuggestion` — registers one `WifiNetworkSuggestion` per profile, each marked
  `setIsMetered(true)`, using that profile's saved passphrase (none for an open or OWE
  network), WPA3 when the scan says the AP is WPA3-only, and enhanced open for OWE. A
  per-profile applied record under `suggestion_applied.<id>` lets one profile be removed
  without touching the others. Approve the system notification once; Android reconnects
  on its own afterwards, including after reboots. Being metered and never validating, it
  does not become the default route, so mobile data keeps carrying internet.
- `LocalWifi` — optional local-only mode using `WifiNetworkSpecifier`. This can never be
  silent: Android shows a dialog on every reconnect, and the app supplies the saved
  passphrase. Kept as a non-default mode.
- `Profiles` — the saved-network list in the `local_wifi` prefs: ordered opaque ids
  (`default`, `p1`, ...), one profile per SSID, capped at 16. Passphrases stay only in
  the encrypted `PassphraseStore`, under `profile.<id>`. The first read of an upgraded
  install turns the old single SSID and passphrase into `default`, so nothing has to be
  retyped. The list is also available from adb/Termux through `keeper.sh profiles`.
- `NetworkRows` — the Network page without Android types: one row per profile in try
  order with its security badge, password state, last suggestion status and the active
  marker, plus the summary and delete-confirmation text.
- `ControlProvider` — the adb command surface: the same actions the buttons run,
  reachable with `adb shell content call` and readable through `keeper.sh`.
  `ControlReceiver` is the same dispatch over an ordered broadcast, the only
  control channel an ordinary app uid can reach (see [Control from Termux](control.md#control-from-termux-no-adb)).
- `PinnedPort` — probes `127.0.0.1:5555` every 10 seconds and, when nothing listens, asks
  Termux to run `pin-adb-port.sh` through Termux's own `RUN_COMMAND` API. All the
  decisions (when to ask, how often, whether Termux is usable) live here.
- `WifiRadio` — switches the Wi-Fi radio on. Apps lost that right in Android 10, so the
  Gradle build sends `shell:svc wifi enable` over the app's trusted TLS key. Termux's
  `wifi-on.sh` stays as the fallback for an unpaired install and for `build.sh`, and
  the Wi-Fi panel opens when neither client can help. The journal names the client
  that took the request (`WifiRoute`).
- Boot receiver + periodic job as fallbacks.

**Termux scripts (`*.sh`, `*.py`)**

- `adb-keepalive.sh` — polls `adb devices` every 5 s. On disconnect it tries, cheapest
  first: the pinned port 5555, the previous port, the port advertised over mDNS, then a
  TCP scan of the kernel's ephemeral range (32768–60999 when
  `/proc/sys/net/ipv4/ip_local_port_range` is readable, which it is not from Termux).
  Single instance, wake lock held while running and released on exit, rotating log.
- `pin-adb-port.sh` — attaches to adbd (a serial the keepalive already holds, then
  mDNS, then the phone's TCP table or a scan) and sends `adb tcpip 5555`. Run by the
  app, not by hand. Its last stdout line is `RESULT pinned=5555` (or
  `RESULT error=no-adbd`); see [Script result contract](control.md#script-result-contract).
- `pair-local.sh` — one-time bootstrap for a phone that has never been paired:
  pairs adbd over loopback and prints `uid=2000(shell)` as proof. See
  [Pair from Termux, no laptop](build.md#pair-from-termux-no-laptop).
- `keeper.sh` — laptop/Termux-side wrapper that maps friendly command names onto the
  app's content provider, and prints the `key=value` reply.
- `keeperctl.sh` — the same friendly commands straight from a Termux prompt, without
  adb, using the one-time control token. See [Control from Termux](control.md#control-from-termux-no-adb).
- `wifi-on.sh` — turns the Wi-Fi radio on with `svc wifi enable` over Termux's adb,
  which keeps working with the radio off because it goes through loopback. Tries the
  pinned port first, then any serial adb already holds. This is the fallback for an
  install whose app key is not paired and for the `build.sh` APK; the Gradle build
  sends the same request over its own TLS connection first. Ends with
  `RESULT wifi=on` or `RESULT error=...`.
- `adb-connect` — the mDNS browser from `adb-harness[mdns]` (pinned in
  `tools/requirements.txt`). It reads adbd's own `_adb-tls-connect._tcp` or
  `_adb-tls-pairing._tcp` advertisement and hands the live port to adb; for a
  connect port it only reports one that `adb connect` reaches, so stale records
  are discarded. `discover.sh` runs it from
  `tools/.venv` (created by `tools/setup-adb-harness.sh`) or from `PATH`; without
  it the keepalive falls back to scanning. The local `adb-mdns.py` fork is gone
  (#68), and `tools/check-adb-harness.sh` fails if one grows back.
- `reconnect.sh` / `wait_adb.sh` — one-shot reconnect and a bounded "wait for adbd to
  appear" helper, useful after rebooting the phone. Both try the pinned port first;
  `wait_adb.sh` ends with `RESULT connected=<port>` or `RESULT error=timeout`.
- `discover.sh` / `scan_ports.py` — the one discovery implementation: shared port
  range, pinned-port default, mDNS, the phone's `/proc/net/tcp` table, the loopback
  scan, and connect-and-verify. Sourced by the other scripts so they cannot drift
  apart again.
- Reboot/unlock live in the sibling `phone-link-tools` repository: `reboot.sh`
  waits for the new boot, `unlock.sh` clears the keyguard.
- `autostart-allow.sh` — drives DokeOS's autostart list over adb, because no
  grant reaches it. `--check "Wireless ADB Keeper"` reports without toggling.
- `keeper.sh autostart` — the fast path to the same row: the app's own
  accessibility service reads that one window and clicks the switch, about a
  second or two instead of the ~35 s screen walk. The vendor screen has to be
  open first (`adb shell am start -n
  com.blackview.systemmanager/com.freeme.freemanager.autoboot.AutoStartMainActivity`),
  because Android refuses that activity start from a background app; both
  `install.sh --autostart` and `autostart-allow.sh` open it. `autostart-allow.sh`
  also stays as the fallback where the service cannot be enabled, and its
  `--check` remains the independent read.
## How port discovery works

Android's wireless debugging advertises `_adb-tls-connect._tcp` over mDNS itself, and
the advertisement carries adbd's current port. adb's *client* support for mDNS is
compiled out of common builds (Termux's package and nixpkgs' `android-tools` both
report `mdns is not supported by this version of adb`), but the advertisement is
standard mDNS and any browser can read it — no adb support required.

```
adb-<device>._adb-tls-connect._tcp.local.  port 32997
```

`discover.sh` runs `adb-connect` from `adb-harness[mdns]` (pinned in
`tools/requirements.txt`) for that lookup: it browses the service and only reports a
port after `adb connect` reaches a device, so a stale advertisement outliving its
adbd is discarded. `pair-local.sh` uses the same command with `--pair` for
`_adb-tls-pairing._tcp`. The CLI is the only supported interface; the local
zeroconf fork was deleted in #68 and `tools/check-adb-harness.sh` keeps it out.

When an adb serial is already attached, `discover.sh` asks the phone instead:
`adb shell cat /proc/net/tcp /proc/net/tcp6` lists every listening socket (state `0A`,
local port in hex) in one round trip. That is far cheaper than opening tens of
thousands of loopback connections, and it falls back to the scan when no serial is
attached or the tables are empty. The shared scan covers the kernel's ephemeral range and is the last resort.
## Watchdog

```bash
bash tools/setup-adb-harness.sh  # optional, enables mDNS port discovery
./adb-keepalive.sh &             # foreground: omit "&"
```

Log: `adb-keepalive.log`

For start-at-boot, install **Termux:Boot** and place a script in `~/.termux/boot/`
that waits ~20 s and then execs `adb-keepalive.sh`.

If Shizuku is installed, the keepalive also restarts its shell server after every
reconnect (and once a minute if it was killed), as long as a `start-shizuku`
command exists on `PATH`. Shizuku's server must be started by adb after each
reboot, so this keeps `rish` working without extra steps.

## See also

[README](../README.md) · [Build](build.md) · [Install](install.md) · [Control](control.md) · [Boot](boot.md) · [Limits](limits.md) · [Diagnostics](diagnostics.md) · [Reboot verdict](reboot-verdict.md) · [Pre-unlock connect](preunlock-connect.md) · [Termux build](termux-build.md)
