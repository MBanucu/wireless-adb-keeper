#!/data/data/com.termux/files/usr/bin/bash
# Shared helpers for tests/device/*.sh. Sourced once by ../device.sh; a suite
# run directly gets a clear message from its own guard instead of a half-run.
#
# Everything here talks to the installed app through adb. The journal and the
# SharedPreferences are read with `run-as`, so the suite needs the debuggable
# Gradle build, not the build.sh APK (whose manifest is not debuggable).
#
# The state helpers are the reason this is one file: every suite runs with a
# snapshot taken by the runner, and the runner restores it from an EXIT/INT
# trap. A test that changes mode, saves a temp profile or revokes a permission
# therefore cannot leave the phone in a different place, even when it fails.
#
#   bash tests/device.sh --only diag
#
# shellcheck shell=bash

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
    echo "tests/device/lib.sh is sourced by tests/device.sh, not run" >&2
    exit 2
fi
if [ "${DEVICE_LIB_SOURCED:-0}" = 1 ]; then
    return 0
fi
DEVICE_LIB_SOURCED=1

PKG=${DEVICE_PACKAGE:-io.github.mbanucu.wirelessadbkeeper}
AUTH="content://$PKG.control"
ADB=${ADB:-adb}
PHONE_SERIAL=${PHONE_SERIAL:-}
DEVICE_DIR=$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
DEVICE_ROOT=${DEVICE_ROOT:-$(CDPATH='' cd -- "$DEVICE_DIR/../.." && pwd)}
export DEVICE_ROOT
: "${DEVICE_STATE:?DEVICE_STATE must name the runner snapshot directory}"
DEVICE_PID=${DEVICE_PID:-$$}
DEVICE_PORT=${DEVICE_PORT:-5555}

LAST_COMMAND=
SKIPPED=${SKIPPED:-0}

# Command substitutions run in a subshell, so an in-memory LAST_COMMAND set by
# `status=$(keeper status)` would not reach the assertion. Keep the last
# device command in the runner's state directory as well.
_last() {
    LAST_COMMAND=$1
    printf '%s\n' "$1" > "$DEVICE_STATE/last-command" 2>/dev/null || true
}

# ---------------------------------------------------------------- adb plumbing

dev_adb() {
    if [ -n "$PHONE_SERIAL" ]; then
        "$ADB" -s "$PHONE_SERIAL" "$@"
    else
        "$ADB" "$@"
    fi
}

# The wrapper is the documented interface; tests never call `content` directly
# except to check that the wrapper and the raw provider agree.
keeper() {
    _last "keeper.sh $*"
    env ADB="$ADB" PHONE_SERIAL="$PHONE_SERIAL" \
        bash "$DEVICE_ROOT/keeper.sh" "$@"
}

# Raw provider reply, still inside the Bundle envelope. Used by errors.sh and
# diag.sh; `keeper` is what tests normally use.
content_call() {
    _last "content call --uri $AUTH $*"
    dev_adb shell content call --uri "$AUTH" "$@"
}

# The same envelope stripping keeper.sh does, so a direct call is comparable.
strip_envelope() {
    tr -d '\r' \
        | sed -e 's/^Result: Bundle\[{//' -e 's/}\]$//' -e 's/^code=[0-9]*, data=//'
}

reply_code() {
    printf '%s\n' "$1" | tr -d '\r' \
        | sed -n 's/.*code=\([0-9][0-9]*\).*/\1/p' | head -n 1
}

# One key=value line's value. Reads stdin so both `keeper status` and a saved
# reply can be inspected without another device round trip.
field() {
    local key=$1
    tr -d '\r' | sed -n "s/^${key}=//p" | head -n 1
}

# ------------------------------------------------------------ device readers

# The journal lives in device-protected storage so it exists before unlock.
# `run-as` is the only reader; a missing file is an empty journal, not an error.
journal() {
    _last "run-as $PKG cat /data/user_de/0/$PKG/files/diagnostics/events.log"
    dev_adb shell "run-as $PKG cat /data/user_de/0/$PKG/files/diagnostics/events.log" \
        2>/dev/null || true
}

# SharedPreferences, still XML. With no argument, every file is concatenated so
# a canary grep cannot miss a second file. With an argument, one file.
prefs() {
    if [ "$#" -eq 0 ]; then
        _last "run-as $PKG sh -c 'cat shared_prefs/*.xml'"
        dev_adb shell "run-as $PKG sh -c 'cat shared_prefs/*.xml 2>/dev/null'" \
            2>/dev/null || true
    else
        _last "run-as $PKG cat shared_prefs/$1"
        dev_adb shell "run-as $PKG cat shared_prefs/$1" 2>/dev/null || true
    fi
}

