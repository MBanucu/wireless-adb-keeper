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
| Run `adb connect` | Termux, or the app's own client for the pin | the bootstrap APK has no adb client |
| Learn adbd's port | app: `NsdManager` mDNS, then local listening ports; Termux fallback | adbd's port is not exposed in any property |
| Pin adbd to port 5555 | app: `tcpip:5555` over its own trusted TLS key; Termux fallback | only a client whose key adbd already accepts can send the service request |
| Turn the Wi-Fi radio on | app: `shell:svc wifi enable` over the trusted TLS key; Termux fallback | Android 10+ forbids `WifiManager.setWifiEnabled` for apps |

## Requirements

- Android 10+ (network suggestions), tested on Android 15, unrooted.
- Bootstrap APK (`build.sh`): `openjdk-17`, `aapt`, `d8`, `apksigner` (Termux
  `aapt`, `apksigner` and `android-sdk`-style `d8` packages), plus an
  `android.jar`.
- Canonical APK (`tools/termux-build.sh`): JDK 17+, Android SDK platform 35, and
  Termux's bionic `aapt2` (`pkg install aapt`). The wrapper pins Gradle 9.9.2
  and AGP 9.5.2.
- For the watchdog: `python3`, `adb-harness[mdns]==0.2.3` (optional; enables
  mDNS discovery — `bash tools/setup-adb-harness.sh`), `termux-wake-lock`
  (Termux:API not required for the lock itself; the `termux-wake-lock` binary ships
  with Termux).

## Quickstart

Five minutes on a phone that is already reachable with `adb`:

```bash
# 1. build the complete APK (Termux, or a host with JDK 17, SDK 35 and aapt2)
tools/termux-build.sh

# 2. install it and grant the permissions it needs
PHONE_SERIAL=192.168.77.232:5555 ./install.sh

# 3. add the Wi-Fi network in the app, tap Save, then Connect now,
#    and approve the "available networks" notification once
# 4. confirm that the monitor and the fixed port are up
./keeper.sh status
./keeper.sh diag | grep -E 'pin.state|oem.autostart'
```

On a DokeOS phone, also allow the app in **System Manager → App Management →
Auto Start Management**; `./install.sh --autostart` drives that row for you.

With no laptop at all, stay inside Termux: `./build.sh` builds the dependency-free
APK, `./pair-local.sh 123456` pairs Termux's adb the first time, and
`./adb-keepalive.sh` keeps the connection. That APK keeps wireless debugging on
and opens the Wi-Fi panel, but leaves pinning 5555 and switching Wi-Fi on to
Termux; see [Limits](docs/limits.md).

## Documentation

The README stays a front page. Everything else is one click away:

| Page | Sections |
| --- | --- |
| [Build](docs/build.md) | [the two builds](docs/build.md#the-two-builds), [bootstrap](docs/build.md#bootstrap-no-gradle), [Gradle](docs/build.md#canonical-gradle-build), [dependency policy](docs/build.md#dependency-policy), [probes and CI](docs/build.md#probes-and-ci), [pairing](docs/build.md#pairing), [pair from Termux](docs/build.md#pair-from-termux-no-laptop), [spike findings](docs/build.md#spike-findings) |
| [Install](docs/install.md) | [installing the APK and the grants](docs/install.md#install), [the DokeOS autostart row](docs/install.md#install), [the fixed port 5555](docs/install.md#fixed-port-5555) |
| [Control](docs/control.md) | [control from adb](docs/control.md#control-from-adb), [profiles from the command line](docs/control.md#profiles-from-the-command-line), [control from Termux](docs/control.md#control-from-termux-no-adb), [the `RESULT` contract](docs/control.md#script-result-contract) |
| [Boot](docs/boot.md) | [reboot and unlock](docs/boot.md#reboot-and-unlock), [the two DokeOS boot gates](docs/boot.md#two-independent-boot-gates-dokeos), [connect before unlock](docs/boot.md#connect-before-unlock-host) |
| [Architecture](docs/architecture.md) | [components](docs/architecture.md#components), [how port discovery works](docs/architecture.md#how-port-discovery-works), [the watchdog](docs/architecture.md#watchdog) |
| [Limits](docs/limits.md) | [what the app cannot do](docs/limits.md) |
| [Diagnostics](docs/diagnostics.md) | [the Diag tab and `diag`](docs/diagnostics.md#diagnostics-from-the-app-or-adb), [snapshot fields](docs/diagnostics.md#snapshot-fields), [reason codes](docs/diagnostics.md#reason-codes) |
| [Reboot verdict](docs/reboot-verdict.md) | [recording a pre-reboot state and proving what survived](docs/reboot-verdict.md) |
| [Pre-unlock connect](docs/preunlock-connect.md) | [attaching a host to adbd while the phone is still locked](docs/preunlock-connect.md) |
| [Termux build](docs/termux-build.md) | [the on-device Gradle build in depth](docs/termux-build.md) |
