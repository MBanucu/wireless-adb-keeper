#!/data/data/com.termux/files/usr/bin/bash
# Exercise tests/device.sh and tests/device/lib.sh with a stateful fake adb, a
# fake `capture`, and a fake `adb-connect`. No phone, no adb device, no
# adb-harness install: the point is the runner's contracts - exit codes, the
# recording tier, failure artifacts, reconnect logging, and the restore trap.
#
#   bash tools/device-tooling-test.sh
#
# Wired into `./gradlew probeDeviceTooling` and therefore probeScripts.
set -u

ROOT=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd) || exit 1
WORK=$(mktemp -d "${TMPDIR:-/tmp}/device-tooling.XXXXXX") || exit 1
trap 'rm -rf "$WORK"' EXIT

BIN=$WORK/bin
STATE=$WORK/state
SUITES=$WORK/suites
ART=$WORK/artifacts
DEVSTATE=$WORK/device-state
OUT=$WORK/out
mkdir -p "$BIN" "$STATE" "$SUITES" "$ART" "$WORK/tmp"

failures=0
check() {
    local what=$1 ok=$2
    if [ "$ok" = 1 ]; then
        echo "ok   $what"
    else
        echo "FAIL $what"
        failures=$((failures + 1))
    fi
}

syntax() {
    local script=$1
    if bash -n "$ROOT/$script" 2>/dev/null; then
        check "bash -n $script" 1
    else
        check "bash -n $script" 0
    fi
}

syntax tests/device.sh
syntax tools/device-tooling-test.sh
for suite in tests/device/*.sh; do
    syntax "$suite"
done

cat > "$BIN/adb" <<'FAKE_ADB'
#!/bin/sh
set -u
state=${FAKE_STATE:?}
printf '%s\n' "$*" >> "$state/calls.log"
if [ "${1:-}" = "-s" ]; then
    serial=${2:-}
    shift 2
    printf 'serial\t%s\n' "$serial" >> "$state/calls.log"
else
    serial=
fi
cmd=${1:-}

online() { [ -f "$state/online" ]; }
ssid_value() {
    local value=
    if [ -f "$state/prefs/local_wifi.xml" ]; then
        value=$(sed -n 's/.*name="ssid">\([^<]*\)<.*/\1/p' "$state/prefs/local_wifi.xml" | head -n 1)
    fi
    printf '%s\n' "${value:-$(cat "$state/ssid" 2>/dev/null || echo Home)}"
}
mode() {
    case "$(cat "$state/mode" 2>/dev/null || echo auto-connect)" in
        auto|auto-connect) printf 'auto-connect\n' ;;
        local|local-only) printf 'local-only\n' ;;
        *) cat "$state/mode" ;;
    esac
}
emit_status() {
    printf 'Result: Bundle[{code=200, data=mode=%s\nssid=%s\nservice=%s\n' \
        "$(mode)" "$(ssid_value)" \
        "$(cat "$state/service" 2>/dev/null || echo running)"
    printf 'permission=granted\nlocation=granted\npinned_port=listening on 5555\njournal_events=1}]\n'
}
emit_diag() {
    printf 'Result: Bundle[{code=200, data=unlocked=true\nmode=%s\nssid=%s\nservice=%s\nport_5555=listening on 5555\njournal_events=1}]\n' \
        "$(mode)" "$(ssid_value)" \
        "$(cat "$state/service" 2>/dev/null || echo running)"
}
emit_error() {
    printf 'Result: Bundle[{code=400, data=error=%s}]\n' "$1"
}

