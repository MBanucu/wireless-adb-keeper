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
3. **Don't regress the current build.** `build.sh` must keep producing a working APK;
   dependencies are allowed only on the Gradle path until decided otherwise.
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
at all. `build.sh` untouched. Both builds verified.

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

*Scope:* `AdbClient`, `AdbKey`, `tools/AdbProbe`; `PinnedPort` and `WifiRadio` untouched.

*Done when:* the probe authenticates and prints `id`, `getprop` and `echo` correctly in
sequence, its encoded public key matches `.android/adbkey.pub` byte for byte, and both
builds still pass. **Met.**

*Risk:* protocol handshake details (checksum, `AUTH` signature padding, shell v2 vs
legacy shell). Keep the transport in one class so M3 can wrap it in TLS unchanged.

### M2 — App key, stored once, trusted once — **decision gate** · [#4](https://github.com/MBanucu/wireless-adb-keeper/issues/4)

Two separable pieces:

**Key storage.** Generate an RSA keypair into app storage, export the ADB public key
in `adb`'s `name=... base64` form, and sign auth tokens with it. Store it in
**device-protected storage**, because the boot path needs it before the first unlock
(`PinnedPort` runs at `LOCKED_BOOT_COMPLETED`) and credential-encrypted storage is
unreadable there. Not AndroidKeyStore: ADB auth needs the raw key and its own
signature padding.

**Trust bootstrap.** Implement wireless-debugging pairing:
`_adb-tls-pairing._tcp` discovery, `pairing:...` service, SPAKE2, the 6-digit code.
This is the expensive part and the reason a library is on the table.

Decision gate — pick one, on measured evidence, before writing the pairing code:

| Option | Why | Why not |
| --- | --- | --- |
| Extend the M1 client with pairing | no dependency, stays Java/`build.sh`-friendly, full control | SPAKE2 + adbd's TLS cert exchange is a lot of subtle crypto to own |
| **libadb-android** (3.1.1) | pure Java, pairing, arbitrary services, Apache-2.0 OR GPL-3.0, deployed widely via App Manager | jars + native `.so` for 4 ABIs, APK 886K → 4.4M, single maintainer |
| **Kadb** (2.1.5) | best-documented pairing/TLS/mDNS surface, `open()` raw services, Apache-2.0, active | Kotlin, `minCompileSdk=37` (forces compileSdk 37), BouncyCastle's `META-INF/LICENSE*` needs a packaging exclude, ~3.5MB |
| Dadb, adblib, adb-kt | small | no pairing / unmaintained |

Recommendation to re-check at the gate: **Kadb, used narrowly** — pairing, TLS
client and mDNS are exactly the three things we would otherwise write ourselves, and
they are the three things worth not writing ourselves. Its size is the price of the
feature, not of `tcpip`. It also forces the compileSdk 37 bump here, which is a
one-line change plus an SDK install.

*Done when:* on a fresh install, the app shows/passes the pairing code, pairs to
localhost, and `adb_keys` (checked from a shell) now contains the app's key; the
key survives a reboot and is readable before unlock.

*Risk:* this is the milestone that can fail. If pairing against the phone's own
adbd turns out to be refused by the vendor build, the fallback is explicit and
documented: keep Termux, close the issue as "not feasible unrooted".

### M3 — TLS transport, discovery, and `tcpip` from the app · [#5](https://github.com/MBanucu/wireless-adb-keeper/issues/5)

With a trusted key, the app can now do what the scripts did:

- TLS client to adbd's wireless-debugging port (`CNXN`, `STLS`, adbd's cert
  exchange), reusing the M1 transport behind a socket-agnostic layer.
- Port discovery without Termux: `NsdManager` for `_adb-tls-connect._tcp`, verify the
  port still accepts a connection before trusting it (the stale-advertisement problem
  `adb-mdns.py` already documents), then fall back to the same 30000–50000 scan.
- `PinnedPort` calls the in-app client to send `tcpip:5555`, keeping its current
  scheduling, quiet window and "probe before asking" logic.
- Re-pin after reboot against the new TLS port, which is the actual reason the
  property cannot simply persist.
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

*Done when:* a device with no Termux installed, no scripts, and no `RUN_COMMAND`
grant keeps wireless debugging on, re-pins 5555, and turns the radio on.

### M5 — Ship (folded into [#6](https://github.com/MBanucu/wireless-adb-keeper/issues/6))

Bump version, regenerate the screenshots that show Termux wording, tag a release,
close #1 with the measured numbers.

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
- **Library coupling.** Kadb requires `compileSdk 37`; libadb-android drags in
  native libs and 4× the APK. Whichever wins, pin the version and record why in
  `build.gradle.kts`.
- **Background execution.** Pinning now happens in-process: a killed service means
  no re-pin, where Termux's keepalive used to survive. The existing job/boot
  fallbacks need to cover this, and the log needs to say when they did not.

## Verification

Per milestone, the acceptance line above. End to end, on the phone, with Termux
uninstalled: cold boot → 5555 reachable; `adb usb` → 5555 back within a minute;
Wi-Fi off → radio back on; airplane mode → recovery; 24 h soak with the log checked
for repeated adbd restarts (the write/notify loop `Ensurer` guards against).
Report timings against the current baseline (≤30 s, ≈60 s after boot) rather than
"works".
