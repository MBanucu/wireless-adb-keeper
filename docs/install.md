# Install

```bash
PHONE_SERIAL=192.168.77.232:5555 ./install.sh     # or just ./install.sh
./install.sh --autostart                          # also drive the DokeOS row
```

With no argument, `install.sh` prefers the canonical Gradle APK at
`build-gradle/outputs/apk/debug/wireless-adb-keeper-debug.apk` (the one `tools/termux-build.sh`
builds, with pairing). If that file is absent it falls back to `wifidebug.apk`,
the dependency-free bootstrap APK `./build.sh` signs. Pass a path to install
something else. The two are signed with different keys, so installing one over
the other requires an uninstall first (which also clears the app's saved
settings).

`install.sh` does the install and then the four grants, because a reinstall
silently drops them:

```bash
adb install -r build-gradle/outputs/apk/debug/wireless-adb-keeper-debug.apk
# ... or `adb install -r wifidebug.apk` for the dependency-free bootstrap APK
adb shell pm grant io.github.mbanucu.wirelessadbkeeper android.permission.WRITE_SECURE_SETTINGS
adb shell pm grant io.github.mbanucu.wirelessadbkeeper android.permission.ACCESS_FINE_LOCATION
adb shell pm grant io.github.mbanucu.wirelessadbkeeper android.permission.POST_NOTIFICATIONS
adb shell pm grant io.github.mbanucu.wirelessadbkeeper com.termux.permission.RUN_COMMAND
```

The `RUN_COMMAND` grant is needed for the fixed port and for the **Turn Wi-Fi on**
button (see [Fixed port 5555](#fixed-port-5555) below), and only works once the installed APK declares it — granting it to an older build is a
silent no-op. Losing it does not break anything loudly: the app keeps running and
keeps logging, and only `pin.no_permission` (or the state line
`closed (grant com.termux.permission.RUN_COMMAND; fix: adb shell pm grant …)`)
says why the port never comes back — which is exactly the situation in
which there is no cable to fix it with. Hence the script.

On a DokeOS phone the install also prints the vendor prerequisite from the
[boot gates](boot.md#two-independent-boot-gates-dokeos). Nothing is toggled by default: `--autostart` is the opt-in, and
it checks that the phone is `RUNNING_UNLOCKED` first. It then enables the app's
own accessibility service (`AutostartAccess`, declared in
`res/xml/autostart_service.xml` with `packageNames="com.blackview.systemmanager"`,
so it is only ever handed the vendor app's window), opens **System Manager →
App Management → Auto Start Management** with `am start`, and calls
`content call --method autostart`, which finds the row and clicks its switch.
If the service cannot be enabled, the install falls back to
`autostart-allow.sh "Wireless ADB Keeper"`, which walks the same screen with
uiautomator. A failure names the expected screen instead of guessing. After the
next reboot, `./keeper.sh diag | grep oem.autostart` is the check.

With the service enabled, `content call --method autostart` is enough to switch
the row back on later (idempotent: `before=on after=on` when it already is),
provided the vendor screen is open; `keeper.sh autostart` is the same call.

A reinstall keeps the service enabled. To turn the capability off again:

```bash
adb shell settings put secure enabled_accessibility_services ""
adb shell settings put secure accessibility_enabled 0
```

(Or toggle it in Settings → Accessibility; the app appears there as *Wireless
ADB Keeper autostart*. Clearing the setting leaves the other enabled services
alone only if you edit the list instead of emptying it.)

The fallback scripts the app may run live in `~/wireless-adb-keeper/`, so copy them
there and mark them executable. A Gradle install whose app key is paired uses the
in-app TLS client for pinning and Wi-Fi instead and does not need them:

```bash
mkdir -p ~/wireless-adb-keeper
cp pin-adb-port.sh wifi-on.sh discover.sh scan_ports.py ~/wireless-adb-keeper/
chmod +x ~/wireless-adb-keeper/*.sh ~/wireless-adb-keeper/*.py

# mDNS discovery is adb-connect from the pinned adb-harness[mdns].
# A venv beside the runtime scripts is what discover.sh prefers:
ADB_HARNESS_VENV=~/wireless-adb-keeper/tools/.venv \
    bash tools/setup-adb-harness.sh
# ... or install the pinned extra and let discover.sh find it on PATH:
pip install "adb-harness[mdns]==0.2.3"
```

An existing install at `~/wireless-adb-keeper/` must re-run that command after
upgrading: `pin-adb-port.sh` now needs `discover.sh`, `scan_ports.py` and
`adb-connect` (from adb-harness) beside it or on `PATH`.
Termux 0.109+ sends the script's exit code, stdout and stderr back, so a missing
helper now shows its own error under **Pin result** and in the journal; older
Termux still only sees the port stay closed (#37).

Open the app, add the Wi-Fi network on the **Network** page (both modes need it; open
networks need no password), tap **Save**, then **Connect now** and approve the
"available networks" notification once. Leave **Connect mode** on `auto-connect`. Add
more networks there to keep several saved; tap a row to edit or delete it, and the
up/down controls set the try order (the first row is marked active).

## Fixed port 5555

`adb` is told to listen on a fixed port, so a script on the other side does not have to
rediscover adbd's port after every restart. The port is set with `adb tcpip 5555`, which
is an adb *service request*: it needs an adb client whose key adbd already accepts.

The canonical Gradle build is that client now. Its key and certificate live in
device-protected storage, so `PinnedPort` can open adbd's TLS port and send
`tcpip:5555` even before the first unlock. It discovers the TLS port with
`NsdManager` (`_adb-tls-connect._tcp`) and falls back to listening local ports in
the ephemeral range; a stale advertisement fails the TLS handshake and the next
candidate is tried.

Termux remains the fallback for an install whose app key is not paired and for
the dependency-free `build.sh` APK. When `127.0.0.1:5555` stops accepting
connections, `PinnedPort` sends a `RUN_COMMAND` intent to Termux, which runs
`pin-adb-port.sh` and sends `tcpip:5555` with its own paired client.

Fallback requirements:

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

## See also

[README](../README.md) · [Build](build.md) · [Control](control.md) · [Boot](boot.md) · [Architecture](architecture.md) · [Limits](limits.md) · [Diagnostics](diagnostics.md) · [Reboot verdict](reboot-verdict.md) · [Pre-unlock connect](preunlock-connect.md) · [Termux build](termux-build.md)