prefs_exists() {
    dev_adb shell "run-as $PKG test -f shared_prefs/$1" >/dev/null 2>&1
}

# A permission is granted/denied/unknown from dumpsys. `pm check-permission`
# changed output across releases, while the granted= line is stable and also
# reports install-time grants such as WRITE_SECURE_SETTINGS.
permission_state() {
    local dump=$1 perm=$2 line
    line=$(printf '%s\n' "$dump" | grep -F -- "$perm" | grep -F 'granted=' | head -n 1)
    case $line in
        *'granted=true'*) printf 'granted\n' ;;
        *'granted=false'*) printf 'denied\n' ;;
        *) printf 'unknown\n' ;;
    esac
}

pm_grant() {
    _last "pm grant $PKG $1"
    dev_adb shell pm grant "$PKG" "$1"
}

pm_revoke() {
    _last "pm revoke $PKG $1"
    dev_adb shell pm revoke "$PKG" "$1"
}

force_stop_app() {
    _last "am force-stop $PKG"
    dev_adb shell am force-stop "$PKG"
}

# 5555 in /proc/net/tcp is 15B3, state 0A. When the file is unreadable or has
# no entry at all, fall back to a toybox nc connect. This is deliberately
# independent of PinnedPort.state(), which is the value under test.
port_listening() {
    local net
    net=$(dev_adb shell 'cat /proc/net/tcp /proc/net/tcp6 2>/dev/null' 2>/dev/null || true)
    if [ -n "$net" ] && printf '%s\n' "$net" | grep -q ':15B3 '; then
        printf '%s\n' "$net" \
            | awk '$2 ~ /:15B3$/ && $4 == "0A" { found = 1 } END { exit(found ? 0 : 1) }'
        return
    fi
    dev_adb shell "timeout 2 nc 127.0.0.1 $DEVICE_PORT </dev/null" >/dev/null 2>&1
}

wait_for_service() {
    local timeout=$1 deadline
    deadline=$(( $(date +%s) + timeout ))
    while :; do
        if [ "$(keeper status 2>/dev/null | field service)" = running ]; then
            return 0
        fi
        if [ "$(date +%s)" -ge "$deadline" ]; then
            return 1
        fi
        sleep 1
    done
}

wait_for_port() {
    local timeout=$1 deadline
    deadline=$(( $(date +%s) + timeout ))
    while :; do
        if port_listening; then
            return 0
        fi
        if [ "$(date +%s)" -ge "$deadline" ]; then
            return 1
        fi
        sleep 1
    done
}

# --------------------------------------------------------------- assertions

# A failure must be reproducible from the line above it: the command that
# produced the value, what was expected, what came back, and the journal tail.
# `exit` (not `return`) fails the suite's subshell, so the runner's state trap
# still owns the parent.
_assert_fail() {
    local what=$1 expected=$2 actual=$3 tail command
    command=$(cat "$DEVICE_STATE/last-command" 2>/dev/null || true)
    printf 'FAIL %s\n' "$what"
    printf '  command: %s\n' "${command:-${LAST_COMMAND:-$what}}"
    printf '  expected:\n'
    printf '%s\n' "$expected" | sed 's/^/    /'
    printf '  actual:\n'
    printf '%s\n' "$actual" | sed 's/^/    /'
    tail=$(journal | tail -n 20 || true)
    printf '  journal (last 20):\n'
    if [ -n "$tail" ]; then
        printf '%s\n' "$tail" | sed 's/^/    /'
    else
        printf '    (empty or unavailable)\n'
    fi
    exit 1
}

assert_eq() {
    local what=$1 expected=$2 actual=$3
    if [ "$expected" = "$actual" ]; then
        printf 'ok   %s\n' "$what"
    else
        _assert_fail "$what" "$expected" "$actual"
    fi
}

assert_contains() {
    local what=$1 needle=$2 haystack=$3
    case $haystack in
        *"$needle"*) printf 'ok   %s\n' "$what" ;;
        *) _assert_fail "$what" "contains <$needle>" "$haystack" ;;
    esac
}

assert_not_contains() {
    local what=$1 needle=$2 haystack=$3
    case $haystack in
        *"$needle"*) _assert_fail "$what" "does not contain <$needle>" "$haystack" ;;
        *) printf 'ok   %s\n' "$what" ;;
    esac
}