shell_call() {
    local sys=$*
    case $sys in
        "content call"*)
            set -- $sys
            shift 2
            method= arg= extra=
            while [ "$#" -gt 0 ]; do
                case $1 in
                    --method) method=${2:-}; shift 2 || break ;;
                    --arg) arg=${2:-}; shift 2 || break ;;
                    --extra) extra=${2:-}; shift 2 || break ;;
                    *) shift ;;
                esac
            done
            case $method in
                status) emit_status ;;
                diag) emit_diag ;;
                set_mode)
                    case $arg in
                        auto) printf 'auto-connect\n' > "$state/mode" ;;
                        local) printf 'local-only\n' > "$state/mode" ;;
                        *) printf '%s\n' "$arg" > "$state/mode" ;;
                    esac
                    emit_status
                    ;;
                save_wifi)
                    printf '%s\n' "$arg" > "$state/ssid"
                    printf '<?xml version="1.0" encoding="utf-8"?>\n<map>\n' \
                        > "$state/prefs/local_wifi.xml"
                    printf '    <string name="ssid">%s</string>\n' "$arg" \
                        >> "$state/prefs/local_wifi.xml"
                    printf '    <string name="profile.2_ciphertext">saved</string>\n</map>\n' \
                        >> "$state/prefs/local_wifi.xml"
                    printf 'Result: Bundle[{code=200, data=ssid=%s\npassphrase=set}]\n' "$arg"
                    ;;
                service_stop)
                    printf 'stopped\n' > "$state/service"
                    printf 'Result: Bundle[{code=200, data=service=stopped}]\n'
                    ;;
                connect|pin_port|pubkey|schedule)
                    printf 'Result: Bundle[{code=200, data=ok=true}]\n'
                    ;;
                *) emit_error "unknown method" ; exit 1 ;;
            esac
            ;;
        "pm path "*) printf 'package:/data/app/fake/base.apk\n' ;;
        dumpsys\ package*)
            for perm in \
                android.permission.ACCESS_COARSE_LOCATION \
                android.permission.ACCESS_FINE_LOCATION \
                android.permission.POST_NOTIFICATIONS \
                android.permission.WRITE_SECURE_SETTINGS \
                com.termux.permission.RUN_COMMAND; do
                printf '%s: granted=true\n' "$perm"
            done
            ;;
        dumpsys\ wifi*) printf 'WifiService dump\n' ;;
        "run-as "*)
            case $sys in
                *"test -f shared_prefs/"*)
                    file=$(printf '%s' "$sys" | sed 's/.*test -f shared_prefs\/\([^ ]*\).*/\1/')
                    [ -f "$state/prefs/$file" ]
                    exit $?
                    ;;
                *"cat /data/user_de/"*) cat "$state/events.log" ;;
                *"cat shared_prefs/*"*)
                    cat "$state"/prefs/*.xml 2>/dev/null || true
                    ;;
                *"cat shared_prefs/"*)
                    file=$(printf '%s' "$sys" | sed 's/.*cat shared_prefs\/\([^ ]*\).*/\1/')
                    cat "$state/prefs/$file" 2>/dev/null || true
                    ;;
                *"cat > shared_prefs/"*)
                    file=$(printf '%s' "$sys" | sed 's/.*cat > shared_prefs\/\([^ ]*\).*/\1/')
                    cat > "$state/prefs/$file"
                    ;;
                *"rm -f shared_prefs/"*)
                    file=$(printf '%s' "$sys" | sed 's/.*rm -f shared_prefs\/\([^ ]*\).*/\1/')
                    rm -f "$state/prefs/$file"
                    ;;
                *"sh -c true"*) ;;
                *) ;;
            esac
            ;;
        "uiautomator dump"*)
            printf 'UI hierchary dumped to: %s\n' "$state/ui.xml"
            ;;
        "cat /proc/net/tcp"*) printf '  sl  local_address rem_address st\n   0: 00000000:15B3 00000000:0000 0A\n' ;;
        "timeout 2 nc "*) exit 0 ;;
        "input tap "*) printf 'tap\n' ;;
        "am force-stop "*) printf 'stopped\n' > "$state/service" ;;
        "am start-foreground-service "*) printf 'running\n' > "$state/service"; printf 'Starting service\n' ;;
        "pm grant "*|"pm revoke "*) printf 'ok\n' ;;
        "getprop "*) printf '1\n' ;;
        *) ;;
    esac
    exit 0
}

