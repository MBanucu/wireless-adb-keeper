# The unit harness

The suites under `unit/java` run on a plain JVM: no device, no Android SDK, no
Gradle and no test framework. JUnit does not resolve from the offline Gradle
cache this project builds from on the phone, so the runner is a few dozen lines
of Java instead.

## Layout

```
tests/
  unit.sh                              # discovery, cached javac, one JVM
  gate.sh                              # counts, empty-run guard, timing gate
  unit/harness/SuiteRunner.java        # runs every named suite in one JVM
  unit/harness/TestSupport.java        # ok/FAIL lines, counts, exit code
  unit/java/.../*Test.java             # the suites
```

`unit.sh` finds every `*Test.java`, derives its class name from the path, and
passes the names to `SuiteRunner`. Adding a suite needs no edit here or in
`build.gradle.kts`.

## Running

```bash
bash tests/unit.sh                 # no Gradle, no SDK needed for pure suites
./gradlew probeUnit                # the same script through Gradle
./gradlew probe                    # probeUnit plus the tools/ probes
./gradlew probeBuildTooling        # wrapper hash guard + termux-build.sh's env handling
./gradlew checkStagedManifest      # the generated manifest still has its components
./gradlew checkDependencyPolicy    # libadb-android still has a verification checksum
./gradlew check                    # probeUnit, probes, manifest/policy checks and lint

# What CI does: run the suites, then let the gate read the log.
mkdir -p tests/out
bash tests/unit.sh > tests/out/unit.log 2>&1; status=$?
bash tests/gate.sh --log tests/out/unit.log --status "$status"
```

Output is one `ok`/`FAIL` line per check, then `PASS n <suite>` or
`FAIL n of m <suite>` for each suite. The exit code is 1 when any check failed.
`SuiteRunner` also prints `timing: <class>#main <ms> passed` per suite, the
`test-timings` format used by android-accessible-terminal.

`./gradlew probe` also runs `tools/DiagProbe.java`, the host-only redaction
probe. It writes a fake passphrase, token and pairing code through the real
journal append path into a temporary file and fails if any of them appears in
the file. It needs no device and no Android runtime, because the formatting,
retention and rotation decisions live in the pure classes the probe and the app
share. A deliberate failure prints `redaction probe: FAIL n check(s)` and exits
non-zero.

## The CI gate

CI has one `tests` job that runs, in this order:

1. `bash tests/unit.sh` on the plain JDK, before any Gradle step. This is the
   phone path: no `./gradlew`, no `build.sh`, no test framework.
2. `bash tests/gate.sh`, which reads that log.
3. `./gradlew checkBuildEnv probe lintDebug assembleDebug` (locally add
   `--offline`; CI resolves the wrapper and dependencies over the network it
   already uses). The wrapper JAR and the distribution SHA-256 are validated
   before this step.

`tests/gate.sh` prints the counts every time, not only a verdict:

```
gate: checks: 196 (0 failed)
gate: suites: 11
gate: timing lines: 11 (1155ms total)
gate: timing: median 1140ms of last 5; limit 4560ms (4x), floor 1000ms
gate: PASS 196 checks in 11 suites
```

It exits 1 when `unit.sh` failed, when no checks, suites or `timing:` lines were
found (a zero-test run is red), when the number of `timing:` lines does not
match the number of suites, or when the timed total is more than 4x the median
of the last ten green runs. The median is `tests/out/timings.tsv`, one total-ms
line per run; CI restores it with `actions/cache` and appends only green runs.
Fewer than three samples, or a missing/corrupt history file, skips the
regression comparison instead of failing it. The log and summary are uploaded as
artifacts on failure; only the timing history and the javac stamp are cached,
never the suite output.

A red run should not need a rerun to explain itself. The failure artifact holds
`unit.log` (the `FAIL <check>: expected <x> got <y>` lines), `failing-checks.txt`
(just those lines), `gate-summary.txt`, `gradle.log`, and lint's
`lint-results-debug.html`. Compilation and lint failures still fail the Gradle
step; `lintDebug` uses `abortOnError = true`.

### What green proves

Green means every discovered JVM check passed, the staged manifest and the
dependency policy still hold, the build tooling accepts and rejects the right
wrapper files, lint reported nothing fatal, and the debug APK assembled. It does **not** mean the app works on a phone: no unit
suite can reach the real adbd, a `Context`, notifications, the activity or the
network. Those are the manual device checks below.

