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
# A quiet run still prints failures and the summary; only the per-check `ok`
# lines and the named skips are silent.
DEVICE_QUIET=${DEVICE_QUIET:-0}
# Per-run evidence: a uiautomator dump and a screenshot of every failed check.
DEVICE_ARTIFACTS=${DEVICE_ARTIFACTS:-}
DEVICE_UI_DUMP=${DEVICE_UI_DUMP:-/sdcard/wak-ui.xml}
# Setup may heal an empty `adb devices` only when asked, and every attempt is
# logged. Off by default: a reachability check must not be healed silently.
DEVICE_AUTOHEAL=${DEVICE_AUTOHEAL:-0}
DEVICE_RECONNECT_MAX=${DEVICE_RECONNECT_MAX:-3}
DEVICE_RECONNECT_DELAY=${DEVICE_RECONNECT_DELAY:-1}
DEVICE_RECONNECT_LOG=${DEVICE_RECONNECT_LOG:-}
DEVICE_SETUP_RECONNECT_MS=${DEVICE_SETUP_RECONNECT_MS:-0}
DEVICE_RECONNECT_ATTEMPTS=${DEVICE_RECONNECT_ATTEMPTS:-0}
BLOCKED=3

LAST_COMMAND=
SKIPPED=${SKIPPED:-0}

# Command substitutions run in a subshell, so an in-memory LAST_COMMAND set by
# `status=$(keeper status)` would not reach the assertion. Keep the last
# device command in the runner's state directory as well.
_last() {
    LAST_COMMAND=$1
    printf '%s\n' "$1" 2>/dev/null > "$DEVICE_STATE/last-command" || true
}

# ---------------------------------------------------------------- adb plumbing

dev_adb() {
    if [ -n "$PHONE_SERIAL" ]; then
        "$ADB" -s "$PHONE_SERIAL" "$@"
    else
        "$ADB" "$@"
    fi
}

# ------------------------------------------------------------ reconnect policy

# One place answers "is adb attached": every setup and reachability check uses
# it, so an empty `adb devices` cannot be mistaken for a working target.
adb_online() {
    local state
    if [ -n "$PHONE_SERIAL" ]; then
        _last "adb -s $PHONE_SERIAL get-state"
    else
        _last "adb get-state"
    fi
    state=$(dev_adb get-state 2>/dev/null || true)
    [ "$(printf '%s\n' "$state" | tr -d '\r' | tail -n 1)" = device ]
}

# The log is on disk because setup runs before a suite subshell exists and a
# suite has to be able to print the attempts setup made. A path under the
# artifact directory survives the run; a bare /tmp one is a last resort.
_reconnect_log() {
    local line=$1 file=${DEVICE_RECONNECT_LOG:-${TMPDIR:-/tmp}/wak-reconnect.$$.log}
    if [ -n "$DEVICE_RECONNECT_LOG" ]; then
        mkdir -p "$(dirname -- "$DEVICE_RECONNECT_LOG")" 2>/dev/null || true
    fi
    printf '%s\n' "$line" >> "$file" 2>/dev/null || true
    printf 'reconnect: %s\n' "$line"
}

reconnect_report() {
    local file=${DEVICE_RECONNECT_LOG:-${TMPDIR:-/tmp}/wak-reconnect.$$.log}
    [ -f "$file" ] || { printf 'reconnect: no attempts logged\n'; return 0; }
    sed 's/^/reconnect: /' "$file"
}

# One attempt, named on stdout before it runs. `adb-connect` is used when it is
# installed because it finds a new wireless-debugging port; `adb reconnect` is
# the fallback for a target that is listed but offline. `adb-watch` is a
# long-running daemon and is deliberately not started by a timed check.
reconnect_once() {
    local out rc connect=${ADB_CONNECT:-adb-connect}
    if command -v "$connect" >/dev/null 2>&1; then
        if [ -n "$PHONE_SERIAL" ]; then
            if out=$("$connect" --serial "$PHONE_SERIAL" --quiet 2>&1); then
                rc=0
            else
                rc=$?
            fi
        else
            if out=$("$connect" --quiet 2>&1); then
                rc=0
            else
                rc=$?
            fi
        fi
        _reconnect_log "attempt via $connect exited $rc${out:+: $(printf '%s' "$out" | tr '\n' ' ')}"
    else
        if out=$(dev_adb reconnect 2>&1); then
            rc=0
        else
            rc=$?
        fi
        _reconnect_log "attempt via adb reconnect exited $rc${out:+: $(printf '%s' "$out" | tr '\n' ' ')}"
    fi
    return "$rc"
}