case $cmd in
    get-state)
        if online; then
            printf 'device\n'
        else
            printf 'error: device offline\n' >&2
            exit 1
        fi
        ;;
    devices)
        printf 'List of devices attached\n'
        online && printf '127.0.0.1:5555\tdevice\n'
        ;;
    connect)
        if [ -f "$state/connect_works" ]; then
            touch "$state/online"
            printf 'connected to %s\n' "${1:-127.0.0.1:5555}"
        else
            printf 'failed to connect: Connection refused\n' >&2
            exit 1
        fi
        ;;
    reconnect)
        if [ -f "$state/reconnect_works" ]; then
            touch "$state/online"
        fi
        ;;
    disconnect) rm -f "$state/online" ;;
    shell) shift; shell_call "$@" ;;
    exec-out)
        shift
        case ${1:-} in
            cat)
                shift
                case ${1:-} in
                    /sdcard/*) cat "$state/ui.xml" ;;
                    *) cat "$state/prefs/${1##*/}" 2>/dev/null || true ;;
                esac
                ;;
            screencap) printf '\211PNG\r\n\032\nfake screenshot\n' ;;
            *) ;;
        esac
        ;;
    *) ;;
esac
exit 0
FAKE_ADB
chmod +x "$BIN/adb"

cat > "$BIN/adb-connect" <<'FAKE_CONNECT'
#!/bin/sh
set -u
state=${FAKE_STATE:?}
case ${1:-} in
    --version)
        printf 'adb-connect 9.9-fake\n'
        exit 0
        ;;
esac
printf '%s\n' "$*" >> "$state/connect.log"
[ -n "${FAKE_CONNECT_DELAY:-}" ] && sleep "$FAKE_CONNECT_DELAY"
touch "$state/online"
printf '{"schema":"adb-connect","version":1,"ok":true,"port":5555}\n'
FAKE_CONNECT
chmod +x "$BIN/adb-connect"

cat > "$BIN/capture" <<'FAKE_CAPTURE'
#!/bin/sh
set -u
state=${FAKE_STATE:?}
printf '%s\n' "$*" >> "$state/capture.log"
case ${1:-} in
    --version) printf 'capture 9.9-fake\n' ;;
    start)
        if [ "${FAKE_CAPTURE_FAIL_START:-0}" = 1 ]; then
            printf 'capture start: fake failure\n' >&2
            exit 1
        fi
        printf '{"schema":"capture-start","version":1,"recording":true}\n'
        ;;
    event) ;;
    stop) printf 'recording: fake\n' ;;
    frames) printf 'frames: fake\n' ;;
    *) ;;
esac
exit 0
FAKE_CAPTURE
chmod +x "$BIN/capture"

# The suites are files, so the runner's discovery, --only and slow handling are
# exercised without copying the real ones (which would need a phone).
cat > "$SUITES/pass.sh" <<'SUITE'
#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
if [ -z "${DEVICE_STATE:-}" ]; then
    echo "run through tests/device.sh" >&2
    exit 2
fi
# shellcheck disable=SC1091
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
assert_eq "the fake mode is the snapshot mode" auto-connect "$(keeper status | field mode)"
assert_contains "the fake journal has a service start" 'svc.start' "$(journal)"
assert_ui_contains "the Diagnostics tab is in the dump" Diagnostics
assert_ui_absent "there is no failure banner" 'fatal error'
ui_tap Diagnostics
SUITE
cat > "$SUITES/fail.sh" <<'SUITE'
#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
if [ -z "${DEVICE_STATE:-}" ]; then
    echo "run through tests/device.sh" >&2
    exit 2
fi
# shellcheck disable=SC1091
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
assert_eq "the fake value is deliberately wrong" expected actual
SUITE
cat > "$SUITES/skip.sh" <<'SUITE'
#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
if [ -z "${DEVICE_STATE:-}" ]; then
    echo "run through tests/device.sh" >&2
    exit 2
