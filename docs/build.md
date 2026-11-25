# Build

The Gradle build is canonical and feature-complete; `build.sh` is the
dependency-free bootstrap a phone-only setup can always run.

```bash
tools/termux-build.sh        # canonical: full app, pairing included
./build.sh                   # bootstrap: no Gradle, no dependency, no pairing
```

## The two builds

There are two builds, and the difference is a contract, not an accident:

| build | command | APK | feature set | needs |
| --- | --- | --- | --- | --- |
| **Gradle (canonical)** | `tools/termux-build.sh` | `build-gradle/outputs/apk/debug/wireless-adb-keeper-debug.apk` | Termux path **and** pairing (`src-pairing`, `libadb-android`) | JDK 17+, Android SDK 35, Termux `aapt2` |
| bootstrap | `./build.sh` | `wifidebug.apk` | Termux path only: no dependency, no native library, no pairing | `javac`, `d8`, `aapt`, `apksigner`, an `android.jar` |

Gradle is canonical because it is the complete app. `build.sh` remains the
dependency-free bootstrap: it must keep working with no network, no Gradle and
no SDK configuration, which is what a phone-only setup can rely on. Neither
silently becomes the other; [`termux-build.md`](termux-build.md)
records the full contract and the measured builds.

### Bootstrap (no Gradle)

```bash
# put android.jar (API 28+ platform jar) next to build.sh, or point ANDROID_JAR at it
./build.sh          # compiles, dexes, signs with a generated debug keystore
```

The script creates `debug.keystore` on first run and signs `wifidebug.apk`. If
`android.jar` is not next to `build.sh`, the newest one under
`$HOME/android-sdk/platforms/` is used. The manifest is also the source of
`d8 --min-api`: the script reads `android:minSdkVersion` from it instead of
hardcoding an API level, so a `minSdk` bump cannot leave the dex behind.

### Canonical Gradle build

```bash
tools/termux-build.sh                 # checkBuildEnv + assembleDebug, offline by default
OFFLINE=0 tools/termux-build.sh       # first run, allows the downloads
tools/termux-build.sh lintDebug       # any Gradle task, same environment
tools/termux-build.sh --check         # print the resolved SDK and aapt2 only
```

The wrapper resolves the SDK from `ANDROID_HOME`, `ANDROID_SDK_ROOT`,
`local.properties` or `$HOME/android-sdk`, exports it for the Gradle process,
and passes Termux's bionic `aapt2` as `android.aapt2FromMavenOverride`. A fresh
checkout therefore needs one command; `local.properties.example` is the
template if you prefer to pin `sdk.dir` instead. The same tasks by hand:

```bash
export ANDROID_HOME="$HOME/android-sdk"
./gradlew --offline checkBuildEnv probe lintDebug assembleDebug
```

