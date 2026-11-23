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

**App (`src/`)** — `io.github.mbanucu.wirelessadbkeeper`, targetSdk 35, built on device.
The name says whose app it is: `com.example` is the placeholder the docs reserve for
samples and cannot be published, so it is the GitHub-handle form instead.

- `MonitorService` — foreground service; watches Wi-Fi via `NetworkCallback`, watches the
  secure setting via `ContentObserver`, and re-enables wireless debugging when needed.
- `Ensurer` — the actual setting write, with a 3 s quiet window so its own change
  notification cannot cause a write/notify loop (which would restart adbd repeatedly).
- `WifiSuggestion` — registers a `WifiNetworkSuggestion` marked `setIsMetered(true)`,
  using the saved passphrase (none for an open or OWE network), WPA3 when the scan says
  the AP is WPA3-only, and enhanced open for OWE. Approve the system notification once;
  Android reconnects on its own afterwards, including after reboots. Being metered and
  never validating, it does not become the default route, so mobile data keeps carrying
  internet.
- `LocalWifi` — optional local-only mode using `WifiNetworkSpecifier`. This can never be
  silent: Android shows a dialog on every reconnect, and the app supplies the saved
  passphrase. Kept as a non-default mode.
- `ControlProvider` — the adb command surface: the same actions the buttons run,
  reachable with `adb shell content call` and readable through `keeper.sh`.
- `PinnedPort` — probes `127.0.0.1:5555` every 10 seconds and, when nothing listens, asks
  Termux to run `pin-adb-port.sh` through Termux's own `RUN_COMMAND` API. All the
  decisions (when to ask, how often, whether Termux is usable) live here.
- `WifiRadio` — switches the Wi-Fi radio on. Apps lost that right in Android 10, so it
  asks Termux to run `wifi-on.sh` (`svc wifi enable` as the shell user) and falls back
  to opening the Wi-Fi panel when Termux cannot help.
- Boot receiver + periodic job as fallbacks.

**Termux scripts (`*.sh`, `*.py`)**

- `adb-keepalive.sh` — polls `adb devices` every 5 s. On disconnect it tries, cheapest
  first: the pinned port 5555, the previous port, the port advertised over mDNS, then a
  TCP scan of 30000–50000. Single instance, wake lock held, rotating log.
- `pin-adb-port.sh` — attaches to adbd (a serial the keepalive already holds, then
  mDNS, then a scan) and sends `adb tcpip 5555`. Run by the app, not by hand.
- `keeper.sh` — laptop/Termux-side wrapper that maps friendly command names onto the
  app's content provider, and prints the `key=value` reply.
- `wifi-on.sh` — turns the Wi-Fi radio on with `svc wifi enable` over Termux's adb,
  which keeps working with the radio off because it goes through loopback. Run by the
  app's **Turn Wi-Fi on** button.
- `adb-mdns.py` — reads adbd's own `_adb-tls-connect._tcp` advertisement with
  `python-zeroconf`, filters out stale advertisements, and prints the live port.
  Needs `pip install zeroconf`; without it the keepalive falls back to scanning.
- `reconnect.sh` / `wait_adb.sh` — one-shot reconnect and a bounded "wait for adbd to
  appear" helper, useful after rebooting the phone.
- Reboot/unlock and the autostart toggler live in the sibling `phone-link-tools`
  repository: `reboot.sh` waits for the new boot, `unlock.sh` clears the keyguard.

## Reboot and unlock

```bash
../phone-link-tools/reboot.sh                   # reboot, return after a new boot id
../phone-link-tools/unlock.sh --pin 1234        # clear the keyguard
../phone-link-tools/unlock.sh --check           # print the lock state only
PHONE_PIN=1234 ../phone-link-tools/unlock.sh    # or take the PIN from the env
```

Boot completion is waited for with **one** adb round trip: the polling loop runs on
 the phone (`while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 0.1; done`),
so the cost is 100 ms granularity rather than an adb call per tick. After that the
script wakes the screen, dismisses the keyguard and types the PIN, retrying until
`dumpsys user` reports `RUNNING_UNLOCKED`. Measured on this phone: adbd back at
t+44 s, `sys.boot_completed` at t+52 s, unlocked at t+55 s.