fi
# shellcheck disable=SC1091
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
skip "the fake prerequisite" "it is absent on purpose"
exit "$BLOCKED"
SUITE
cat > "$SUITES/slow.sh" <<'SUITE'
#!/data/data/com.termux/files/usr/bin/bash
# device-slow: 1
set -euo pipefail
if [ -z "${DEVICE_STATE:-}" ]; then
    echo "run through tests/device.sh" >&2
    exit 2
fi
# shellcheck disable=SC1091
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
assert_eq "the slow fake suite ran" 1 1
SUITE
cat > "$SUITES/restore.sh" <<'SUITE'
#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
if [ -z "${DEVICE_STATE:-}" ]; then
    echo "run through tests/device.sh" >&2
    exit 2
fi
# shellcheck disable=SC1091
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
temp_profile zz-test-tooling "canary"
keeper mode local >/dev/null
printf 'changed\n' > "$DEVICE_STATE/restore-changed"
assert_eq "the restore fake suite fails on purpose" 1 0
SUITE
cat > "$SUITES/hang.sh" <<'SUITE'
#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
if [ -z "${DEVICE_STATE:-}" ]; then
    echo "run through tests/device.sh" >&2
    exit 2
fi
# shellcheck disable=SC1091
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
keeper mode local >/dev/null
printf 'ready\n' > "$DEVICE_STATE/hang-ready"
exec sleep 30
SUITE
cp "$ROOT/tests/device/lib.sh" "$SUITES/lib.sh"
cp "$ROOT/tests/device/reach.sh" "$SUITES/reach.sh"

reset() {
    rm -rf "$STATE" "$DEVSTATE" "$ART"
    mkdir -p "$STATE/prefs" "$ART"
    : > "$STATE/calls.log"
    : > "$STATE/capture.log"
    : > "$STATE/connect.log"
    printf 'auto-connect\n' > "$STATE/mode"
    printf 'Home\n' > "$STATE/ssid"
    printf 'running\n' > "$STATE/service"
    : > "$STATE/online"
    : > "$STATE/listen"
    : > "$STATE/connect_works"
    printf '2026-11-23T10:00:00Z 10s info svc.start mode=auto-connect\n' \
        > "$STATE/events.log"
    cat > "$STATE/prefs/local_wifi.xml" <<'XML'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="ssid">Home</string>
    <string name="profile.1_ciphertext">cipher</string>
</map>
XML
    cat > "$STATE/ui.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8" standalone="yes" ?>
<hierarchy rotation="0"><node index="0" text="" class="android.widget.FrameLayout" bounds="[0,0][1080,2400]"><node index="0" text="Status" content-desc="Status tab" class="android.widget.Button" bounds="[0,2100][270,2200]" /><node index="1" text="Diag" content-desc="Diagnostics" class="android.widget.Button" bounds="[270,2100][540,2200]" /></node></hierarchy>
XML
}

code=0
# run_case <name> <want> [env assignments...] -- [device.sh arguments...]
run_case() {
    local name=$1 want=$2
    shift 2
    local -a envs=()
    while [ "${1:-}" != "--" ]; do
        envs+=("$1")
        shift
    done
    shift
    : > "$OUT"
    code=0
    ( cd "$WORK" && env PATH="$BIN:$PATH" FAKE_STATE="$STATE" \
        TMPDIR="$WORK/tmp" DEVICE_STATE_DIR="$DEVSTATE" \
        DEVICE_ARTIFACTS="$ART" DEVICE_SUITE_DIR="$SUITES" \
        "${envs[@]}" timeout 90 bash "$ROOT/tests/device.sh" "$@" ) \
        > "$OUT" 2>&1 || code=$?
    if [ "$code" -eq "$want" ]; then
        check "$name: exit $code" 1
    else
        check "$name: expected exit $want, got $code" 0
        sed 's/^/     | /' "$OUT" | head -n 60
    fi
}

saw() {
    local what=$1 text=$2
    if grep -qF -- "$text" "$OUT"; then
        check "$what" 1
    else
        check "$what" 0
        sed 's/^/     | /' "$OUT" | head -n 40
    fi
}

