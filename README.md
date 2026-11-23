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
  `ControlReceiver` is the same dispatch over an ordered broadcast, the only
  control channel an ordinary app uid can reach (see [Control from Termux](#control-from-termux-no-adb)).
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
  TCP scan of the kernel's ephemeral range (32768–60999 when
  `/proc/sys/net/ipv4/ip_local_port_range` is readable, which it is not from Termux).
  Single instance, wake lock held while running and released on exit, rotating log.
- `pin-adb-port.sh` — attaches to adbd (a serial the keepalive already holds, then
  mDNS, then the phone's TCP table or a scan) and sends `adb tcpip 5555`. Run by the
  app, not by hand. Its last stdout line is `RESULT pinned=5555` (or
  `RESULT error=no-adbd`); see [Script result contract](#script-result-contract).
- `keeper.sh` — laptop/Termux-side wrapper that maps friendly command names onto the
  app's content provider, and prints the `key=value` reply.
- `keeperctl.sh` — the same friendly commands straight from a Termux prompt, without
  adb, using the one-time control token. See [Control from Termux](#control-from-termux-no-adb).
- `wifi-on.sh` — turns the Wi-Fi radio on with `svc wifi enable` over Termux's adb,
  which keeps working with the radio off because it goes through loopback. Tries the
  pinned port first, then any serial adb already holds. Run by the app's
  **Turn Wi-Fi on** button. Ends with `RESULT wifi=on` or `RESULT error=...`.
- `adb-mdns.py` — reads adbd's own `_adb-tls-connect._tcp` advertisement with
  `python-zeroconf`, filters out stale advertisements, and prints the live port.
  Needs `pip install zeroconf`; without it the keepalive falls back to scanning.
- `reconnect.sh` / `wait_adb.sh` — one-shot reconnect and a bounded "wait for adbd to
  appear" helper, useful after rebooting the phone. Both try the pinned port first;
  `wait_adb.sh` ends with `RESULT connected=<port>` or `RESULT error=timeout`.
- `discover.sh` / `scan_ports.py` — the one discovery implementation: shared port
  range, pinned-port default, mDNS, the phone's `/proc/net/tcp` table, the loopback
  scan, and connect-and-verify. Sourced by the other scripts so they cannot drift
  apart again.
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

`tests/unit/` holds the JVM suites (see [`tests/README.md`](tests/README.md)) and
`tools/` holds host-side diagnostics. Gradle compiles the probes against the
app's `android.jar`, so neither needs a hand-written `javac` line:

```bash
./gradlew probeUnit   # the tests/unit suites, no device, no JUnit
./gradlew probe       # probeUnit plus IdentityProbe, TokenProbe, ResultProbe and the scripts
./gradlew probeResult # TermuxResult: the RESULT-line parser, no device
./gradlew probeScripts # pin-adb-port.sh, wait_adb.sh, wifi-on.sh with a fake adb
./gradlew probeAdb    # AdbProbe: needs a phone with adbd on 127.0.0.1:5555
./gradlew probeAdb -PadbKey=$HOME/.android/adbkey -PadbPort=5555 -PadbPinPort=5555
./gradlew probeAdb -PadbTcpip=true   # also sends `tcpip:5555` and times the re-pin
```

`probe` runs only what can run without a phone; `test` and `check` depend on it.
`probeAdb` stays manual and is never part of CI. There is deliberately no JUnit
dependency: it does not resolve from the offline Gradle cache this project builds
from on the phone, so `tests/unit.sh` runs the suites itself and reports
`PASS`/`FAIL` with a non-zero exit code.

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

With no argument, `install.sh` installs `wifidebug.apk` in the repo root, the
file `./build.sh` signs. Pass a path to install something else; the Gradle build
is signed with a different key, so it cannot update an app installed by
`build.sh`.

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
cp pin-adb-port.sh wifi-on.sh discover.sh scan_ports.py adb-mdns.py ~/wireless-adb-keeper/
chmod +x ~/wireless-adb-keeper/*.sh ~/wireless-adb-keeper/*.py
```

An existing install at `~/wireless-adb-keeper/` must re-run that command after
upgrading: `pin-adb-port.sh` now needs `discover.sh` and `scan_ports.py` beside it.
Termux 0.109+ sends the script's exit code, stdout and stderr back, so a missing
helper now shows its own error under **Pin result** and in the journal; older
Termux still only sees the port stay closed (#37).

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

The provider is exported, but it gates the caller: adb shell, root, system and the
app itself get through by uid, and an ordinary app that reaches it through its
own `ContentResolver` may present the control token instead. A caller with
neither gets the same 403 whether it sends no token or a wrong one. Errors come back as `code=400, data=error=...`; `keeper.sh` strips the
envelope. `start` is the one command that does not use the provider: Android 12+
refuses to let a background app start its own foreground service, so the shell
starts it with `am start-foreground-service` instead.

### Control from Termux (no adb)

`adb shell content call` — and therefore `keeper.sh` — runs as uid 2000 and has
always passed the gate. The path that did not work is `content call` straight
from a Termux prompt: the `content` command asks the platform for an "external"
provider, which needs the signature permission
`ACCESS_CONTENT_PROVIDERS_EXTERNALLY`. Only shell and root hold that permission,
so an app uid is refused inside `getContentProviderExternal()` before
`ControlProvider` is entered. A token extra cannot change that; the provider is
simply not reachable from that command.

The app therefore also exports an ordered broadcast (`ControlReceiver`) with the
same dispatch and the token gate. It is token-only because a broadcast does not
give the receiver a trustworthy sender uid on every supported release; shell and
root keep their uid gate through the provider. `keeperctl.sh` uses the
broadcast. Mint the token once, from adb:

```bash
mkdir -p ~/.config/wireless-adb-keeper
chmod 700 ~/.config/wireless-adb-keeper
touch ~/.config/wireless-adb-keeper/token
chmod 600 ~/.config/wireless-adb-keeper/token
adb shell content call --uri content://io.github.mbanucu.wirelessadbkeeper.control \
    --method token \
  | sed -n 's/.*data=token=\([0-9a-f]\{64\}\).*/\1/p' \
  > ~/.config/wireless-adb-keeper/token
```

`--method token` is shell-only, like `pubkey`, and mints 32 random bytes on first
use. The app keeps them in device-protected storage beside its ADB key, at
`/data/user_de/0/<package>/files/adb-identity/control.token` with mode 0600. The
copy in Termux holds the 64 hex characters with mode 600. `keeperctl.sh` refuses
a file that is not owned by the current user or is not 600, and never prints the
token.

```bash
./keeperctl.sh status            # the same key=value fields as keeper.sh status
./keeperctl.sh enable
./keeperctl.sh wifi-on
./keeperctl.sh connect | stop
./keeperctl.sh mode auto|local
./keeperctl.sh save SSID [PASS]
./keeperctl.sh schedule
./keeperctl.sh diag
```

It runs from the checkout; there is nothing to install. If the scripts are copied to
`~/wireless-adb-keeper/`, copy `keeperctl.sh` beside them.

The token is a capability, not a package check: any process that can read the
600 file can drive the app, and no other app can. That is deliberate — it
survives a Termux reinstall and works with a Termux fork whose package id is
different. Root, adb shell and the app itself keep working without it, so the
existing `adb shell` path is unchanged. `start`, `pin`, `pubkey` and `token`
still need adb; they do something an app uid cannot (start another app's
foreground service, run `adb tcpip`, or handle key material).

A force-stopped app receives no broadcasts. If `keeperctl.sh` reports no reply,
run `./keeper.sh status` once (the provider starts the app) or open it; the
provider path is the recovery path.

Residual exposure: the token travels in the argv of the short-lived `am`/`cmd`
process as `--es token ...`. On this device `/proc` is mounted
`hidepid=invisible`, so another app cannot read it; a process running as
Termux's uid can, but it can also read the 600 file. `am`/`content` have no
environment or stdin channel for a provider extra, so avoiding argv entirely
would need a second app with a Binder helper. The token is not echoed by the
wrapper, written to logcat, or put in an error reply: the app logs the caller
(or `broadcast sender`), the method and only whether a token was presented,
never its characters.

`ENSURE` is a broadcast rather than a provider call:

```bash
adb shell am broadcast -a io.github.mbanucu.wirelessadbkeeper.ENSURE \
    -n io.github.mbanucu.wirelessadbkeeper/.EnsureReceiver
```

`reconnect.sh` prints the same command as a hint. The receiver is exported but
requires `WRITE_SECURE_SETTINGS`, which shell, root, system and the app itself
hold; an ordinary app's broadcast is refused.

## Diagnostics

When something does not work — a boot that never pinned the port, a missing
grant, auto-connect that stopped after a reboot — the answer is on the phone,
not only in `logcat`.

- **Diag tab** in the app: a `key=value` snapshot (uptime, process start, unlock
  state, mode, service, wireless flag, port 5555, the last `pin_result` and
  `wifi_result`, Termux and permission state, journal size) above the recent
  events, with **Copy diagnostics** and **Refresh**.
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
| `pin.result`, `script.result` | Termux returned a script's stdout, stderr, exit code and final `RESULT` line |
| `pin.fallback` | the port probe is the only evidence: Termux is older than 0.109 or sent no result |
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
keepalive already holds, then mDNS, then the phone's TCP table or a scan) and sends
`tcpip:5555`.

Requirements:

- Termux from F-Droid/GitHub with `allow-external-apps=true` and a working `adb`.
- This checkout **at `~/wireless-adb-keeper`** in Termux — that is the path the app
  calls. (`PinnedPort.SCRIPT` is the one place to change if it lives elsewhere.)
- `pm grant io.github.mbanucu.wirelessadbkeeper com.termux.permission.RUN_COMMAND`, and an APK whose
  manifest declares it (1.6+).
- The `INTERNET` permission. Not for the network as such: without it Android refuses
  even a loopback `socket()`, which made the watchdog report the port as closed while it
  was listening perfectly well.
- Termux 0.109+ to report the script's result. It is not required for pinning: an older
  Termux ignores the pending intent and the port probe remains the fallback.

### Script result contract

The app asks Termux for the execution result with the
`com.termux.RUN_COMMAND_PENDING_INTENT` extra (Termux >= 0.109). Every script the
app runs ends with one machine-readable line on stdout, `RESULT key=value ...`;
values are single tokens separated by whitespace, and the last `RESULT` line
wins if a script prints more than one.

| script | success | failure |
| --- | --- | --- |
| `pin-adb-port.sh` | `RESULT pinned=5555` | `RESULT error=no-adbd`, `RESULT error=port-closed port=5555` |
| `wait_adb.sh` | `RESULT connected=5555` | `RESULT error=timeout` |
| `wifi-on.sh` | `RESULT wifi=on` | `RESULT error=no-device`, `RESULT error=wifi-off state=0` |

A pin already running under another PID exits 0 with `RESULT skipped=running`.
The app parses that line, keeps Termux's stdout, stderr and exit code, and
writes one `pin.result` (or `script.result`) event to the journal. The raw
bundle keys are Termux's own: `result` → `stdout`, `stderr`, `exitCode`, `err`,
`errmsg`.

What the person holding the phone sees:

- **Success**: the Status page's **Pin result** row shows the script's own
  `pinned=5555 (exit 0)`, and **Pinned port** reads
  `listening on 5555; script pinned=5555`.
- **Failure**: **Pin result** shows the script's error, its exit code and the
  first stderr line, for example `error=no-adbd (exit 1); adbd: no devices`.
  **Pinned port** carries the same reason instead of a bare `closed`, and the
  full line is in the Diag journal under `pin.result`.
- **Older Termux** (before 0.109): the pending intent is ignored, **Pin
  result** stays `none`, and one `pin.fallback` journal event says the port
  probe is the only evidence. Pinning itself behaves exactly as before.

The probe is still the authority on whether the socket answers; a script's
RESULT line is only trusted for five minutes, so a later failure is not hidden
by an old success.

Measured on this phone (Android 15, unrooted):

| event | 5555 closed → reachable |
| --- | --- |
| monitor service running: Wi-Fi back, wireless debugging re-enabled, `adb usb`, or adbd dying | typically ≤ ~16 s; up to ~31 s if the close follows a Termux ask inside the 30 s window (10 s probe, a check 15 s after a network/ensure event, ~1 s script) |
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

When an adb serial is already attached, `discover.sh` asks the phone instead:
`adb shell cat /proc/net/tcp /proc/net/tcp6` lists every listening socket (state `0A`,
local port in hex) in one round trip. That is far cheaper than opening tens of
thousands of loopback connections, and it falls back to the scan when no serial is
attached or the tables are empty. The shared scan covers the kernel's ephemeral range and is the last resort.

## Limitations

- The app cannot reconnect ADB; that is Termux's job (see the table above).
- Pinning to 5555 needs Termux: without it the app still keeps wireless debugging on,
  it just cannot make adbd listen on a fixed port.
- `WifiNetworkSpecifier` (local-only mode) always needs a tap. Both modes need the saved
  passphrase unless the network is open or enhanced open (OWE).
- The TCP scan misses the live port if another scan runs at the same time — hence the
  single-instance guard. Avoid running `reconnect.sh` while the keepalive is scanning.
  With a serial attached, discovery reads `/proc/net/tcp` instead and no scan races.
- adbd only listens while a Wi-Fi network is connected; with Wi-Fi off there is nothing
  to connect to.
