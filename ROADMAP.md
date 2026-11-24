# Roadmap: removing the Termux dependency

Tracking issue: [#1 Explore integrating an ADB client](https://github.com/MBanucu/wireless-adb-keeper/issues/1)

Goal: the app speaks the ADB protocol itself, so an unrooted phone needs no Termux,
no `com.termux.RUN_COMMAND`, no scripts in `~/wireless-adb-keeper/`, and no paired
`adb` client outside the app.

Guiding constraints, in priority order:

1. **Unrooted.** No `su`, no Magisk module, no privileged helper app.
2. **No taps to operate.** Runtime behaviour stays automatic: monitor, re-enable,
   re-pin, Wi-Fi on. A *one-time* bootstrap tap is acceptable (see M2) — it is setup,
   not operation.
3. **Two builds, deliberately.** Gradle is the canonical, feature-complete build
   (`tools/termux-build.sh` → `build-gradle/outputs/apk/debug/wireless-adb-keeper-debug.apk`).
   `build.sh` stays the no-dependency bootstrap and keeps producing the
   Termux-backed `wifidebug.apk` when Gradle, the network or the SDK is unavailable.
   Neither may silently become the other, and the README has to say which artifact
   is which; [#69](https://github.com/MBanucu/wireless-adb-keeper/issues/69) and
   [`docs/termux-build.md`](docs/termux-build.md) record the contract.
4. **Termux stays as fallback until its replacement is measured, not assumed.**

## What actually has to be replaced

| Job today | Done by | Replacement |
| --- | --- | --- |
| Re-enable wireless debugging | app (`WRITE_SECURE_SETTINGS`) | unchanged |
| Reconnect to Wi-Fi | app (`WifiNetworkSuggestion`) | unchanged |
| Learn adbd's port | Termux + mDNS + port scan | app: mDNS (NSD) or scan, plus adbd's TLS port |
| `tcpip:5555` | Termux, `pin-adb-port.sh` | app: ADB client, `tcpip:5555` service |
| `svc wifi enable` with radio off | Termux, `wifi-on.sh` | app: ADB client, `shell:svc wifi enable` |
| **adbd trusting our connection** | Termux's already-paired key | **app-owned key, trusted once** |

That last row is the project's real dependency, and it is not a coding problem — it
is an authorisation problem. `adbd` accepts a client only if the client's public key
is in `/data/misc/adb/adb_keys`, which no app can write. The web-debugging key in
Termux belongs to Termux's private storage, so it cannot be borrowed. The app has to
own a key, and adbd has to be made to trust it exactly once.

The only unrooted ways to do that:

- **Wireless-debugging pairing (Android 11+)**: the app acts as the pairing client,
  the user types the 6-digit code from *Developer options → Wireless debugging →
  Pair device with pairing code*. Pairing is what writes the app's key into
  `adb_keys`, and it persists across reboots and across `adb kill-server`. This is
  the path the roadmap takes.
- **Root / pre-provisioned `adb_keys`.** Out of scope.

On Android 10 and below there is no pairing flow, so no-Termux is impossible there.
Termux support stays for those devices (or the app raises its floor).

## Milestones

### M0 — Build foundation — done · [#2](https://github.com/MBanucu/wireless-adb-keeper/issues/2)

Gradle build on top of the unchanged layout (`build.gradle.kts`, wrapper 9.9.2,
staged manifest, output in `build-gradle/`), so a declared dependency is possible
at all. `build.sh` untouched. Both builds verified. The follow-up hardening in
#69 moved versions into `gradle/libs.versions.toml`, pinned the wrapper
distribution hash, added dependency verification, committed the JitPack
artifacts under `gradle/vendor/` so the canonical build no longer needs JitPack,
and replaced the staged-manifest regex with a DOM transformation and a
component/permission check.

*Done when:* `./gradlew assembleDebug` produces an APK with the same package,
version and components as `build.sh`. **Met.**

### M1 — ADB client core, verified from the host · [#3](https://github.com/MBanucu/wireless-adb-keeper/issues/3)

The smallest client that can talk to adbd: TCP connect, `CNXN`/`AUTH`, open a service,
read/write streams. No TLS, no pairing, no mDNS. **Core done; wiring `WifiRadio` moved
to M3.**

It cannot be proven from inside the app, because the app has no trusted key until M2.
Instead it is proven from Termux: `tools/AdbProbe` runs the same `src/` classes from a
JVM against `127.0.0.1:5555` with `~/.android/adbkey`, the key adbd already accepts.
Two protocol details were established the hard way and are documented on #3: the
`AUTH` signature carries the token *as* the PKCS#1 digest (not `SHA1withRSA`), and
streams must be filtered by id because adbd can send a late duplicate `CLSE`.

*Scope:* `AdbClient`, `AdbKey`, `tools/AdbProbe` (including a `--tcpip` smoke test that
re-pins 5555 and times it); `PinnedPort` and `WifiRadio` untouched.

*Done when:* the probe authenticates and prints `id`, `getprop` and `echo` correctly in
sequence, its encoded public key matches `.android/adbkey.pub` byte for byte, and both
builds still pass. **Met.**

The signature encoding was also checked against the platform's own provider, not just
re-derived by hand: `ProviderTest` signs a token and verifies the block with
`NONEwithRSA`, and rejects it under `SHA1withRSA` (which hashes the token again — the
mistake the javadoc warns about). Run as part of `bash tests/unit.sh`, all four
checks pass on the JDK's provider; the same checks passed on the phone's
Conscrypt (`AndroidOpenSSL version 1.0`) before they moved into the suite, so both
providers agree on the block `AdbKey.sign` produces.

*Risk:* protocol handshake details (checksum, `AUTH` signature padding, shell v2 vs
legacy shell). Note for M3: adb's TLS is an `STLS` upgrade of the same socket, not a
TLS socket handed in at construction, so the "wrap it unchanged" idea was wrong — the
transport needs stream injection, or libadb takes that path (see M3).

### M2 — App key, stored once, trusted once — **decision gate** · [#4](https://github.com/MBanucu/wireless-adb-keeper/issues/4)

**Status:** key storage is done and verified on the phone — `AdbIdentity` keeps the key at
`/data/user_de/0/<pkg>/files/adb-identity/adbkey.pk8` (0600), reported by `keeper.sh pubkey`.
Pairing now works on the phone: `keeper.sh pair CODE` paired the app key and
`keeper.sh pair-verify` returned `Connected as uid=2000(shell)` (2026-11-25). The one-time
input path is the pairing screen or `keeper.sh pair CODE` (a `content call` action), with
`keeper.sh pair-verify` as the shell proof. Reading the key across a reboot before unlock
is still unproven.

What exists now, and where it lives:

- `src/AdbCertificate.java` — self-signed X.509 for the ADB key, DER built by hand (no
  BouncyCastle in `src/`), stored as `adbkey.crt` beside the key. It re-parses and verifies
  its own output before writing, and `keytool -printcert` reads v3, SHA256withRSA,
  `digitalSignature`, CA:false. Regenerated if the key is replaced.
- `src-pairing/Pairing.java` — `PairingConnectionCtx` over the port advertised as
  `_adb-tls-pairing._tcp`, plus `verify()`: mDNS for `_adb-tls-connect._tcp`, then
  `AdbConnection.create(...)` and `open("shell:id")`. That reply is the M2 acceptance test.
- `src-pairing/PairActivity.java` — one screen: code field, Pair, Test connection, and a
  shortcut to Developer options. Reached from Help → Pair this phone.
- `Keeper.pair` / `Keeper.pairVerify` — the same two calls from `content call`, with no
  screen. They reach `src-pairing` by name so `build.sh` still compiles; `keeper.sh pair
  CODE` and `keeper.sh pair-verify` are the wrappers, and both are shell-only.
- `build.gradle.kts` — JitPack repository, `libadb-android:3.1.1`, `src-pairing` added to the
  Gradle source set only, and the `.PairActivity` entry injected into the staged manifest. The
  canonical manifest and `build.sh` are untouched, so the Termux APK still builds with no
  dependency and no pairing screen.

Three pieces:

**Key storage (done).** Generate an RSA keypair into app storage, export the ADB public key
in `adb`'s `name=... base64` form, and sign auth tokens with it. Store it in
**device-protected storage**, because the boot path needs it before the first unlock
(`PinnedPort` runs at `LOCKED_BOOT_COMPLETED`) and credential-encrypted storage is
unreadable there. Not AndroidKeyStore: ADB auth needs the raw key and its own
signature padding.

**Certificate.** Pairing takes an X.509 `Certificate`, not just a key, so build a
self-signed one for the ADB key (BouncyCastle, already transitive through libadb, or DER
by hand) and keep it beside the key in the same protected directory.

**Trust bootstrap.** Implement wireless-debugging pairing:
`_adb-tls-pairing._tcp` discovery, `pairing:...` service, SPAKE2, the 6-digit code.
This is the expensive part and the reason a library is on the table.

Decision gate — pick one, on measured evidence, before writing the pairing code:

| Option | Why | Why not |
| --- | --- | --- |
| Extend the M1 client with pairing | no dependency, stays Java/`build.sh`-friendly, full control | SPAKE2 + adbd's TLS cert exchange is a lot of subtle crypto to own |
| **libadb-android** (3.1.1) — **picked** | pure Java, public `PairingConnectionCtx` + `AdbConnection.create`, builds at `compileSdk 35`, Apache-2.0 OR GPL-3.0, deployed widely via App Manager | jars + native `.so` for 4 ABIs, APK 886K → 4.4M, single maintainer |
| **Kadb** (2.1.5) | best-documented pairing/TLS/mDNS surface, `open()` raw services, Apache-2.0, active | Kotlin, `minCompileSdk=37` (forces compileSdk 37), BouncyCastle's `META-INF/LICENSE*` needs a packaging exclude, ~3.5MB |
| Dadb, adblib, adb-kt | small | no pairing / unmaintained |

**Decision at the gate: libadb-android, used narrowly.** Kadb read better here when
this was written, on the assumption that `compileSdk 37` was a one-line change. It is
not: the SDK on this phone has platforms 34 and 35 only, `sdkmanager` is not on `PATH`,
and Kadb also needs the Kotlin plugin. libadb-android builds at the current 35 and its
pairing surface is public — `new PairingConnectionCtx(host, port, code, privateKey,
certificate, "pairing")` then `AdbConnection.create(host, port, key, cert)` — so it is
the pick. Kadb stays the fallback if libadb's pairing is refused by this vendor build.

The certificate piece above is what the first draft missed, and it is why M2 is not
just "add a dependency": the build gains a cert path and the app gains a second stored
artifact.

*Done when:* on a fresh install the app pairs to loopback with the code from Developer
options, and afterwards opens a service over that connection with no Termux involvement;
after a reboot the pinned port is back before the first unlock. Success is the working
connection, not the key file: the earlier wording, "`adb_keys` contains the app's key",
is untestable — `/data/misc/adb/adb_keys` is unreadable from the shell uid and the app is
not debuggable, so `run-as` cannot reach it either.

*Verified (2026-11-25):* on a fresh Gradle install, with the system pairing dialog open,
`keeper.sh pair CODE` returned `paired=true` and `keeper.sh pair-verify` returned
`connected=true` with `uid=2000(shell)`. The pairing screen itself was verified on the
same phone in #108. That closes the pairing half of M2.

*Still unproven:* reading the key across a reboot before unlock. The `user_de` path
proves the mechanism; no reboot test has run yet, so M2 remains open for that half.

*Risk:* this is the milestone that can fail. If pairing against the phone's own
adbd turns out to be refused by the vendor build, the fallback is explicit and
documented: keep Termux, close the issue as "not feasible unrooted".

### M3 — TLS transport, discovery, and `tcpip` from the app · [#5](https://github.com/MBanucu/wireless-adb-keeper/issues/5)

**Status (2026-11-25):** the in-app pin is implemented. With the app key paired
and wireless debugging on, `PinnedPort` logged `pin.tls_ok` 5 s after Wi-Fi came
up and 5555 was listening 7 s after `sys.boot_completed`, while the user was
still `RUNNING_LOCKED`. mDNS is tried first, then local listening ports in the
ephemeral range. Termux remains the fallback; `WifiRadio` still uses Termux.

With a trusted key, the app can now do what the scripts did:

- TLS client to adbd's wireless-debugging port (`CNXN`, `STLS`, adbd's client cert).
  This is **not** a wrapper around M1's transport: adb upgrades the existing socket with
  `STLS`, so either `AdbClient` grows stream injection or it stays the plain-5555 client
  and libadb owns TLS. Working position: libadb owns TLS and pairing, `AdbClient` keeps
  the plain-5555 path used for re-pin checks and `svc wifi enable`; collapse the two only
  if M3 shows one covers both.
- Port discovery without Termux: `NsdManager` for `_adb-tls-connect._tcp`, verify the
  port still accepts a connection before trusting it (the stale-advertisement problem
  `adb-mdns.py` already documents), then fall back to the same 32768–60999 scan
  (or the kernel's ephemeral range).
- `PinnedPort` calls the in-app client to send `tcpip:5555`, keeping its current
  scheduling, quiet window and "probe before asking" logic.
- Re-pin after reboot against the new TLS port, which is the actual reason the
  property cannot simply persist. Two mechanics measured with `AdbProbe --tcpip`:
  the `tcpip` request tears down the transport that sent it (adbd restarts), and the
  wireless-debugging TLS port changes with it, so the pin path must survive its own
  connection dying and rediscover the port before the next use. Re-pin itself is fast —
  200 ms and 177 ms across two runs, 3 poll attempts each — so the ≤30 s and ≈60 s
  targets below are bound by how often the app looks, not by the client.
- `WifiRadio` switches to `shell:svc wifi enable` over the now-trusted key, with the
  Termux path kept as fallback until M4 removes it. (Moved here from M1: the app cannot
  authenticate before M2.)

*Done when:* start from `adb usb` with no Termux scripts present and get 5555
reachable in ≤ 30 s; reboot untouched and get 5555 reachable within the current
≈60 s baseline.

### M4 — Termux becomes optional, then absent · [#6](https://github.com/MBanucu/wireless-adb-keeper/issues/6)

- Remove the `RUN_COMMAND` intents, `PinnedPort.SCRIPT`, `WifiRadio` fallback,
  the permission from the manifest, and the grant from `install.sh`.
- Delete `pin-adb-port.sh` / `wifi-on.sh` from the app's runtime requirements (keep
  them in the repo for the Android ≤10 path if that path is kept).
- Update `README.md`: components table, requirements, limitations, and the
  security note that `tcpip` mode is still unencrypted RSA auth on 5555.
- Settle the build split from constraint 3: the Termux-free APK is the Gradle artifact, so
  either `build.sh` ships the Termux-backed fallback with a README that says so, or it is
  retired for the dependency-carrying feature set.

*Done when:* a device with no Termux installed, no scripts, and no `RUN_COMMAND`
grant keeps wireless debugging on, re-pins 5555, and turns the radio on.

### M5 — Ship (folded into [#6](https://github.com/MBanucu/wireless-adb-keeper/issues/6))

Bump version, regenerate the screenshots that show Termux wording, tag a release,
close #1 with the measured numbers.

## Adopting the extracted libraries · [#68](https://github.com/MBanucu/wireless-adb-keeper/issues/68)

The mechanisms extracted from this repo move in one slice at a time; the app's
own decisions stay here. Only the mDNS discovery slice has a released
dependency, so it is the only one adopted now.

| piece | state |
| --- | --- |
| mDNS discovery: `adb-mdns.py` → `adb-connect` | **done.** `adb-harness[mdns]==0.2.3` is pinned in `tools/requirements.txt`; `discover.sh` runs it from `tools/.venv` or `PATH`. `scan_ports.py` stays until adb-harness grows the TCP-table/loopback scan fallback. `tools/check-adb-harness.sh` fails if a zeroconf browser or `adb-mdns.py` comes back. |
| `TermuxResult` / `TermuxResultService` → `termux-run-command` | **blocked on a release.** Not on Maven Central yet. Keep the local copies and the pin-specific freshness/fallback logic (`RESULT_FRESH_MS`, `FALLBACK_MS`, `noteProbeFallback`) until it is. |
| `ControlProvider` / `ControlReceiver` / `ControlToken` → `android-control-surface` | **blocked on a release.** Not on Maven Central yet. `keeperctl.sh` output and the existing `--method` names must not change when it lands. |
| `build.sh` → `termux-apkbuild` | **blocked on a release.** The Gradle path stays untouched. |
| CI guard for raw `RUN_COMMAND_` strings and `ContentProvider` CRUD stubs | still open; this slice adds only the mDNS guard. |

`adb-harness` 0.2.3 is the newest PyPI release and predates its documented
0/1/2/3 exit-code table (it only distinguishes 0 and 1). `discover.sh` branches
on the full table when the installed version supports it and treats the older
`1` as "nothing advertised" after checking for a missing prerequisite, so
pinning 0.2.3 now does not block a later bump.

## Out of scope

- Persistent 5555 across reboot: `persist.adb.tcp.port` is refused to both shell and
  apps (SELinux); re-pinning at boot is the correct design, not a workaround.
- Root, Magisk, Shizuku as a privileged proxy for adbd.
- Replacing the Termux-side watchdog (`adb-keepalive.sh`) — that runs on the phone
  *as* an adb host and is a different problem from the app's.

## Risks

- **Key trust is the whole ballgame.** Every milestone after M1 depends on M2
  succeeding on a real vendor build. Timebox it; the documented fallback is Termux.
- **Pre-unlock storage.** Key and any state needed at boot must live in
  device-protected storage; getting this wrong shows up as "works after unlock,
  fails on reboot", the exact case that matters.
- **Vendor divergence.** DokeOS already needed `directBootAware` written on the
  receiver; assume the TLS/pairing path needs per-version testing (11/12/13/15).
- **Library coupling.** libadb-android is the pick: 886K → 4.4M APK, jars plus native
  libs for four ABIs, single maintainer, Apache-2.0 OR GPL-3.0. Kadb (needs
  `compileSdk 37` plus Kotlin) is the fallback. Pin the version and record the reason in
  `build.gradle.kts`.
- **Two clients drift.** libadb for TLS/pairing and `AdbClient` for plain 5555 means two
  transports and two auth paths in one app. Keep the split explicit in the code and give
  the log line a way to say which one did the work, or debugging a pin failure gets
  ambiguous.
- **Background execution.** Pinning now happens in-process: a killed service means
  no re-pin, where Termux's keepalive used to survive. The existing job/boot
  fallbacks need to cover this, and the log needs to say when they did not.

## Verification

Per milestone, the acceptance line above. End to end, on the phone, with Termux
uninstalled: cold boot → 5555 reachable; `adb usb` → 5555 back within a minute;
Wi-Fi off → radio back on; airplane mode → recovery; 24 h soak with the log checked
for repeated adbd restarts (the write/notify loop `Ensurer` guards against).
Report timings against the current baseline (≤30 s, ≈60 s after boot) rather than
"works". `tools/AdbProbe --tcpip` is the reference measurement for a re-pin made by an
app-owned client (200/177 ms), and `keeper.sh status` is the cheap health check after
each run — note that a `tcpip` run costs one adb server transport drop (~5 s to reclaim).