saw_not() {
    local what=$1 text=$2
    if grep -qF -- "$text" "$OUT"; then
        check "$what" 0
        sed 's/^/     | /' "$OUT" | head -n 40
    else
        check "$what" 1
    fi
}

logged() {
    local what=$1 pattern=$2
    if grep -qE -- "$pattern" "$STATE/calls.log"; then
        check "$what" 1
    else
        check "$what" 0
        sed 's/^/     | /' "$STATE/calls.log" | tail -n 20
    fi
}

# 1. --list needs no device and runs no adb command.
reset
rm -f "$STATE/online"
run_case "list without a device" 0 -- --list
saw "list names the suites" "pass"
saw "list includes slow" "slow"
if [ -s "$STATE/calls.log" ]; then
    check "list did not call adb" 0
else
    check "list did not call adb" 1
fi

# 2. No device is exit 3, not a pass, and no snapshot is written.
reset
rm -f "$STATE/online"
run_case "no device is blocked" 3 -- --only pass
saw "no device says why" "skip: no device"
saw "no device says nothing ran" "no suite ran"
if [ -f "$DEVSTATE/ready" ]; then
    check "no device left no snapshot" 0
else
    check "no device left no snapshot" 1
fi

# 3. A missing adb is the same blocked code.
reset
run_case "no adb is blocked" 3 ADB=adb-does-not-exist -- --only pass
saw "no adb names the command" "adb-does-not-exist"

# 4. The default tier passes with only adb and the fake suites.
reset
run_case "default tier passes" 0 -- --only pass
saw "ok lines are printed" "ok   the fake mode is the snapshot mode"
saw "a UI assertion passes" "ok   the Diagnostics tab is in the dump"
saw "the timing line is test-timings format" "timing: tests/device/pass.sh#main"
if grep -qE '^timing: tests/device/pass\.sh#main [0-9]+ms passed$' "$OUT"; then
    check "the timing line is passed" 1
else
    check "the timing line is passed" 0
fi
if grep -q '^shell input tap 405 2150$' "$STATE/calls.log"; then
    check "a tap used derived coordinates" 1
else
    check "a tap used derived coordinates" 0
    sed 's/^/     | /' "$STATE/calls.log" | tail -n 10
fi
if [ -d "$DEVSTATE" ]; then
    check "a clean run removed its snapshot" 0
else
    check "a clean run removed its snapshot" 1
fi

# 5. --quiet keeps failures and the summary, not the ok lines.
reset
run_case "quiet run passes" 0 -- --only pass --quiet
saw_not "quiet hides ok lines" "ok   "
saw "quiet still prints the summary" "PASS 1 suite(s)"

# 6. --serial reaches every adb invocation.
reset
run_case "serial run passes" 0 -- --serial 127.0.0.1:9999 --only pass
if grep -qE '^-s 127\.0\.0\.1:9999 get-state$' "$STATE/calls.log" \
    && grep -qE '^-s 127\.0\.0\.1:9999 shell' "$STATE/calls.log"; then
    check "every adb call carries --serial" 1
else
    check "every adb call carries --serial" 0
    sed 's/^/     | /' "$STATE/calls.log" | head -n 20
fi

# 7. --record without adb-harness says so and still passes.
reset
run_case "record without a harness passes" 0 CAPTURE=capture-not-installed -- --only pass --record
saw "the missing harness is named" "recording skipped: adb-harness not found"
saw "the checks still pass" "PASS pass"

# 8. --record with a harness emits start, one event pair per check, and stop.
reset
run_case "record with a fake harness passes" 0 -- --only pass --record
saw "the harness version is printed" "capture 9.9-fake"
if grep -q '^start --dir' "$STATE/capture.log" \
    && grep -q '^event check:pass:start' "$STATE/capture.log" \
    && grep -q '^event check:pass:end' "$STATE/capture.log" \
    && grep -q '^stop --dir' "$STATE/capture.log"; then
    check "start, check event and stop were called" 1
else
    check "start, check event and stop were called" 0
    sed 's/^/     | /' "$STATE/capture.log"
fi