The device suite is never in CI, by design. CI is a container with no phone, no
adbd on `127.0.0.1:5555` and no Termux `RUN_COMMAND` delivery, so a device job
would only test a mock and call it green. Device behaviour is verified by hand:
`./gradlew probeAdb` against a running adbd (plus `-PadbTcpip=true` for the
re-pin path), and a device suite such as `tests/device.sh` is run manually on the
phone. Neither is part of `probe`, and neither belongs in this workflow.

## Writing a suite

A suite is a `public final` class with a `public static void main(String[])`,
because `SuiteRunner` calls it reflectively:

```java
package io.github.mbanucu.wirelessadbkeeper;

import static io.github.mbanucu.wirelessadbkeeper.harness.TestSupport.*;

public final class FooTest {
    private FooTest() {
    }

    public static void main(String[] args) {
        check("expected value", 2 + 2, 4);
        checkTrue("a condition", "x".startsWith("x"));
        checkThrows("bad input is rejected", IllegalArgumentException.class,
                () -> Integer.parseInt("nope"));
        report("FooTest");
    }
}
```

`TestSupport` has `check(what, expected, actual)`, `checkTrue(what, actual)`,
`checkThrows(what, expectedType, body)` and `report(suite)`. Counters reset in
`report`, so suites can share the one JVM.

## Compilation and the cache

Compilation is skipped when the sorted source list, every source's mtime and
size, and the chosen `android.jar` are unchanged since the last run. A deleted
suite changes the list, so its class is removed with the rest of the output
rather than left behind.

`android.jar` is found in the same order as `build.sh`: `ANDROID_JAR`, then the
newest platform under `ANDROID_HOME` or `ANDROID_SDK_ROOT` (what CI sets), then
`$HOME/android-sdk/platforms/*/android.jar`, then a jar beside `tests/unit.sh`
or at the repository root. The SDK wins over a stale root jar on purpose: the
app targets constants that an older jar does not have. With a
jar, all of `src/` is compiled against it. Without one, only sources that do
not `import android.*` are compiled, and the two ported probe suites below are
skipped with a message; the pure seam suites still run.

The **run** classpath contains the test output only - deliberately not
`android.jar`. A suite that reaches an Android API by accident fails with
`NoClassDefFoundError` instead of passing because a stub happened to be on the
classpath.

The identity and certificate suites are the exception to the jar-less subset:
their classes (`AdbIdentity`, `AdbCertificate`) import `android.content.Context`
for the device-protected storage entry points, which the suites never call.
They need the jar to compile, like the app itself; the suites over pure classes
(`DiagFormat`, `DiagJournal`, `DiagRetention`, `DiagRotation`, `DiagSnapshot`,
`DiagReasons`, `PermissionAudit`, `SuggestionRecord`, `SuggestionRegistry`,
`ProfileTable`, `NetworkRows`, `Backoff`, `EnsureDecision`, `ControlArgs`,
`Base64Codec`, `Provider`, `TermuxResult`, `VerboseLog`) do not.

## The device suite

`tests/device.sh` drives the installed app through `keeper.sh` on the phone:
the Status page, the journal, redaction, permissions, recovery after a
force-stop, error replies, the 200-event ring, and a timed adb reachability
check. It is deliberately not in CI. A missing phone is a skip, never a pass.

```bash
bash tests/device.sh                 # every suite except the slow one
bash tests/device.sh --only diag     # one suite, or --only diag,status
bash tests/device.sh --list          # the suite names, no device needed
bash tests/device.sh --quiet         # failures and the summary only
bash tests/device.sh --serial 127.0.0.1:5555
bash tests/device.sh --record        # optional adb-harness video and events
bash tests/device.sh --connect       # opt in to setup auto-reconnect
bash tests/device.sh --restore       # put back a dead run's state
bash tests/device.sh --slow          # add cap.sh (210 mode changes, in parallel)
bash tests/device.sh --allow-reboot  # opt in to the #39 reboot in recovery.sh
DEVICE_WIFI_SSID=WP22 DEVICE_WIFI_PASSPHRASE=... \
    bash tests/device.sh --only wifi # opt-in real-network check
```

The flags follow `android-accessible-terminal/samples/e2e.sh`: `--list`,
`--only` (a name, a comma list or a space list), `--quiet`, `--restore`, and
`--serial`, which is passed through every adb call. `--slow` and
`--allow-reboot` keep their old meaning. `--record` is the only tier-2 flag.