# Counts fixed-string occurrences, not matching lines: a canary that appears
# twice in one XML line must count as two.
assert_count() {
    local what=$1 expected=$2 needle=$3 haystack=$4 count
    if [ -z "$needle" ]; then
        _assert_fail "$what" "$expected" "cannot count an empty needle"
    fi
    count=$({ printf '%s' "$haystack" | grep -o -F -- "$needle" || true; } | wc -l)
    count=$((count))
    if [ "$count" -eq "$expected" ]; then
        printf 'ok   %s\n' "$what"
    else
        _assert_fail "$what" "$expected" "$count"
    fi
}

# A named reason is a skip, not a pass: the suite's output says so and the
# runner still reserves exit 0 for checks that actually ran.
skip() {
    printf 'skip %s: %s\n' "$1" "$2"
    SKIPPED=$((SKIPPED + 1))
}

timing() {
    local name=$1 ms=$2 status=$3
    printf 'timing: tests/device/%s.sh#main %sms %s\n' "$name" "$ms" "$status"
}

now_ms() {
    local ns
    ns=$(date +%s%N 2>/dev/null || true)
    case $ns in
        ''|*[!0-9]*) printf '%s000\n' "$(date +%s)" ;;
        *) printf '%s\n' "$((ns / 1000000))" ;;
    esac
}

# --------------------------------------------------------------- temp profiles

# The only SSID shape a test may save. The PID is the runner's, not the
# subshell's, and the counter is a file so two suites cannot collide.
temp_ssid() {
    local file=$DEVICE_STATE/temp-counter n
    n=$(cat "$file" 2>/dev/null || true)
    n=$(( ${n:-0} + 1 ))
    printf '%s\n' "$n" > "$file"
    printf 'zz-test-%s-%s\n' "$DEVICE_PID" "$n"
}

_temp_remember() {
    local ssid=$1 list=$DEVICE_STATE/temp-ssids
    grep -q -x -F -- "$ssid" "$list" 2>/dev/null \
        || printf '%s\n' "$ssid" >> "$list"
}

# Saves a temp network through the app. It marks the profile active before the
# call, so a failed or interrupted save is still removed by the trap.
temp_profile() {
    local ssid=$1 pass=${2-}
    : > "$DEVICE_STATE/temp-active"
    _temp_remember "$ssid"
    if [ "$#" -ge 2 ]; then
        keeper save "$ssid" "$pass"
    else
        keeper save "$ssid"
    fi
}

# In auto mode the running monitor registers the saved SSID as a suggestion.
# It must be removed while the app still holds the temp applied record;
# writing the old prefs back first would make add() believe it was already
# the current suggestion. Local mode is the app's own remove path.
_remove_temp_suggestion() {
    [ -f "$DEVICE_STATE/temp-active" ] || return 0
    if [ "$(keeper status 2>/dev/null | field service)" != running ]; then
        keeper start >/dev/null 2>&1 || true
        wait_for_service 15 || true
    fi
    keeper mode local >/dev/null 2>&1 || true
    sleep 1
}

_restore_pref_file() {
    local file=$1 src="$DEVICE_STATE/prefs/$1" presence
    presence=$(cat "$src.present" 2>/dev/null || true)
    if [ "$presence" = present ]; then
        dev_adb shell "run-as $PKG sh -c 'cat > shared_prefs/$file && chmod 600 shared_prefs/$file'" \
            < "$src"
    else
        dev_adb shell "run-as $PKG rm -f shared_prefs/$file" >/dev/null 2>&1
    fi
}

# Stops the app before writing its prefs: a running process keeps the old
# values in memory and would write them back over the restored file.
drop_temp_profile() {
    local file
    [ -f "$DEVICE_STATE/temp-active" ] || return 0
    _remove_temp_suggestion || true
    force_stop_app >/dev/null 2>&1 || return 1
    sleep 1
    for file in local_wifi.xml local_wifi.xml.bak wifi_suggestion.xml wifi_suggestion.xml.bak; do
        _restore_pref_file "$file" || return 1
    done
    rm -f "$DEVICE_STATE/temp-active"
}

# ------------------------------------------------------------ state contract

passphrase_present() {
    local xml
    if ! prefs_exists local_wifi.xml; then
        printf 'absent\n'
        return
    fi
    xml=$(prefs local_wifi.xml)
    # Profiles keep their ciphertext under profile.<id>_ciphertext; older
    # builds used the two passphrase* keys directly.
    if printf '%s' "$xml" | grep -q -E \
        'name="(passphrase|passphrase_ciphertext|profile\.[^"]+_ciphertext)"'; then
        printf 'present\n'
    else
        printf 'absent\n'
    fi
}