# 8b. A harness that fails to start is also only a printed reason.
reset
run_case "a harness that cannot start is skipped" 0 FAKE_CAPTURE_FAIL_START=1 -- --only pass --record
saw "the failed start is explained" "recording skipped: capture start failed"
saw "the checks still pass without recording" "PASS pass"

# 9. A failing check leaves a dump and a screenshot, and with --record the
#    frame for that check is pulled after the run.
reset
run_case "a failing check fails the run" 1 -- --only fail --record
saw "the failure prints the command" "command:"
saw "the failure prints what was expected" "expected:"
saw "the failure prints what came back" "actual:"
saw "the failure prints the journal tail" "journal (last 20):"
saw "the failure names its artifacts" "artifacts:"
if ls "$ART"/*.ui.xml >/dev/null 2>&1 && ls "$ART"/*.png >/dev/null 2>&1; then
    check "a failing check saved its dump and screenshot" 1
else
    check "a failing check saved its dump and screenshot" 0
    find "$ART" -maxdepth 1 -type f -print | sed 's/^/     | /'
fi
if grep -q '^frames --event check:fail:failed' "$STATE/capture.log"; then
    check "the failure's frame was requested" 1
else
    check "the failure's frame was requested" 0
    sed 's/^/     | /' "$STATE/capture.log"
fi
if [ -d "$DEVSTATE" ]; then
    check "a failed run restored and removed its snapshot" 0
else
    check "a failed run restored and removed its snapshot" 1
fi

# 10. A suite that skips as a whole is 3, never 0, and its timing line is not
#     `skipped`.
reset
run_case "a blocked suite is exit 3" 3 -- --only skip
saw "the suite is reported as skipped" "SKIP skip"
saw "the reason is printed" "it is absent on purpose"
if grep -qE '^timing: tests/device/skip\.sh#main [0-9]+ms failed$' "$OUT"; then
    check "a blocked suite is timed as failed" 1
else
    check "a blocked suite is timed as failed" 0
fi
saw_not "a blocked suite is never a pass" "PASS 0 suite(s)"

# 10b. --only takes a comma list, like the aat e2e CLI.
reset
run_case "--only takes a comma list" 3 -- --only pass,skip
saw "the first named suite ran" "PASS pass"
saw "the second named suite was blocked" "SKIP skip"

# 11. An unknown --only name is a usage error.
reset
run_case "an unknown --only name is usage" 2 -- --only not-a-suite
saw "the unknown name is explained" "no suite named"

# 11b. Tier 3 is deferred: there is no --ui and no a11y probe to fall back to.
reset
run_case "tier 3 is not accepted yet" 2 -- --ui semantic --only pass
saw "the deferred UI flag is unknown" "unknown option: --ui"

# 11c. Without --slow, a slow suite is named and not timed; --only still runs
#      it explicitly, as the old flag did.
mkdir -p "$WORK/slowsuites"
cp "$SUITES/pass.sh" "$SUITES/slow.sh" "$SUITES/lib.sh" "$WORK/slowsuites/"
reset
run_case "a slow suite is excluded by default" 0 DEVICE_SUITE_DIR="$WORK/slowsuites" --
saw "the excluded slow suite is named" "skip slow (slow: pass --slow to include it)"
if grep -q '^timing: tests/device/slow\.sh' "$OUT"; then
    check "an excluded slow suite prints no timing line" 0
else
    check "an excluded slow suite prints no timing line" 1
fi

reset
run_case "an explicit slow suite runs" 0 -- --only slow
saw "the slow suite passed" "PASS slow"

# 13. Auto-heal is off unless --connect: no attempt, exit 3.
reset
rm -f "$STATE/online"
run_case "auto-heal is opt-in" 3 -- --only pass
saw "setup says auto-heal is off" "auto-heal is off"
if [ -s "$STATE/connect.log" ]; then
    check "no auto-heal ran without --connect" 0
else
    check "no auto-heal ran without --connect" 1
fi

# 14. --connect logs every attempt and heals an empty device list.
reset
rm -f "$STATE/online"
run_case "auto-heal with --connect" 0 -- --connect --only reach
saw "the attempt is timestamped" "reconnect: attempt 1/3 at"
saw "the installed reconnect tool's version is printed" "adb-connect 9.9-fake"
saw "the reach check passed" "PASS reach"
if [ -s "$STATE/connect.log" ]; then
    check "adb-connect was called" 1
else
    check "adb-connect was called" 0
    sed 's/^/     | /' "$STATE/connect.log"
fi

# 15. A reconnect that exceeded the reachability bound still fails, with the
#     attempts printed.
reset
rm -f "$STATE/online"
run_case "a slow reconnect fails the bound" 1 FAKE_CONNECT_DELAY=0.1 \
    DEVICE_REACH_BOUND_MS=1 -- --connect --only reach
saw "the reach failure names the bound" "setup reconnect stayed within 1ms"
saw "the reach failure prints the attempts" "reconnect: attempt 1/3"

# 16. The attempt count is bounded.
reset
rm -f "$STATE/online"
run_case "auto-heal is bounded" 3 ADB_CONNECT=/nonexistent \
    DEVICE_RECONNECT_MAX=2 DEVICE_RECONNECT_DELAY=0 -- --connect --only pass
saw "the first attempt is printed" "attempt 1/2"
saw "the second attempt is printed" "attempt 2/2"
saw_not "there is no third attempt" "attempt 3/2"

# 17. A suite that changes mode and saves a temp profile, then fails, is
#     restored: prefs back, mode back, temp profile gone. The order proves the
#     suggestion is removed before the prefs are written back.
reset
cp "$STATE/prefs/local_wifi.xml" "$WORK/original.xml"
run_case "a failed changing suite restores" 1 -- --only restore
if cmp -s "$WORK/original.xml" "$STATE/prefs/local_wifi.xml"; then
    check "the original prefs are back byte for byte" 1
else
    check "the original prefs are back byte for byte" 0
    diff -u "$WORK/original.xml" "$STATE/prefs/local_wifi.xml" 2>&1 | sed 's/^/     | /'
fi
if [ "$(cat "$STATE/mode")" = auto-connect ]; then
    check "the mode is back" 1
else
    check "the mode is back" 0
    cat "$STATE/mode" | sed 's/^/     | /'
fi
if [ -f "$DEVSTATE/temp-active" ] || [ -f "$DEVSTATE/ready" ]; then
    check "the temp profile marker and snapshot are gone" 0
    find "$DEVSTATE" -maxdepth 2 -print 2>&1 | sed 's/^/     | /'
else
    check "the temp profile marker and snapshot are gone" 1
fi
local_line=$(grep -n 'set_mode --arg local' "$STATE/calls.log" | head -n 1 | cut -d: -f1)
stop_line=$(grep -n 'am force-stop' "$STATE/calls.log" | head -n 1 | cut -d: -f1)
auto_line=$(grep -n 'set_mode --arg auto' "$STATE/calls.log" | head -n 1 | cut -d: -f1)
if [ -n "$local_line" ] && [ -n "$stop_line" ] && [ -n "$auto_line" ] \
    && [ "$local_line" -lt "$stop_line" ] && [ "$stop_line" -lt "$auto_line" ]; then
    check "local mode, force-stop, then the saved mode" 1
else
    check "local mode, force-stop, then the saved mode" 0
    grep -nE 'set_mode|force-stop|start-foreground' "$STATE/calls.log" | sed 's/^/     | /'
fi

# 18. An interrupt reaches the trap: the changed mode is put back and the
#     snapshot is removed, even though the suite never returned. SIGTERM is
#     used because a non-interactive shell starts background jobs with SIGINT
#     ignored; INT and TERM share tests/device.sh's interrupted() path.
reset
( cd "$WORK" && exec env PATH="$BIN:$PATH" FAKE_STATE="$STATE" \
    TMPDIR="$WORK/tmp" DEVICE_STATE_DIR="$DEVSTATE" \
    DEVICE_ARTIFACTS="$ART" DEVICE_SUITE_DIR="$SUITES" \
    bash "$ROOT/tests/device.sh" --only hang ) \
    > "$OUT" 2>&1 &
runner=$!
i=0
while [ ! -f "$DEVSTATE/hang-ready" ] && [ "$i" -lt 100 ]; do
    sleep 0.05
    i=$((i + 1))
done
kill -TERM "$runner" 2>/dev/null || true
wait "$runner"
code=$?
if [ "$code" -eq 143 ]; then
    check "an interrupt exits 143" 1
else
    check "an interrupt exits 143, got $code" 0
    sed 's/^/     | /' "$OUT" | tail -n 30
fi
if [ "$(cat "$STATE/mode")" = auto-connect ]; then
    check "an interrupt put the mode back" 1
else
    check "an interrupt put the mode back" 0
    cat "$STATE/mode" | sed 's/^/     | /'
fi
if [ -d "$DEVSTATE" ]; then
    check "an interrupt removed the snapshot after restoring" 0
else
    check "an interrupt removed the snapshot after restoring" 1
fi

# 19. --restore repairs a dead run's snapshot: the temp profile and the mode
#     are put back without running a suite.
reset
mkdir -p "$DEVSTATE/prefs"
printf 'ready\n' > "$DEVSTATE/ready"
printf 'auto-connect\n' > "$DEVSTATE/mode"
printf 'Home\n' > "$DEVSTATE/ssid"
printf 'present\n' > "$DEVSTATE/passphrase"
printf 'running\n' > "$DEVSTATE/service"
printf 'zz-test-dead\n' > "$DEVSTATE/temp-ssids"
printf 'zz-test-dead\n' > "$DEVSTATE/temp-active"
for perm in \
    android.permission.ACCESS_COARSE_LOCATION \
    android.permission.ACCESS_FINE_LOCATION \
    android.permission.POST_NOTIFICATIONS \
    android.permission.WRITE_SECURE_SETTINGS \
    com.termux.permission.RUN_COMMAND; do
    printf '%s\tgranted\n' "$perm" >> "$DEVSTATE/perms"
done
cp "$STATE/prefs/local_wifi.xml" "$DEVSTATE/prefs/local_wifi.xml"
printf 'present\n' > "$DEVSTATE/prefs/local_wifi.xml.present"
: > "$DEVSTATE/prefs/local_wifi.xml.bak"
printf 'absent\n' > "$DEVSTATE/prefs/local_wifi.xml.bak.present"
: > "$DEVSTATE/prefs/wifi_suggestion.xml"
printf 'absent\n' > "$DEVSTATE/prefs/wifi_suggestion.xml.present"
: > "$DEVSTATE/prefs/wifi_suggestion.xml.bak"
printf 'absent\n' > "$DEVSTATE/prefs/wifi_suggestion.xml.bak.present"
printf 'local-only\n' > "$STATE/mode"
printf '<map><string name="ssid">zz-test-dead</string></map>\n' \
    > "$STATE/prefs/local_wifi.xml"
run_case "--restore repairs a dead run" 0 -- --restore
saw "the restore names what it did" "restore:"
if [ "$(cat "$STATE/mode")" = auto-connect ]; then
    check "--restore put the mode back" 1
else
    check "--restore put the mode back" 0
fi
if cmp -s "$WORK/original.xml" "$STATE/prefs/local_wifi.xml"; then
    check "--restore put the prefs back" 1
else
    check "--restore put the prefs back" 0
    diff -u "$WORK/original.xml" "$STATE/prefs/local_wifi.xml" 2>&1 | sed 's/^/     | /'
fi
if [ -d "$DEVSTATE" ]; then
    check "--restore removed its snapshot" 0
else
    check "--restore removed its snapshot" 1
fi

# 20. --restore with nothing saved is a successful no-op.
reset
run_case "--restore with nothing saved" 0 -- --restore
saw "the no-op says so" "nothing to restore"

echo "$failures failure(s)" >&2
[ "$failures" -eq 0 ]