The `wifi` suite is skipped in the default run because it needs a real
credential. Set `DEVICE_WIFI_PASSPHRASE` (and optionally `DEVICE_WIFI_SSID`,
default `WP22`) and select it explicitly. It stores the password through the
provider, proves auto-connect reports `suggestion <id>: active`, and proves the
password is absent from the profile list, SharedPreferences and the diag
report. Its cleanup clears the password, removes the suggestion and restores
the previous mode; a failed assertion reports that the secret was found without
printing it. The value is never committed.

The slow `cap` suite is the one long default check. Each host-side `keeper`
call starts a new adb shell and `content` process (about 1.1 s), so 210 calls
used to take about 229 s. The calls are now issued in two device-side parallel
batches, followed by one sequential write to make the newest event
(`auto-connect`) deterministic; the same assertions take about 17 s.

### The three tiers

1. **Default — adb, bash and coreutils.** The suites use `keeper.sh` and
   `run-as` for the journal and prefs; the semantic-UI helpers use
   `uiautomator dump` for text. A failing check writes the failing command,
   expected value, actual value, the journal tail, a `uiautomator` dump and a
   `screencap` into a per-run artifact directory. No APK, Python, ffmpeg or
   sibling repository is needed.
2. **`--record` — optional `adb-harness`.** When `capture` is on `PATH` the
   runner prints its `--version`, calls `capture start`, writes
   `check:<suite>:start`, `:end` or `:failed` for each suite, and calls
   `capture stop` at the end. A failing suite then gets
   `capture frames --event check:<suite>:failed`. Without the command it prints
   `recording skipped: adb-harness not found` and the checks run unchanged.
   Recording is never required for a pass and never vendored.
3. **`--ui semantic` — deferred, not shipped.** Tier 1 is the default until a
   real check shows `uiautomator dump` is too weak for the Diag tab or the
   Network page. The evidence condition is a failing check with its dump
   attached to #58. Only then is the four-source `a11y-probe` copied into
   `tests/e2e/a11y-probe/`, built with `build.sh`, enabled for the run and the
   previous accessibility setting restored afterwards, as
   `samples/e2e.sh` does. No accessibility APK or probe source is in this
   repository today, and `--ui semantic` is not accepted by the runner.

Requirements for tier 1:

- The **debuggable Gradle APK**, not the `build.sh` APK. The journal and the
  SharedPreferences are read and restored with `run-as`, which an APK whose
  manifest is not debuggable refuses. The runner says which package it could
  not enter and exits 3 without running a suite.
- The device is unlocked (the Wi-Fi prefs live in credential-protected
  storage; the runner refuses an `unlocked=false` diag), `adb get-state`
  returns `device`, and the package is installed.
- Termux with the `RUN_COMMAND` grant is needed by the pin/connect checks, as
  on a normal install. Use `--serial` (or `PHONE_SERIAL`) when more than one adb
  target is attached.

Exit codes, one convention across the device suites:

| code | meaning |
| --- | --- |
| 0 | every selected suite passed |
| 1 | a check failed, or the state restore did not complete |
| 2 | bad option, or an unknown `--only` name |
| 3 | blocked: no adb, no device, a missing required tool, setup failed, no suite ran, or a selected suite skipped itself |

A skip is never 0. A named skip inside a suite (`skip <check>: <reason>`) prints
the reason and does not pretend that check passed; the suite can still pass its
other checks. A suite that skips as a whole exits 3. This deliberately differs
from `freebuff-native-termux`, which uses 77 for a skip.

### What green proves

A green device run means every selected check executed on the real phone and
the snapshot was restored and verified, not that a UI looked right. The
`uiautomator` helpers are exercised without a device by
`tools/device-tooling-test.sh` (a fake dump); no device suite asserts the app's
rendered Diag or Network page yet. The reboot path is still opt-in and cannot
run on this phone, which is both the host and the target.

### Layout