It also matters for apps that start at boot. `LOCKED_BOOT_COMPLETED` *is*
delivered on this device — the earlier note here blamed the vendor's autostart
policy and was wrong. What a package needs is `RECEIVE_BOOT_COMPLETED`, and
`android:directBootAware="true"` written on the receiver itself: DokeOS does not
inherit it from `<application>`, so the broadcast resolves no component and the
process never starts. With both in place the proxy listens before the first
unlock; unlocking is then only needed for the laptop's own uplink.

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
`$HOME/android-sdk/platforms/` is used. The manifest is also the source of
`d8 --min-api`: the script reads `android:minSdkVersion` from it instead of
hardcoding an API level, so a `minSdk` bump cannot leave the dex behind.

Updating an installed copy needs the **same signing key**: pass `--ks` to
`apksigner` with the key the installed build was signed with, or Android refuses the
update as a signature mismatch (an uninstall also clears the app's saved settings).

### Gradle

`build.sh` remains the on-phone build. The Gradle build exists for what it is
better at: declaring a dependency instead of hand-dexing it, and importing the
tree into an IDE.

```bash
./gradlew assembleDebug     # build-gradle/outputs/apk/debug/
```

It builds the same `src/` and the same root `AndroidManifest.xml`, which stays
canonical for `build.sh`, plus the dependency-using `src-pairing/` (see
[Pairing](#pairing)). The package name, `versionCode`, `versionName`,
`minSdk` and `targetSdk` are read from that manifest, so the two builds cannot
disagree; Gradle stages a copy under `build-gradle/` with the attributes AGP owns
removed. Output goes to `build-gradle/` because `build.sh` owns `build/`.

`local.properties` (gitignored) points at the SDK; on this device AGP also needs
`android.aapt2FromMavenOverride` in `~/.gradle/gradle.properties`, since the
`aapt2` AGP ships is x86_64. The Gradle debug APK is signed with the SDK's
`~/.android/debug.keystore`, not the `debug.keystore` `build.sh` creates, so it
will not update an installed `build.sh` build.

### Probes and CI

`tools/` holds two host-side diagnostics. Gradle compiles them against the
app's `android.jar`, so neither needs a hand-written `javac` line:

```bash
./gradlew probe       # IdentityProbe: AdbKey/AdbIdentity/Base64Codec, no device
./gradlew probeAdb    # AdbProbe: needs a phone with adbd on 127.0.0.1:5555
./gradlew probeAdb -PadbKey=$HOME/.android/adbkey -PadbPort=5555 -PadbPinPort=5555
./gradlew probeAdb -PadbTcpip=true   # also sends `tcpip:5555` and times the re-pin
```

`probe` runs only what can run without a phone; `test` and `check` depend on it.
`probeAdb` stays manual and is never part of CI. There is deliberately no JUnit
dependency: it does not resolve from the offline Gradle cache this project builds
from on the phone, so the probe stands in for the unit tests.

`.github/workflows/build.yml` runs `./gradlew test lint assembleDebug` on every
push and pull request, on JDK 17 with the Android SDK. It needs no device, and it
does not run `build.sh`, which remains the on-phone path.

### Pairing

Wireless-debugging pairing is the only thing in the app that needs a library.
It is SPAKE2 plus a certificate exchange, so it uses `libadb-android` and lives
in `src-pairing/`, a source directory only Gradle compiles. `build.sh` still
sees `src/` alone: its APK has no dependency, no native library, and no pairing
screen, and the extra `<activity>` for the pairing screen is injected into the
staged manifest by `build.gradle.kts` rather than into the canonical one.

The one-time setup, for the Gradle build only:

1. Open the app → **Help** → **Pair this phone**.
2. In Developer options, open **Wireless debugging** → **Pair device with
   pairing code** and leave that dialog up — the port it advertises exists only
   while it is open.
3. Type the 6-digit code into the app and tap **Pair**.

`AdbCertificate` gives the existing key a self-signed certificate beside it in
device-protected storage (built as DER by hand, so `src/` needs no BouncyCastle;
`keytool -printcert` reads it back as v3, SHA256withRSA, 2048-bit,
`digitalSignature`, not a CA). The pairing port comes from mDNS
(`_adb-tls-pairing._tcp`). **Test connection** then opens `shell:id` over the
trusted TLS port — that, not the key file, is the proof: adbd only answers a key
it has stored, and `/data/misc/adb/adb_keys` is unreadable to this app anyway.

## Install

```bash
PHONE_SERIAL=192.168.77.232:5555 ./install.sh     # or just ./install.sh
```

`install.sh` does the install and then the four grants, because a reinstall
silently drops them:

```bash
adb install -r wifidebug.apk
adb shell pm grant io.github.mbanucu.wirelessadbkeeper android.permission.WRITE_SECURE_SETTINGS
adb shell pm grant io.github.mbanucu.wirelessadbkeeper android.permission.ACCESS_FINE_LOCATION
adb shell pm grant io.github.mbanucu.wirelessadbkeeper android.permission.POST_NOTIFICATIONS
adb shell pm grant io.github.mbanucu.wirelessadbkeeper com.termux.permission.RUN_COMMAND
```

The `RUN_COMMAND` grant is needed for the fixed port and for the **Turn Wi-Fi on**
button (see below), and only works once the installed APK declares it — granting it to an older build is a
silent no-op. Losing it does not break anything loudly: the app keeps running and
keeps logging, and only `pin check: closed (grant com.termux.permission.RUN_COMMAND)`
in logcat says why the port never comes back — which is exactly the situation in
which there is no cable to fix it with. Hence the script.

Both scripts the app runs live in `~/wireless-adb-keeper/`, so copy them there and mark
them executable:

```bash
mkdir -p ~/wireless-adb-keeper
cp pin-adb-port.sh wifi-on.sh ~/wireless-adb-keeper/
chmod +x ~/wireless-adb-keeper/*.sh
```

Open the app, enter the Wi-Fi SSID and password (both modes need it; open networks need
none), tap **Save**, then **Connect now** and approve the "available networks"
notification once. Leave **Connect mode** on `auto-connect`.

## Control from adb

Every action is also a shell command, so the app never needs to be tapped.
`keeper.sh` wraps the raw call; `content call` starts the app even when it was
force-stopped or only just installed, which a broadcast cannot do.

```bash
./keeper.sh status            # the Status page as key=value lines
./keeper.sh enable            # Enable now
./keeper.sh wifi-on           # Turn Wi-Fi on
./keeper.sh start | stop      # monitor service
./keeper.sh connect           # Connect now
./keeper.sh mode auto|local   # Connect mode
./keeper.sh save SSID [PASS]  # Save SSID and password
./keeper.sh pin               # Pin adb port 5555 now
./keeper.sh pubkey            # the app's own ADB public key
./keeper.sh schedule          # 15-minute keep-alive job
./keeper.sh diag              # status snapshot + recent events
```

`mode` and `save` reply with `reload=monitor` when a running monitor applied the
change, or `reload=next start` when no service was running: the value is saved in
either case and does not need a service restart. (`content call` cannot start the
monitor service on Android 12+, so the running service watches the saved settings
instead of being told by an intent.)

Without the wrapper, the same thing by hand:

```bash
adb shell content call --uri content://io.github.mbanucu.wirelessadbkeeper.control \
    --method status
```

The provider is exported, but it checks the caller's uid, so only adb shell, root,
system and the app itself get through; an ordinary installed app cannot switch Wi-Fi
or stop the monitor. Errors come back as `code=400, data=error=...`; `keeper.sh`
strips the envelope. `start` is the one command that does not use the provider:
Android 12+ refuses to let a background app start its own foreground service, so the
shell starts it with `am start-foreground-service` instead.

## Diagnostics

When something does not work — a boot that never pinned the port, a missing
grant, auto-connect that stopped after a reboot — the answer is on the phone,
not only in `logcat`.

- **Diag tab** in the app: a `key=value` snapshot (uptime, process start, unlock
  state, mode, service, wireless flag, port 5555, Termux and permission state,
  journal size) above the recent events, with **Copy diagnostics** and
  **Refresh**.
- **From adb** (and therefore from a script):

  ```bash
  adb shell content call --uri content://io.github.mbanucu.wirelessadbkeeper.control \
      --method diag
  ```

  The first block is the snapshot, then `-- journal (oldest first) --` and one
  line per event: `time uptime level code detail`.

The journal is a ring buffer of the last 200 events, 32 KiB at most, in
`files/diagnostics/events.log` under **device-protected** storage
(`/data/user_de/0/<package>/`). That location is deliberate: `BootReceiver` and
`MonitorService` run at `LOCKED_BOOT_COMPLETED`, before the first unlock, so an
event recorded there is still readable afterwards and survives a reboot.

Event codes are stable (`area.action`), so `grep` works on a copied report:

| Code | Meaning |
|---|---|
| `boot.received`, `boot.jobs`, `boot.start_failed` | the boot receiver ran, scheduled the ensure jobs, or failed to start the service |
| `svc.start`, `svc.unlocked`, `svc.mode`, `svc.connect` | service created (with unlock state), mode applied after unlock, mode or connect request |
| `ensure.changed`, `ensure.noop`, `ensure.no_wifi`, `ensure.no_permission`, `ensure.failed` | what `Ensurer` did, journalled only when the result changes |
| `pin.state` | the 5555 probe changed state (listening or the reason it is not) |
| `wifi.suggest.added`, `wifi.suggest.failed`, `wifi.suggest.no_password`, `wifi.suggest.build_failed`, `wifi.suggest.record_unsealed` | suggestion result, with the raw status code in the detail |
| `wifi.secret.undecryptable`, `wifi.secret.unsealable` | the Keystore could not read or write a secret, e.g. after a lock-screen change |

The password, the control token and a pairing code are never written: the
diagnostic API has no parameter for them, and the formatter scrubs
`passphrase=…`, `token=…` and `pairing_code=…` if one is ever passed by mistake.
The SSID does appear — it is already visible in system settings.

`Diag.event()` cannot throw: a journal that is full, unwritable or corrupt costs
that one entry and a line in `logcat`, never the service.

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
- `pm grant io.github.mbanucu.wirelessadbkeeper com.termux.permission.RUN_COMMAND`, and an APK whose
  manifest declares it (1.6+).
- The `INTERNET` permission. Not for the network as such: without it Android refuses
  even a loopback `socket()`, which made the watchdog report the port as closed while it
  was listening perfectly well.

Measured on this phone (Android 15, unrooted):

| event | 5555 closed → reachable |
| --- | --- |
| monitor service running: Wi-Fi back, wireless debugging re-enabled, `adb usb`, or adbd dying | ≤ ~16 s: a port that closes with no event is noticed by the 10 s probe, a network/ensure event schedules a check 15 s later, and the Termux script takes about a second |
| reboot, without touching anything | ≈ 60 s after `sys.boot_completed` (boot receiver → ask → `tcpip`) |

The first check after the service starts runs 8 s in. The monitor's checks ask Termux at
most once per 30 s, so a close that follows an ask inside that window waits for the
window to pass (≤ ~31 s); `keeper.sh pin` and the button ask immediately. After a closed
result the port is probed once more 15 s later; that is when `pinned_port=` catches up,
so the status line can lag the port by up to 15 s.
The 10 s probe interval is what keeps the no-event case inside #5's ≤ 30 s target; the
probe is a loopback connect, and the 30 s window still limits what Termux is asked to do.

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
- `WifiNetworkSpecifier` (local-only mode) always needs a tap. Both modes need the saved
  passphrase unless the network is open or enhanced open (OWE).
- The TCP scan misses the live port if another scan runs at the same time — hence the
  single-instance guard. Avoid running `reconnect.sh` while the keepalive is scanning.
- adbd only listens while a Wi-Fi network is connected; with Wi-Fi off there is nothing
  to connect to.
