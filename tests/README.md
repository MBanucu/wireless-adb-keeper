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
(`DiagFormat`, `DiagSnapshot`, `SuggestionRecord`, `ProfileTable`, `Backoff`,
`EnsureDecision`, `ControlArgs`, `Base64Codec`, `Provider`, `TermuxResult`) do
not.

## What this deliberately does not test

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