# Called once by the runner before any suite. It records more than the minimum
# the issue asks for - the service and a byte copy of the two preference files
# are what make a canary passphrase restorable without knowing its value.
state_snapshot() {
    local out mode ssid service pass dump perm state file
    mkdir -p "$DEVICE_STATE/prefs" || return 1
    out=$(keeper status 2>&1) || {
        printf 'cannot read status: %s\n' "$out" >&2
        return 1
    }
    mode=$(printf '%s\n' "$out" | field mode)
    ssid=$(printf '%s\n' "$out" | field ssid)
    service=$(printf '%s\n' "$out" | field service)
    if [ -z "$mode" ] || [ -z "$ssid" ] || [ -z "$service" ]; then
        printf 'status is missing mode/ssid/service:\n%s\n' "$out" >&2
        return 1
    fi
    case $ssid in
        zz-test-*)
            printf 'a previous run left the temp SSID <%s> saved; remove it first\n' \
                "$ssid" >&2
            return 1
            ;;
    esac
    if prefs | grep -q -F 'zz-test-'; then
        printf 'a previous run left a temp profile in shared_prefs; remove it first\n' >&2
        return 1
    fi
    pass=$(passphrase_present)
    printf '%s\n' "$mode"    > "$DEVICE_STATE/mode"
    printf '%s\n' "$ssid"    > "$DEVICE_STATE/ssid"
    printf '%s\n' "$pass"    > "$DEVICE_STATE/passphrase"
    printf '%s\n' "$service" > "$DEVICE_STATE/service"
    printf '%s\n' "$out"     > "$DEVICE_STATE/status.initial"

    dump=$(dev_adb shell dumpsys package "$PKG" 2>/dev/null | tr -d '\r' || true)
    if ! printf '%s\n' "$dump" | grep -q 'granted='; then
        printf 'cannot read permission state from dumpsys package %s\n' "$PKG" >&2
        return 1
    fi
    : > "$DEVICE_STATE/perms"
    for perm in \
        android.permission.ACCESS_COARSE_LOCATION \
        android.permission.ACCESS_FINE_LOCATION \
        android.permission.POST_NOTIFICATIONS \
        android.permission.WRITE_SECURE_SETTINGS \
        com.termux.permission.RUN_COMMAND \
        ; do
        state=$(permission_state "$dump" "$perm")
        printf '%s\t%s\n' "$perm" "$state" >> "$DEVICE_STATE/perms"
    done

    for file in local_wifi.xml local_wifi.xml.bak wifi_suggestion.xml wifi_suggestion.xml.bak; do
        if prefs_exists "$file"; then
            dev_adb shell "run-as $PKG cat shared_prefs/$file" \
                > "$DEVICE_STATE/prefs/$file" || return 1
            printf 'present\n' > "$DEVICE_STATE/prefs/$file.present"
        else
            : > "$DEVICE_STATE/prefs/$file"
            printf 'absent\n' > "$DEVICE_STATE/prefs/$file.present"
        fi
    done
    printf 'ready\n' > "$DEVICE_STATE/ready"
}

_restore_permissions() {
    local rc=0 perm orig current out dump
    dump=$(dev_adb shell dumpsys package "$PKG" 2>/dev/null | tr -d '\r' || true)
    if [ -z "$dump" ]; then
        printf 'restore: cannot read permission state\n' >&2
        return 1
    fi
    while IFS=$'\t' read -r perm orig; do
        [ -n "$perm" ] || continue
        [ "$orig" = unknown ] && continue
        current=$(permission_state "$dump" "$perm")
        [ "$current" = "$orig" ] && continue
        case $orig in
            granted)
                out=$(pm_grant "$perm" 2>&1) || {
                    printf 'restore: could not grant %s: %s\n' "$perm" "$out" >&2
                    rc=1
                } ;;
            denied)
                out=$(pm_revoke "$perm" 2>&1) || {
                    printf 'restore: could not revoke %s: %s\n' "$perm" "$out" >&2
                    rc=1
                } ;;
        esac
    done < "$DEVICE_STATE/perms"
    return "$rc"
}

_verify_permissions() {
    local rc=0 perm orig current dump
    dump=$(dev_adb shell dumpsys package "$PKG" 2>/dev/null | tr -d '\r' || true)
    while IFS=$'\t' read -r perm orig; do
        [ -n "$perm" ] || continue
        [ "$orig" = unknown ] && continue
        current=$(permission_state "$dump" "$perm")
        if [ "$current" != "$orig" ]; then
            printf 'restore: %s is %s, expected %s\n' "$perm" "$current" "$orig" >&2
            rc=1
        fi
    done < "$DEVICE_STATE/perms"
    return "$rc"
}