# At most N attempts, each one logged before it runs. Off unless the runner was
# started with --connect (DEVICE_AUTOHEAL=1). Returns 1 without touching the
# device when auto-heal is disabled, which is what a reachability check wants.
reconnect_ensure() {
    local max=${1:-$DEVICE_RECONNECT_MAX} i=0 connect=${ADB_CONNECT:-adb-connect} version
    if adb_online; then
        return 0
    fi
    if [ "$DEVICE_AUTOHEAL" != 1 ]; then
        _reconnect_log "not attempting: auto-heal is off (pass --connect to enable it)"
        return 1
    fi
    if command -v "$connect" >/dev/null 2>&1; then
        version=$("$connect" --version 2>/dev/null | head -n 1 || true)
        _reconnect_log "using $connect${version:+ ($version)}"
    fi
    while [ "$i" -lt "$max" ]; do
        i=$((i + 1))
        _reconnect_log "attempt $i/$max at $(date -u +%Y-%m-%dT%H:%M:%SZ)"
        reconnect_once || true
        if adb_online; then
            _reconnect_log "online after attempt $i/$max"
            return 0
        fi
        [ "$i" -lt "$max" ] && sleep "$DEVICE_RECONNECT_DELAY"
    done
    _reconnect_log "still offline after $max attempt(s)"
    return 1
}

# A suite that measures reachability calls this first. It records the policy so
# the output can explain why nothing was healed.
autoheal_off() {
    DEVICE_AUTOHEAL=0
    [ "$DEVICE_QUIET" = 1 ] || printf 'reach: auto-heal disabled for this check\n'
    return 0
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

# ------------------------------------------------------------- semantic UI

# Tier 1 has no accessibility APK: `uiautomator dump` writes an XML tree on the
# device and the helpers below read text out of it. A check names a string, never
# a coordinate, so a layout change is a failure of the check, not of the test.
ui_dump() {
    local out
    _last "uiautomator dump $DEVICE_UI_DUMP"
    out=$(dev_adb shell uiautomator dump "$DEVICE_UI_DUMP" 2>&1) || {
        printf 'uiautomator dump failed: %s\n' "$out" >&2
        return 1
    }
    case $out in
        *'dumped to'*|*'UI hierchary dumped'*) ;;
        *) printf 'uiautomator dump said: %s\n' "$out" >&2 ;;
    esac
    dev_adb exec-out cat "$DEVICE_UI_DUMP" 2>/dev/null
}

# One node per line, so grep and the assertions do not depend on the dump being
# pretty-printed (it is one long line on most releases).
ui_nodes() {
    sed 's/>/>\n/g' | sed -n '/^<node /p'
}

# Every node whose attributes contain the text, or the accessibility label. The
# caller gets the line; it does not parse a rectangle.
ui_find() {
    local needle=$1 xml=${2-}
    [ -n "$needle" ] || return 1
    if [ -z "$xml" ]; then
        xml=$(ui_dump) || return 1
    fi
    printf '%s' "$xml" | ui_nodes | grep -F -- "$needle"
}

# The visible text and content descriptions, one per line, for a failure message
# short enough to read.
ui_text() {
    local xml=${1-}
    if [ -z "$xml" ]; then
        xml=$(ui_dump) || return 1
    fi
    printf '%s' "$xml" | ui_nodes \
        | grep -o -e 'text="[^"]*"' -e 'content-desc="[^"]*"' \
        | sed -e 's/^[a-z-]*="//' -e 's/"$//' \
        | grep -v '^$' || true
}

# The centre of a node found by its text. Coordinates are derived here, not
# written in a suite; the deferred tier-3 a11y probe would avoid them entirely.
ui_tap() {
    local needle=$1 node bounds x1 y1 x2 y2
    node=$(ui_find "$needle") || return 1
    bounds=$(printf '%s' "$node" \
        | sed -n 's/.*bounds="\[\([-0-9]*\),\([-0-9]*\)\]\[\([-0-9]*\),\([-0-9]*\)\]".*/\1 \2 \3 \4/p' \
        | head -n 1)
    [ -n "$bounds" ] || return 1
    read -r x1 y1 x2 y2 <<< "$bounds"
    _last "input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))"
    dev_adb shell input tap "$(( (x1 + x2) / 2 ))" "$(( (y1 + y2) / 2 ))"
}

assert_ui_contains() {
    local what=$1 needle=$2 xml found
    if ! xml=$(ui_dump); then
        _assert_fail "$what" "a uiautomator dump" "uiautomator dump failed"
    fi
    found=$(printf '%s' "$xml" | ui_nodes | grep -F -- "$needle" || true)
    if [ -n "$found" ]; then
        ok_line "$what"
    else
        _assert_fail "$what" "the UI contains <$needle>" "$(printf '%s' "$xml" | ui_text)"
    fi
}

