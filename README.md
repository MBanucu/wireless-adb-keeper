# Wireless ADB Keeper

Keeps Android **wireless debugging** enabled while Wi-Fi is connected, auto-reconnects
to a chosen Wi-Fi network (silently, via a network suggestion), and keeps a Termux-side
watchdog attached to adbd even though adbd changes port every time it restarts.

Built for unrooted devices where `adb` runs from Termux on the phone itself.

## Why

Wireless debugging turns itself off when Wi-Fi drops, and `adbd` only listens while a
Wi-Fi network is connected. Android also picks a **new random port** every time it
restarts, so a one-shot `adb connect` goes stale immediately.

Neither half can be solved by the app alone:

| Problem | Solved by | Why not the other side |
| --- | --- | --- |
| Re-enable wireless debugging | the app (`WRITE_SECURE_SETTINGS`) | Termux cannot write secure settings |
| Reconnect to Wi-Fi silently | the app (`WifiNetworkSuggestion`) | — |
| Run `adb connect` | Termux | apps cannot run the adb client |
| Learn adbd's port | Termux via mDNS | apps cannot read `/proc/net/tcp`, and adbd's port is not exposed in any property |

## Components

**App (`src/`)** — `com.example.wifidebug`, targetSdk 28, built on device.

- `MonitorService` — foreground service; watches Wi-Fi via `NetworkCallback`, watches the
  secure setting via `ContentObserver`, and re-enables wireless debugging when needed.
- `Ensurer` — the actual setting write, with a 3 s quiet window so its own change
  notification cannot cause a write/notify loop (which would restart adbd repeatedly).
- `WifiSuggestion` — registers a `WifiNetworkSuggestion` marked `setIsMetered(true)`.
  Approve the system notification once; Android reconnects on its own afterwards,
  including after reboots. Being metered and never validating, it does not become the
  default route, so mobile data keeps carrying internet.
- `LocalWifi` — optional local-only mode using `WifiNetworkSpecifier`. This can never be
  silent: Android shows a dialog and requires the passphrase on every reconnect.
  Kept as a non-default mode.
- Boot receiver + periodic job as fallbacks.

**Termux scripts (`*.sh`, `*.py`)**

- `adb-keepalive.sh` — polls `adb devices` every 5 s. On disconnect it tries, cheapest
  first: the previous port, the port advertised over mDNS, then a TCP scan of
  30000–50000. Single instance, wake lock held, rotating log.
- `adb-mdns.py` — reads adbd's own `_adb-tls-connect._tcp` advertisement with
  `python-zeroconf`, filters out stale advertisements, and prints the live port.
  Needs `pip install zeroconf`; without it the keepalive falls back to scanning.
- `reconnect.sh` / `wait_adb.sh` — one-shot reconnect and a bounded "wait for adbd to
  appear" helper, useful after rebooting the phone.

## Requirements

- Android 10+ (network suggestions), tested on Android 15, unrooted.
- On-device build tools: `openjdk-17`, `aapt`, `d8`, `apksigner` (Termux `aapt`,
  `apksigner` and `android-sdk`-style `d8` packages), plus an `android.jar`.
- For the watchdog: `python3`, `zeroconf` (optional), `termux-wake-lock`
  (Termux:API not required for the lock itself; the `termux-wake-lock` binary ships
  with Termux).

## Build

```bash
# put android.jar (API 28+ platform jar) next to build.sh
./build.sh          # compiles, dexes, signs with a generated debug keystore
```

The script creates `debug.keystore` on first run and signs `wifidebug.apk`.

## Install

```bash
adb install -r wifidebug.apk
adb shell pm grant com.example.wifidebug android.permission.WRITE_SECURE_SETTINGS
adb shell pm grant com.example.wifidebug android.permission.ACCESS_FINE_LOCATION
adb shell pm grant com.example.wifidebug android.permission.POST_NOTIFICATIONS
```

Open the app, enter the Wi-Fi SSID and password, tap **Save**, then **Connect now** and
approve the "available networks" notification once. Leave **Connect mode** on
`auto-connect`.

## Watchdog

```bash
pip install zeroconf            # optional, enables mDNS port discovery
./adb-keepalive.sh &            # foreground: omit "&"
```

Log: `adb-keepalive.log`

For start-at-boot, install **Termux:Boot** and place a script in `~/.termux/boot/`
that waits ~20 s and then execs `adb-keepalive.sh`.

## How port discovery works

Android's wireless debugging advertises `_adb-tls-connect._tcp` over mDNS itself, and
the advertisement carries adbd's current port. adb's *client* support for mDNS is
compiled out of common builds (Termux's package and nixpkgs' `android-tools` both
report `mdns is not supported by this version of adb`), but the advertisement is
standard mDNS and any browser can read it — no adb support required.

```
adb-<device>._adb-tls-connect._tcp.local.  port 32997
```

Stale advertisements can outlive the adbd that made them, so the helper only reports
ports that still accept a TCP connection.

## Limitations

- The app cannot reconnect ADB; that is Termux's job (see the table above).
- `WifiNetworkSpecifier` (local-only mode) always needs a tap and the passphrase.
- The TCP scan misses the live port if another scan runs at the same time — hence the
  single-instance guard. Avoid running `reconnect.sh` while the keepalive is scanning.
- adbd only listens while a Wi-Fi network is connected; with Wi-Fi off there is nothing
  to connect to.