_verify_temp_profiles() {
    local rc=0 ssid wifi_dump
    [ -s "$DEVICE_STATE/temp-ssids" ] || return 0
    while IFS= read -r ssid; do
        [ -n "$ssid" ] || continue
        if printf '%s' "$(prefs local_wifi.xml)" | grep -q -F -- "$ssid"; then
            printf 'restore: %s is still in local_wifi.xml\n' "$ssid" >&2
            rc=1
        fi
    done < "$DEVICE_STATE/temp-ssids"
    # A saved auto-mode profile becomes a platform suggestion. Prefs alone
    # cannot prove it is gone, so look for the test-only SSID in dumpsys too.
    wifi_dump=$(dev_adb shell dumpsys wifi 2>/dev/null || true)
    if [ -n "$wifi_dump" ]; then
        while IFS= read -r ssid; do
            [ -n "$ssid" ] || continue
            if printf '%s\n' "$wifi_dump" | grep -q -F -- "$ssid"; then
                printf 'restore: %s is still known to the Wi-Fi service\n' "$ssid" >&2
                rc=1
            fi
        done < "$DEVICE_STATE/temp-ssids"
    fi
    return "$rc"
}

# Runs from the runner's EXIT trap and from an interrupted run. Each step is
# attempted even if an earlier one failed, so one refusal cannot strand the
# rest of the state. Safe to call twice.
state_restore() {
    [ -f "$DEVICE_STATE/ready" ] || return 0
    trap - EXIT INT TERM
    local rc=0 mode ssid pass service final final_service deadline
    local -r want_mode=$(cat "$DEVICE_STATE/mode")
    local -r want_ssid=$(cat "$DEVICE_STATE/ssid")
    local -r want_pass=$(cat "$DEVICE_STATE/passphrase")
    local -r want_service=$(cat "$DEVICE_STATE/service")

    if [ -f "$DEVICE_STATE/temp-active" ]; then
        printf 'restore: deleting the temp profile and restoring the saved network\n' >&2
        drop_temp_profile || {
            printf 'restore: could not delete the temp profile\n' >&2
            rc=1
        }
    fi

    _restore_permissions || rc=1

    service=$(keeper status 2>/dev/null | field service || true)
    if [ "$want_service" = running ] && [ "$service" != running ]; then
        keeper start >/dev/null 2>&1 || rc=1
        wait_for_service 20 || {
            printf 'restore: the monitor service did not come back\n' >&2
            rc=1
        }
    elif [ "$want_service" = stopped ] && [ "$service" = running ]; then
        keeper stop >/dev/null 2>&1 || rc=1
        deadline=$(( $(date +%s) + 10 ))
        while [ "$(keeper status 2>/dev/null | field service)" = running ]; do
            if [ "$(date +%s)" -ge "$deadline" ]; then
                printf 'restore: the monitor service did not stop\n' >&2
                rc=1
                break
            fi
            sleep 1
        done
    fi

    mode=$(keeper status 2>/dev/null | field mode || true)
    if [ "$mode" != "$want_mode" ]; then
        case $want_mode in
            auto-connect) keeper mode auto >/dev/null 2>&1 || rc=1 ;;
            local-only)   keeper mode local >/dev/null 2>&1 || rc=1 ;;
            *)
                printf 'restore: unknown saved mode <%s>\n' "$want_mode" >&2
                rc=1 ;;
        esac
    fi

    final=$(keeper status 2>/dev/null || true)
    mode=$(printf '%s\n' "$final" | field mode)
    ssid=$(printf '%s\n' "$final" | field ssid)
    final_service=$(printf '%s\n' "$final" | field service)
    pass=$(passphrase_present)
    if [ "$mode" != "$want_mode" ]; then
        printf 'restore: mode is %s, expected %s\n' "$mode" "$want_mode" >&2
        rc=1
    fi
    if [ "$final_service" != "$want_service" ]; then
        printf 'restore: service is %s, expected %s\n' "$final_service" "$want_service" >&2
        rc=1
    fi
    if [ "$ssid" != "$want_ssid" ]; then
        printf 'restore: ssid is %s, expected %s\n' "$ssid" "$want_ssid" >&2
        rc=1
    fi
    if [ "$pass" != "$want_pass" ]; then
        printf 'restore: passphrase is %s, expected %s\n' "$pass" "$want_pass" >&2
        rc=1
    fi
    _verify_permissions || rc=1
    _verify_temp_profiles || rc=1
    if [ "$rc" -eq 0 ]; then
        printf 'restore: mode=%s ssid=%s passphrase=%s permissions unchanged\n' \
            "$mode" "$ssid" "$pass" >&2
    fi
    return "$rc"
}
