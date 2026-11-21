# Wireless ADB Keeper

Keeps Android **wireless debugging** enabled while Wi-Fi is connected, auto-reconnects
to a chosen Wi-Fi network (silently, via a network suggestion), keeps a Termux-side
watchdog attached to adbd even though adbd changes port every time it restarts, and
**pins adbd to a fixed TCP port (5555)** so `adb connect <phone>:5555` keeps working.

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
| Pin adbd to port 5555 | Termux (the app asks it to) | `tcpip` is an adb *service request*: it needs an adb client holding a paired key, and the property it sets (`service.adb.tcp.port`) is refused to `setprop` from the shell user |

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
- `PinnedPort` — probes `127.0.0.1:5555` every minute and, when nothing listens, asks
  Termux to run `pin-adb-port.sh` through Termux's own `RUN_COMMAND` API. All the
  decisions (when to ask, how often, whether Termux is usable) live here.
- Boot receiver + periodic job as fallbacks.

**Termux scripts (`*.sh`, `*.py`)**

- `adb-keepalive.sh` — polls `adb devices` every 5 s. On disconnect it tries, cheapest
  first: the pinned port 5555, the previous port, the port advertised over mDNS, then a
  TCP scan of 30000–50000. Single instance, wake lock held, rotating log.
- `pin-adb-port.sh` — attaches to adbd (a serial the keepalive already holds, then
  mDNS, then a scan) and sends `adb tcpip 5555`. Run by the app, not by hand.
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

The script creates `debug.keystore` on first run and signs `wifidebug.apk`. If
`android.jar` is not next to `build.sh`, the newest one under
`$HOME/android-sdk/platforms/` is used.

Updating an installed copy needs the **same signing key**: pass `--ks` to
`apksigner` with the key the installed build was signed with, or Android refuses the
update as a signature mismatch (an uninstall also clears the app's saved settings).

## Install

```bash
adb install -r wifidebug.apk
adb shell pm grant com.example.wifidebug android.permission.WRITE_SECURE_SETTINGS
adb shell pm grant com.example.wifidebug android.permission.ACCESS_FINE_LOCATION
adb shell pm grant com.example.wifidebug android.permission.POST_NOTIFICATIONS
adb shell pm grant com.example.wifidebug com.termux.permission.RUN_COMMAND
```

The `RUN_COMMAND` grant is only needed for the fixed port (see below), and only
works once the installed APK declares it — granting it to an older build is a
silent no-op.

Open the app, enter the Wi-Fi SSID and password, tap **Save**, then **Connect now** and
approve the "available networks" notification once. Leave **Connect mode** on
`auto-connect`.

## Fixed port 5555

`adb` is told to listen on a fixed port, so a script on the other side does not have to
rediscover adbd's port after every restart. The port is set with `adb tcpip 5555`, which
is an adb *service request*: it needs an adb client whose key adbd already accepts.

The app cannot be that client — neither the client nor the key is available to it — so
it uses the one the phone already has: **Termux's adb**, which is paired with adbd. When
`127.0.0.1:5555` stops accepting connections, `PinnedPort` sends a `RUN_COMMAND` intent
to Termux, Termux runs `pin-adb-port.sh`, and the script attaches to adbd (the serial the
keepalive already holds, then mDNS, then a scan) and sends `tcpip:5555`.

Requirements:

- Termux from F-Droid/GitHub with `allow-external-apps=true` and a working `adb`.
- This checkout **at `~/wireless-adb-keeper`** in Termux — that is the path the app
  calls. (`PinnedPort.SCRIPT` is the one place to change if it lives elsewhere.)
- `pm grant com.example.wifidebug com.termux.permission.RUN_COMMAND`, and an APK whose
  manifest declares it (1.6+).
- The `INTERNET` permission. Not for the network as such: without it Android refuses
  even a loopback `socket()`, which made the watchdog report the port as closed while it
  was listening perfectly well.

Measured on this phone (Android 15, unrooted):

| event | 5555 closed → reachable |
| --- | --- |
| `adb usb`, monitor service running | ≤ 30 s (next one-minute tick) |
| reboot, without touching anything | ≈ 60 s after `sys.boot_completed` (boot receiver → ask → `tcpip`) |

The Termux script itself takes about a second once it has a serial to talk to. Pinning
survives the keeper re-enabling wireless debugging: adbd restarts with a fresh TLS port
*and* keeps listening on 5555 (measured: TLS 46015 and 5555 listening at the same time).

Caveats:

- `tcpip` mode is **unencrypted and authorised by RSA key only** — unlike wireless
  debugging, which is TLS. Use it on the network you control (the phone-link AP), never
  on a public one. `adb usb` removes it again.
- The property is not persistent: a reboot clears it, which is why the app re-pins at
  every boot instead of trying to make it survive on its own.
- Only needed if something wants the phone at a predictable port. The mount transport
  is sshd on 8022, which is fixed already.

## Watchdog

```bash
pip install zeroconf            # optional, enables mDNS port discovery
./adb-keepalive.sh &            # foreground: omit "&"
```

Log: `adb-keepalive.log`

For start-at-boot, install **Termux:Boot** and place a script in `~/.termux/boot/`
that waits ~20 s and then execs `adb-keepalive.sh`.

If Shizuku is installed, the keepalive also restarts its shell server after every
reconnect (and once a minute if it was killed), as long as a `start-shizuku`
command exists on `PATH`. Shizuku's server must be started by adb after each
reboot, so this keeps `rish` working without extra steps.

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
- Pinning to 5555 needs Termux: without it the app still keeps wireless debugging on,
  it just cannot make adbd listen on a fixed port.
- `WifiNetworkSpecifier` (local-only mode) always needs a tap and the passphrase.
- The TCP scan misses the live port if another scan runs at the same time — hence the
  single-instance guard. Avoid running `reconnect.sh` while the keepalive is scanning.
- adbd only listens while a Wi-Fi network is connected; with Wi-Fi off there is nothing
  to connect to.