assert_ui_absent() {
    local what=$1 needle=$2 xml found
    if ! xml=$(ui_dump); then
        _assert_fail "$what" "a uiautomator dump" "uiautomator dump failed"
    fi
    found=$(printf '%s' "$xml" | ui_nodes | grep -F -- "$needle" || true)
    if [ -n "$found" ]; then
        _assert_fail "$what" "the UI does not contain <$needle>" "$(printf '%s' "$xml" | ui_text)"
    else
        ok_line "$what"
    fi
}

# ------------------------------------------------------------- failure evidence

_artifact_name() {
    printf '%s' "$1" \
        | tr -c 'A-Za-z0-9._-' '-' \
        | sed -e 's/--*/-/g' -e 's/^-//' -e 's/-$//' \
        | cut -c 1-80
}

# A failing check leaves what the device showed. A screenshot needs no extra app
# and a dump is the same tree tier 1 asserts on, so the artifact explains the
# failure without a recording being installed.
artifact_failure() {
    local label=${1:-failure} base dir
    [ -n "$DEVICE_ARTIFACTS" ] || {
        printf '  artifacts: none (DEVICE_ARTIFACTS is unset)\n'
        return 0
    }
    mkdir -p "$DEVICE_ARTIFACTS" 2>/dev/null || {
        printf '  artifacts: could not create %s\n' "$DEVICE_ARTIFACTS"
        return 0
    }
    base=$(_artifact_name "$label")
    dir=$DEVICE_ARTIFACTS
    if ui_dump > "$dir/$base.ui.xml" 2> "$dir/$base.ui.err"; then
        [ -s "$dir/$base.ui.err" ] || rm -f "$dir/$base.ui.err"
    else
        rm -f "$dir/$base.ui.xml"
    fi
    if dev_adb exec-out screencap -p > "$dir/$base.png" 2> "$dir/$base.png.err"; then
        [ -s "$dir/$base.png" ] || rm -f "$dir/$base.png"
        [ -s "$dir/$base.png.err" ] || rm -f "$dir/$base.png.err"
    else
        rm -f "$dir/$base.png"
    fi
    printf '  artifacts: %s\n' "$dir"
    [ -f "$dir/$base.ui.xml" ] && printf '    ui: %s\n' "$dir/$base.ui.xml"
    [ -f "$dir/$base.png" ] && printf '    screenshot: %s\n' "$dir/$base.png"
    return 0
}

# --------------------------------------------------------------- assertions

# A failure must be reproducible from the line above it: the command that
# produced the value, what was expected, what came back, the journal tail, and
# the evidence this run saved. `exit` (not `return`) fails the suite's subshell,
# so the runner's state trap still owns the parent.
_assert_fail() {
    local what=$1 expected=$2 actual=$3 tail command suite
    command=$(cat "$DEVICE_STATE/last-command" 2>/dev/null || true)
    suite=${DEVICE_SUITE_NAME:-unknown}
    printf 'FAIL %s\n' "$what"
    printf '  suite: %s\n' "$suite"
    printf '  time: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
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
    artifact_failure "$suite-$what"
    exit 1
}

# The one `ok` line every assertion prints, and the one place a quiet run can
# silence it without touching the verdict.
ok_line() {
    [ "$DEVICE_QUIET" = 1 ] || printf 'ok   %s\n' "$1"
}

assert_eq() {
    local what=$1 expected=$2 actual=$3
    if [ "$expected" = "$actual" ]; then
        ok_line "$what"
    else
        _assert_fail "$what" "$expected" "$actual"
    fi
}

assert_contains() {
    local what=$1 needle=$2 haystack=$3
    case $haystack in
        *"$needle"*) ok_line "$what" ;;
        *) _assert_fail "$what" "contains <$needle>" "$haystack" ;;
    esac
}

assert_not_contains() {
    local what=$1 needle=$2 haystack=$3
    case $haystack in
        *"$needle"*) _assert_fail "$what" "does not contain <$needle>" "$haystack" ;;
        *) ok_line "$what" ;;
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
        ok_line "$what"
    else
        _assert_fail "$what" "$expected" "$count"
    fi
}

# A named reason is a skip, not a pass: the suite's output says so and the
# runner still reserves exit 0 for checks that actually ran.
skip() {
    SKIPPED=$((SKIPPED + 1))
    [ "$DEVICE_QUIET" = 1 ] || printf 'skip %s: %s\n' "$1" "$2"
}

# A suite that cannot do anything at all exits 3, the runner's "blocked" code.
# It is deliberately not 0: a missing tool or an unknown permission state is a
# result nobody should read as green.
blocked() {
    printf 'skip %s: %s\n' "$1" "$2"
    exit "$BLOCKED"
}

# The test-timings line (`<class>#<test> <ms>ms <passed|failed>`). A blocked
# suite is written as `failed`, because `skipped` is not a status the timing
# gate parses and a skipped suite must never be counted as a pass.
timing() {
    local name=$1 ms=$2 status=$3
    case $status in
        passed|failed) ;;
        *) status=failed ;;
    esac
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