```
tests/
  device.sh                         # discovery, flags, snapshot, trap, timing
  device/lib.sh                     # adb/UI/artifact/assert/state helpers
  device/status.sh                  # documented fields and port consistency
  device/diag.sh                    # journal format/order, both readers, force-stop
  device/redaction.sh               # canary passphrase, journal and prefs
  device/permissions.sh             # revoke/grant location with the app running
  device/recovery.sh                # force-stop, service and 5555 return
  device/errors.sh                  # unknown method, missing SSID, gate
  device/reach.sh                   # timed adb reachability, auto-heal off
  device/cap.sh                     # slow: the 200-event ring
tools/device-tooling-test.sh        # fake adb/capture/connect, no device
tests/out/device-state/             # saved state, only while a run needs it
tests/out/device/<run>/             # failures, dumps, screenshots, recording
```

`device.sh` sources `lib.sh` once, then sources each suite in a subshell. A
suite that is run directly refuses with the runner command, because it would
otherwise change the phone without a snapshot. Adding a suite is a file drop;
`# device-slow: 1` marks one that only runs with `--slow` (or `--only`).

`lib.sh` has the tier-1 primitives a growing UI or recovery suite needs:

- `ui_dump`, `ui_find`, `ui_text`, `ui_tap`, `assert_ui_contains`,
  `assert_ui_absent`: text and content descriptions, never a hard-coded
  coordinate. `ui_tap` derives a centre from the node when a check has to move
  between pages.
- `artifact_failure`: the dump and screenshot written on a failure; the runner
  calls it for an assertion, an ERR abort and a suite that exits non-zero.
- `timing`: one `timing: tests/device/<suite>.sh#main <ms>ms <passed|failed>`
  line per suite that ran, in the `test-timings` format. A blocked suite is
  written as `failed` because `skipped` is not a status the timing gate parses;
  a suite excluded by `--slow` does not print a timing line at all.
- `adb_online`, `reconnect_ensure`, `reconnect_report`, `autoheal_off`: the
  reconnect policy below.

### Reconnect policy for #39

Setup does not heal a dropped device by default. `--connect` (or
`DEVICE_AUTOHEAL=1`) allows at most `DEVICE_RECONNECT_MAX` attempts (3 by
default). Each attempt prints and appends
`attempt <n>/<max> at <UTC timestamp>` to
`tests/out/device/<run>/reconnect.log`; `adb-connect` is used when installed,
`adb reconnect` otherwise. `DEVICE_RECONNECT_DELAY` is the pause between
attempts. `adb-watch` is a long-running daemon and is not started by a timed
check.

`reach.sh` calls `autoheal_off` and asserts that the setup time is within
`DEVICE_REACH_BOUND_MS` (15 s by default), printing the attempts. A target that
needed a reconnect is not a failure; one that needed longer than the bound is.
A run without `--connect` cannot silently pass a reachability check it healed,
because nothing was healed.

### The state contract

Before any suite, the runner records:

- connect mode (`auto-connect` / `local-only`), the saved SSID, whether a
  passphrase is stored (never its value), and the monitor service state;
- `granted` / `denied` / `unknown` for `ACCESS_COARSE_LOCATION`,
  `ACCESS_FINE_LOCATION`, `POST_NOTIFICATIONS`, `WRITE_SECURE_SETTINGS` and
  Termux's `RUN_COMMAND`;
- a byte copy of `shared_prefs/local_wifi.xml` and
  `shared_prefs/wifi_suggestion.xml` (including their `.bak` files).

The snapshot lives in `tests/out/device-state/` (override with
`DEVICE_STATE`/`DEVICE_STATE_DIR`), not in a random temporary directory. That is
what makes `--restore` possible: a run killed before its trap leaves the
snapshot on disk, the next normal run repairs the phone before taking a new
snapshot, and `bash tests/device.sh --restore` does the repair explicitly and
then removes the saved state. A restore that fails keeps the snapshot and says
so instead of deleting the only record of what the phone should look like.

The runner installs a trap on `EXIT` and `INT` (and `TERM`). It stops any
recording (so the video is not cut mid-file), then restores in this order:

1. If a temp profile was saved, put the monitor back in local mode while the
   temp applied record is still present, so the app's own
   `WifiSuggestion.remove` path drops the suggestion. A restored prefs file
   alone would hide it from `add()`.
2. `am force-stop` the app, write the saved prefs back (or remove files that
   did not exist), so the encrypted passphrase and the user's SSID are exactly
   what they were.
3. Grant or revoke every permission whose current state differs from the
   snapshot.
4. Restore the monitor service state and the mode.
5. Re-read mode, SSID, passphrase presence and every permission, then check
   the saved prefs and `dumpsys wifi` for any `zz-test` SSID. Any difference is
   a restore failure and the runner exits non-zero.

