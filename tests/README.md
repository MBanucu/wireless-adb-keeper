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
./gradlew check                    # probeUnit, probes and lint

# What CI does: run the suites, then let the gate read the log.
mkdir -p tests/out
bash tests/unit.sh > tests/out/unit.log 2>&1; status=$?
bash tests/gate.sh --log tests/out/unit.log --status "$status"
```

Output is one `ok`/`FAIL` line per check, then `PASS n <suite>` or
`FAIL n of m <suite>` for each suite. The exit code is 1 when any check failed.
`SuiteRunner` also prints `timing: <class>#main <ms> passed` per suite, the
`test-timings` format used by android-accessible-terminal.

## The CI gate

CI has one `tests` job that runs, in this order:

1. `bash tests/unit.sh` on the plain JDK, before any Gradle step. This is the
   phone path: no `./gradlew`, no `build.sh`, no test framework.
2. `bash tests/gate.sh`, which reads that log.
3. `./gradlew probe lintDebug assembleDebug` (locally add `--offline`; CI
   resolves the wrapper and dependencies over the network it already uses).

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

Green means every discovered JVM check passed, lint reported nothing fatal, and
the debug APK assembled. It does **not** mean the app works on a phone: no unit
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
(`DiagFormat`, `DiagSnapshot`, `DiagReasons`, `PermissionAudit`, `SuggestionRecord`,
`ProfileTable`, `Backoff`, `EnsureDecision`, `ControlArgs`, `Base64Codec`,
`Provider`, `TermuxResult`, `VerboseLog`) do not.

## The device suite

`tests/device.sh` drives the installed app through `keeper.sh` on the phone:
the Status page, the journal, redaction, permissions, recovery after a
force-stop, error replies, and the 200-event ring. It is deliberately not in
CI. A missing phone is a skip, never a pass.

```bash
bash tests/device.sh                 # every suite except the slow one
bash tests/device.sh --only diag     # one suite, by file name
bash tests/device.sh --slow          # add cap.sh (210 mode changes)
bash tests/device.sh --allow-reboot  # opt in to the #39 reboot in recovery.sh
```

Requirements:

- The **debuggable Gradle APK**, not the `build.sh` APK. The journal and the
  SharedPreferences are read and restored with `run-as`, which an APK whose
  manifest is not debuggable refuses. The runner says which package it could
  not enter and exits 4 without running a suite.
- The device is unlocked (the Wi-Fi prefs live in credential-protected
  storage; the runner refuses an `unlocked=false` diag), `adb get-state`
  returns `device`, and the package is installed.
- Termux with the `RUN_COMMAND` grant is needed by the pin/connect checks, as
  on a normal install. Use `PHONE_SERIAL=...` when more than one adb target is
  attached.

Exit codes:

| code | meaning |
| --- | --- |
| 0 | every selected suite passed |
| 1 | a check failed, or the state restore did not complete |
| 2 | bad option or unknown `--only` name |
| 3 | no device: `adb get-state` was not `device`; nothing ran |
| 4 | setup failed, no suite ran, or a selected suite skipped itself |

A named skip inside a suite (`skip <check>: <reason>`) prints the reason and
does not pretend that check passed. The suite can still pass its other checks;
a suite that skips as a whole exits 4.

### Layout

```
tests/
  device.sh                         # discovery, snapshot, trap, timing
  device/lib.sh                     # keeper/journal/prefs/assert/state helpers
  device/status.sh                  # documented fields and port consistency
  device/diag.sh                    # journal format/order, both readers, force-stop
  device/redaction.sh               # canary passphrase, journal and prefs
  device/permissions.sh             # revoke/grant location with the app running
  device/recovery.sh                # force-stop, service and 5555 return
  device/errors.sh                  # unknown method, missing SSID, gate
  device/cap.sh                     # slow: the 200-event ring
```

`device.sh` sources `lib.sh` once, then sources each suite in a subshell. A
suite that is run directly refuses with the runner command, because it would
otherwise change the phone without a snapshot. Adding a suite is a file drop;
`# device-slow: 1` marks one that only runs with `--slow` (or `--only`).

### The state contract

Before any suite, the runner records:

- connect mode (`auto-connect` / `local-only`), the saved SSID, whether a
  passphrase is stored (never its value), and the monitor service state;
- `granted` / `denied` / `unknown` for `ACCESS_COARSE_LOCATION`,
  `ACCESS_FINE_LOCATION`, `POST_NOTIFICATIONS`, `WRITE_SECURE_SETTINGS` and
  Termux's `RUN_COMMAND`;
- a byte copy of `shared_prefs/local_wifi.xml` and
  `shared_prefs/wifi_suggestion.xml` (including their `.bak` files).

The runner installs a trap on `EXIT` and `INT` (and `TERM`). It restores in
this order:

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
not the failed subshell.

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
- UI rendering, accessibility, logcat, and anything that depends on how fast
  Android chooses to reconnect.

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