It builds the same `src/` and the same root `AndroidManifest.xml`, which stays
canonical for `build.sh`, plus the dependency-using `src-pairing/` (see
[Pairing](#pairing)). The package name, `versionCode`, `versionName`, `minSdk`
and `targetSdk` are read from that manifest, so the two builds cannot disagree.
Gradle stages a copy under `build-gradle/` with the attributes AGP owns removed;
the staging is a DOM transformation, and `checkStagedManifest` fails the build if
the result loses an activity, a permission or the control provider. Output goes
to `build-gradle/` because `build.sh` owns `build/`.

The two APKs use different debug keys: the Gradle APK is signed with the SDK's
`~/.android/debug.keystore`, not the `debug.keystore` `build.sh` creates. One
cannot update the other.

### Dependency policy

`gradle/libs.versions.toml` owns AGP, `compileSdk` and `libadb-android`; the
version is no longer a string in `build.gradle.kts`. The dependency is a
JitPack artifact, so the build pins it twice:

- `gradle/verification-metadata.xml` carries a SHA-256 for every artifact,
  including `libadb-android` and its `spake2` transitive. A re-published JitPack
  artifact fails the build instead of entering it unnoticed.
- `gradle/vendor/` is a committed, project-controlled Maven repository holding
  both modules. `settings.gradle.kts` resolves it first, so a fresh checkout
  needs no JitPack; `tools/vendor-libadb.sh` (re)creates it and refuses bytes
  that do not match the verification metadata.

```bash
./gradlew checkDependencyPolicy    # the version has a checksum entry
tools/vendor-libadb.sh --check     # verify the committed vendor tree
```

JitPack is only the upstream source now: it is contacted when the catalog asks
for a version the vendor tree does not have. If it is unavailable, the fallbacks
are, in order: the committed vendor tree, the Gradle cache, a controlled mirror
serving the same coordinates, or the dependency-free `build.sh` APK (pairing
excluded). [`termux-build.md`](termux-build.md#dependencies) has the
exact commands and the mirror procedure. Offline builds are unchanged:
`--offline` still resolves from the Gradle cache, and the wrapper checksum is in
`gradle/wrapper/gradle-wrapper.properties`.

### Probes and CI

`tests/unit/` holds the JVM suites (see [`tests/README.md`](../tests/README.md)) and
`tools/` holds host-side diagnostics. Gradle compiles the probes against the
app's `android.jar`, so neither needs a hand-written `javac` line:

```bash
./gradlew probeUnit   # the tests/unit suites, no device, no JUnit
./gradlew probe       # probeUnit, build/manifest checks and the host probes
./gradlew probeResult # TermuxResult: the RESULT-line parser, no device
./gradlew probeScripts # the scripts and tests/device.sh with a fake adb
./gradlew probeDeviceTooling # tests/device.sh: flags, recording, artifacts, restore
./gradlew probeBuildTooling # wrapper hash guard and the Termux script's env handling
./gradlew probeAdb    # AdbProbe: needs a phone with adbd on 127.0.0.1:5555
./gradlew probeAdb -PadbKey=$HOME/.android/adbkey -PadbPort=5555 -PadbPinPort=5555
./gradlew probeAdb -PadbTcpip=true   # also sends `tcpip:5555` and times the re-pin
```

On a phone with the debuggable Gradle build installed, `bash tests/device.sh`
runs the on-device suites over `keeper.sh` with a state snapshot and restore;
`--record` adds an optional `adb-harness` video and `--list` needs no device.
The device run itself is not part of CI; its device-free contracts (flags,
recording, artifacts, restore) are checked by `probeDeviceTooling`. See
[`tests/README.md`](../tests/README.md#the-device-suite).

`probe` runs only what can run without a phone; `test` and `check` depend on it.
`probeAdb` stays manual and is never part of CI. There is deliberately no JUnit
dependency: it does not resolve from the offline Gradle cache this project builds
from on the phone, so `tests/unit.sh` runs the suites itself and reports
`PASS`/`FAIL` with a non-zero exit code.

`.github/workflows/build.yml` runs `./gradlew checkBuildEnv probe lintDebug
assembleDebug` on every push and pull request, on JDK 17 with the Android SDK.
It validates the wrapper JAR (`gradle/actions/wrapper-validation`) and the
distribution SHA-256 (`tools/check-wrapper.sh --online`). It needs no device.
A second, opt-in `termux` job runs `tools/termux-build.sh` and `build.sh` on a
self-hosted phone runner; it is skipped until `TERMUX_RUNNER_ENABLED=true` and a
runner labelled `self-hosted, termux` is registered. See
[`termux-build.md`](termux-build.md#ci-and-the-on-device-job).

### Pairing

Wireless-debugging pairing is the only thing in the app that needs a library.
It is SPAKE2 plus a certificate exchange, so it uses `libadb-android` and lives
in `src-pairing/`, a source directory only the canonical Gradle build compiles.
`build.sh` still sees `src/` alone: its APK has no dependency, no native library,
and no pairing screen. The extra `<activity>` for the pairing screen is added to
the staged manifest by a DOM transformation, not to the canonical manifest, and
`checkStagedManifest` verifies it is there.

The one-time setup, for the canonical Gradle build only. In Developer options,
open **Wireless debugging** → **Pair device with pairing code** and leave that
dialog up — the port it advertises exists only while it is open. Then either:

1. Open the app → **Help** → **Pair this phone**, type the 6-digit code and tap
   **Pair**; or
2. From adb, with the dialog still open:

   ```bash
   ./keeper.sh pair 123456       # the code from the dialog
   ./keeper.sh pair-verify       # proves it: uid=2000(shell)
   ```

   The raw form is `content call --method pair --extra code:s:123456` and
   `--method pair_verify`. Both are shell-only, like `pubkey`: they handle
   identity material, so a control token cannot call them.

The exported pairing screen can also be driven from adb. `pair_host` and
`pair_port` are optional; without them the app discovers the pairing port over
mDNS. The code still has to be the one the system dialog is showing.

```bash
adb shell am start -n io.github.mbanucu.wirelessadbkeeper/.PairActivity \
  --es pair_code 123456 \
  --es pair_host 192.168.77.232 \
  --ei pair_port 37123
```

`--ez pair_verify true` runs **Test connection** directly, or runs it
after a successful pairing when combined with `pair_code`:

```bash
adb shell am start -n io.github.mbanucu.wirelessadbkeeper/.PairActivity \
  --ez pair_verify true
```

The result is shown on the screen and logged under the `WirelessAdbKeeper`
tag (`adb logcat -s WirelessAdbKeeper`).

`AdbCertificate` gives the existing key a self-signed certificate beside it in
device-protected storage (built as DER by hand, so `src/` needs no BouncyCastle;
`keytool -printcert` reads it back as v3, SHA256withRSA, 2048-bit,
`digitalSignature`, not a CA). The pairing port comes from mDNS
(`_adb-tls-pairing._tcp`). **Test connection** (or `./keeper.sh pair-verify`)
then opens `shell:id` over the trusted TLS port — that, not the key file, is the
proof: adbd only answers a key it has stored, and `/data/misc/adb/adb_keys` is
unreadable to this app anyway.

### Pair from Termux, no laptop

`pair-local.sh` is the bootstrap for a phone that has never been paired with
anything. It runs `adb pair` against adbd's pairing service on `127.0.0.1`, then
discovers the connect port with the shared helper and runs `adb connect`. It
prints the `adb shell id` reply; `uid=2000(shell)` in it is the proof. adbd only
answers a key it has stored, so that line means Termux's `~/.android/adbkey` is
the key adbd just learned.

```bash
./pair-local.sh 123456          # pairing port from mDNS
./pair-local.sh 123456 37123    # ... or typed from the dialog
PAIR_CODE=123456 ADB_PAIR_PORT=37123 ./pair-local.sh
```

What a person has to do, and what the script does:

| Step | Who |
| --- | --- |
| Enable **Developer options → Wireless debugging** | person |
| Tap **Pair device with pairing code** and keep the dialog on screen | person |
| Read and type the 6-digit code | person (the code is not in mDNS or a property) |
| Find `_adb-tls-pairing._tcp` | script (mDNS; a typed port if mDNS is unavailable) |
| `adb pair 127.0.0.1:<port> <code>` | script |
| Find `_adb-tls-connect._tcp`, else the shared discovery helper | script |
| `adb connect`, then `adb shell id` as the proof | script |

The dialog has to stay open until `adb pair` returns: its advertisement exists
only while it is up. The script never scans for a pairing port: the
advertisement is the only reliable way to tell the pairing listener from adbd's
TLS port, so a missing advertisement means the port has to be typed. If mDNS
cannot see it, pass the port the dialog shows as the second argument (or
`ADB_PAIR_PORT`); the error says so instead of guessing.

The connect port is always rediscovered, so a wireless-debugging toggle is
handled by re-running the script. Nothing here uses the pinned 5555 either:
pairing can happen before any pin exists.

#### Spike findings

Verified without a phone:

- Termux's adb is `37.0.0p1-android-tools` and has `adb pair HOST[:PORT]
  [PAIRING CODE]`. That proves the client command exists, not that adbd accepts
  a loopback pairing connection.
- `adb mdns services` is not usable here: this adb says `mdns is not supported
  by this version of adb`. The browser is `adb-connect --pair` from `adb-harness`
  (the same dependency and zeroconf path the keeper uses for the connect port);
  `pair-local.sh` browses `_adb-tls-pairing._tcp.local.` through it.
- With a fake adb and fake mDNS (`tools/pair-local-test.sh`,
  `./gradlew probeScripts`) the flow covers success, wrong code, wireless
  debugging off, no pairing service, a stale port, no mDNS, a typed port, and
  rediscovery of both ports after a toggle.

The four unknowns, and what this change can honestly say about them:

| Unknown | Status |
| --- | --- |
| 1. `adb pair 127.0.0.1:<port> <code>` with no laptop | **Device check.** Nothing host-side can prove adbd accepts its own loopback address. The script's `Successfully paired` plus `uid=2000(shell)` is that test. |
| 2. Pairing port without reading the dialog | **Partly verified; visibility is a device check.** Android advertises `_adb-tls-pairing._tcp` while the dialog is open (the app's pairing screen already reads it), and the helper can now browse it from Termux. Whether the advertisement is visible from Termux on this Wi-Fi, or over loopback with Wi-Fi off, is not verified here. The [README](../README.md#why)'s existing constraint — adbd only listens while a Wi-Fi network is connected — suggests Wi-Fi-off loopback is the unlikely case; that is a hypothesis, not a measurement. |
| 3. Connect port after pairing | **Device check; the script does not depend on it.** If `_adb-tls-connect._tcp` is advertised it is used directly. Otherwise `discover.sh` reads the phone's `/proc/net/tcp` when a serial is attached, or scans the loopback range as the last resort. No port is hardcoded. |
| 4. Pairing lifetime with the screen off (DokeOS) | **Device check / hypothesis.** The service is tied to the dialog, not to a timer this repo can observe. Treat "open the dialog, note the code, run the script promptly" as the supported flow; whether DokeOS keeps it alive with the screen off has not been measured. |

Not yet checked on a phone: the real loopback `adb pair`, which key adbd stores,
whether the pairing advertisement appears when Wi-Fi is off, whether the connect
advertisement appears immediately after pairing, and the screen-off lifetime.
The script is written so that the last three are discovered rather than assumed.

## See also

[README](../README.md) · [Install](install.md) · [Control](control.md) · [Boot](boot.md) · [Architecture](architecture.md) · [Limits](limits.md) · [Diagnostics](diagnostics.md) · [Reboot verdict](reboot-verdict.md) · [Pre-unlock connect](preunlock-connect.md) · [Termux build](termux-build.md)