Tests may only save SSIDs of the form `zz-test-<runner pid>-<n>`, and only via
the tracked `temp_profile` helper. They never save, forget or edit the user's
saved network; the original prefs are restored byte for byte. A test that
fails half way still restores, because the trap belongs to the parent runner,
not the failed subshell. `tools/device-tooling-test.sh` proves that order with
fakes: local mode, `am force-stop`, then the saved mode, with the saved prefs
byte-identical afterwards.

Everything the run writes on this side is under `tests/out/`, which is already
ignored: the saved state while a run or a dead run needs it, and one artifact
directory per run holding the failed dumps, screenshots, `capture.log`,
`reconnect.log` and, with `--record`, the frames of the failures. The runner
never deletes the artifact directory; a red run can be explained by copying it.

### Running a suite by hand

The same checks can be reproduced without the runner, but they do not restore
anything. Snapshot by hand first (mode, SSID, permission) and restore it
afterwards. The useful primitives are:

```bash
./keeper.sh status
./keeper.sh diag
adb shell run-as io.github.mbanucu.wirelessadbkeeper \
    cat /data/user_de/0/io.github.mbanucu.wirelessadbkeeper/files/diagnostics/events.log
adb shell run-as io.github.mbanucu.wirelessadbkeeper cat shared_prefs/local_wifi.xml
adb shell dumpsys package io.github.mbanucu.wirelessadbkeeper | grep granted=
adb shell pm revoke io.github.mbanucu.wirelessadbkeeper android.permission.ACCESS_FINE_LOCATION
adb shell pm grant  io.github.mbanucu.wirelessadbkeeper android.permission.ACCESS_FINE_LOCATION
```

`--only redaction` is the quickest sanity check: it saves a canary profile,
greps both readers, deletes the profile and restores the SSID, usually well
under 20 s on this phone.

### What the device suite never asserts

- The OS dialog, notification wording or timing for approving a Wi-Fi
  suggestion. Only the app's side (`wifi.suggest.added` and the status code)
  is visible.
- Suggestion ranking or which network Android prefers. That is platform
  policy, not the app.
- A real `LOCKED_BOOT_COMPLETED` broadcast. It is protected and cannot be sent
  from adb; the reboot check waits for the boot path instead, and only with
  `--allow-reboot`. The phone has to unlock after the reboot, because the
  Wi-Fi prefs are unreadable while it is locked. The package-replaced path is
  what installs actually exercise.
- The user's own SSID or credentials. Tests only save `zz-test` profiles and
  only ever see whether a passphrase is present.
- The provider's 403 for an ordinary app uid, unless `DEVICE_FOREIGN_UID_CMD`
  gives a non-shell caller (root or a helper app). The suite names that skip.
- The app's rendered Diag tab or Network page. The tier-1 `ui_*` helpers exist
  and are tested against a fake dump, but no device suite drives those pages
  yet; tier 3 (`--ui semantic`) is deferred until a real dump fails with its
  evidence attached to #58.
- Pixel-level rendering, accessibility event timing, logcat, and anything that
  depends on how fast Android chooses to reconnect. The reachability check
  bounds the setup time and prints the attempts; it does not promise a
  particular reconnect latency.

## What the unit harness deliberately does not test

- Android framework behaviour: `Context`, storage permissions, broadcasts,
  `WifiManager`, notifications, services and the activity. Those need a device.
- The real adbd connection, pairing/TLS and the network. The pairing code also
  lives in `src-pairing`, which needs `libadb-android` and is not on this
  classpath.
- `RUN_COMMAND` delivery from Termux, the app's receiver wiring and the Status
  page. `tools/result-contract.sh` checks the scripts' side with a fake adb, and
  `probeAdb` is the manual phone check.
- Script behaviour beyond the `RESULT` contract. `tools/AdbProbe` stays a
  manual device probe; `tools/TokenProbe` and `tools/ResultProbe` remain
  runnable alongside their Gradle tasks. The old identity, certificate and
  provider probes are now `IdentityTest`, `CertificateTest` and `ProviderTest`,
  so one gate counts their checks.
- UI, accessibility and anything timing-dependent on the phone.

The harness also does not pretend that zero suites is a pass: `unit.sh` exits 1
when it finds no `*Test.java`.
