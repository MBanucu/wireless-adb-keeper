#!/data/data/com.termux/files/usr/bin/bash
# Run the on-phone suites over keeper.sh, with a state snapshot and a restore
# trap. This is deliberately not part of CI: it needs an installed debug build
# and a phone with wireless debugging already working.
#
#   bash tests/device.sh                 # everything except slow suites
#   bash tests/device.sh --only diag     # one suite, or --only diag,status
#   bash tests/device.sh --list          # the suite names, no device needed
#   bash tests/device.sh --quiet         # failures and the summary only
#   bash tests/device.sh --record        # optional adb-harness video and events
#   bash tests/device.sh --connect       # opt in to setup auto-reconnect
#   bash tests/device.sh --restore       # put back a dead run's state
#   bash tests/device.sh --slow          # include the ring-buffer flap
#   bash tests/device.sh --allow-reboot  # opt in to the #39 reboot scenario
#
# The default tier needs only adb, bash and coreutils. `--record` uses the
# adb-harness `capture` command when it is on PATH and never for a pass.
#
# shellcheck disable=SC2329
# Exit codes: 0 all selected checks passed; 1 a suite or the restore failed;
# 2 usage or an unknown --only name; 3 blocked (no adb, no device, missing
# required tool, or a suite that skipped as a whole). A skip is never 0.
#
# A suite is a shell file in tests/device/*.sh, sourced in a subshell after
# lib.sh was sourced here once. It inherits the helpers and the runner's PID,
# and cannot leave a trap behind in the parent.
set -u

here=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd) || exit 1
root=$(CDPATH='' cd -- "$here/.." && pwd) || exit 1
suite_dir=${DEVICE_SUITE_DIR:-$here/device}
adb=${ADB:-adb}
serial=${PHONE_SERIAL:-}
only=
only_set=0
slow=0
allow_reboot=0
record=0
quiet=0
connect=0
restore_only=0
list_only=0

# The fixed state directory is what makes --restore possible: a run killed
# outright leaves its snapshot here, and the next run repairs the phone before
# taking a new one. Artifacts are per-run and never removed by the runner.
DEVICE_STATE=${DEVICE_STATE:-${DEVICE_STATE_DIR:-$root/tests/out/device-state}}
DEVICE_ARTIFACTS=${DEVICE_ARTIFACTS:-}
CAPTURE=${CAPTURE:-capture}
DEVICE_CAPTURE_DIR=${DEVICE_CAPTURE_DIR:-}

usage() {
    cat <<'USAGE'
Run the on-phone suites over keeper.sh, with a state snapshot and a restore
trap. This needs an installed debug build and a phone with wireless debugging
already working; it is deliberately not part of CI.

  bash tests/device.sh                 # everything except slow suites
  bash tests/device.sh --only diag     # one suite, or --only diag,status
  bash tests/device.sh --list          # the suite names, no device needed
  bash tests/device.sh --quiet         # failures and the summary only
  bash tests/device.sh --record        # optional adb-harness video and events
  bash tests/device.sh --connect       # opt in to setup auto-reconnect
  bash tests/device.sh --restore       # put back a dead run's state
  bash tests/device.sh --slow          # include the ring-buffer flap
  bash tests/device.sh --allow-reboot  # opt in to the #39 reboot scenario

Exit codes: 0 all selected checks passed; 1 a suite or the restore failed;
2 usage or an unknown --only name; 3 blocked (no adb, no device, missing
required tool, or a suite that skipped as a whole).
USAGE
    exit "${1:-0}"
}

while [ "$#" -gt 0 ]; do
    case $1 in
        --only)
            [ "$#" -ge 2 ] || { echo "--only needs a suite name" >&2; exit 2; }
            only="$only ${2//,/ }"
            only_set=1
            shift 2
            ;;
        --only=*)
            only="$only ${1#*=}"
            only=${only//,/ }
            only_set=1
            shift
            ;;
        --slow)
            slow=1
            shift
            ;;
        --allow-reboot)
            allow_reboot=1
            shift
            ;;
        --record)
            record=1
            shift
            ;;
        --no-record)
            record=0
            shift
            ;;
        --quiet)
            quiet=1
            shift
            ;;
        --connect|--auto-connect)
            connect=1
            shift
            ;;
        --serial)
            [ "$#" -ge 2 ] || { echo "--serial needs a value" >&2; exit 2; }
            serial=$2
            shift 2
            ;;
        --serial=*)
            serial=${1#*=}
            shift
            ;;
        --restore)
            restore_only=1
            shift
            ;;
        --list)
            list_only=1
            shift
            ;;
        -h|--help|help)
            usage 0
            ;;
        *)
            echo "unknown option: $1" >&2
            usage 2
            ;;
    esac
done
PHONE_SERIAL=$serial
export PHONE_SERIAL

# Normalise the --only list once: names only, no paths, and no empty tokens.
if [ "$only_set" = 1 ]; then
    normalized=
    for token in $only; do
        case $token in
            */*|.*) echo "--only takes a suite name, not a path: $token" >&2; exit 2 ;;
        esac
        token=${token%.sh}
        [ -n "$token" ] || { echo "--only needs a suite name" >&2; exit 2; }
        normalized="$normalized $token"
    done
    only=${normalized# }
fi

discover_suites() {
    (
        shopt -s nullglob
        cd "$suite_dir" || exit 1
        printf '%s\n' *.sh
    )
}

if [ ! -d "$suite_dir" ]; then
    echo "no suite directory at $suite_dir" >&2
    exit 3
fi
mapfile -t discovered < <(discover_suites)
if [ "${#discovered[@]}" -eq 0 ]; then
    echo "no device suites under $suite_dir" >&2
    exit 3
fi

if [ "$list_only" = 1 ]; then
    for file in "${discovered[@]}"; do
        base=${file%.sh}
        [ "$base" = lib ] && continue
        printf '%s\n' "$base"
    done
    exit 0
fi

suites=()
for file in "${discovered[@]}"; do
    base=${file%.sh}
    [ "$base" = lib ] && continue
    if [ "$only_set" = 1 ]; then
        case " $only " in
            *" $base "*) ;;
            *) continue ;;
        esac
    fi
    suites+=("$suite_dir/$file")
done
if [ "$only_set" = 1 ] && [ "${#suites[@]}" -eq 0 ]; then
    echo "no suite named '$only' under $suite_dir" >&2
    exit 2
fi
if [ "${#suites[@]}" -eq 0 ]; then
    echo "no device suites selected under $suite_dir" >&2
    exit 3
fi

if ! command -v "$adb" >/dev/null 2>&1; then
    echo "skip: no device: '$adb' is not on PATH; no suite ran" >&2
    exit 3
fi

# The snapshot directory is also where lib.sh keeps the last command. Nothing
# creates it yet; --restore reads it, the normal path takes a fresh snapshot.
if [ "$connect" = 1 ]; then
    DEVICE_AUTOHEAL=1
fi
DEVICE_AUTOHEAL=${DEVICE_AUTOHEAL:-0}
export DEVICE_STATE DEVICE_ROOT=$root DEVICE_PID=$$ DEVICE_ALLOW_REBOOT=$allow_reboot
export DEVICE_SLOW=$slow DEVICE_QUIET=$quiet DEVICE_AUTOHEAL
if [ ! -f "$suite_dir/lib.sh" ]; then
    echo "no $suite_dir/lib.sh; run from the repository" >&2
    exit 3
fi
# shellcheck disable=SC1091
. "$suite_dir/lib.sh" || exit 3

if [ "$restore_only" = 1 ]; then
    if [ ! -f "$DEVICE_STATE/ready" ]; then
        echo "nothing to restore: no saved device state in $DEVICE_STATE"
        exit 0
    fi
    if ! adb_online; then
        echo "skip: no device: adb get-state was not device; nothing was restored" >&2
        exit 3
    fi
    if state_restore; then
        rm -rf "$DEVICE_STATE"
        echo "restore: the saved device state is back"
        exit 0
    fi
    echo "FAIL the saved device state was not fully restored; retry with --restore" >&2
    exit 1
fi

run_id=$(date -u +%Y%m%dT%H%M%SZ)-$$
DEVICE_ARTIFACTS=${DEVICE_ARTIFACTS:-$root/tests/out/device/$run_id}
export DEVICE_ARTIFACTS
DEVICE_RECONNECT_LOG=${DEVICE_RECONNECT_LOG:-$DEVICE_ARTIFACTS/reconnect.log}
export DEVICE_RECONNECT_LOG
DEVICE_CAPTURE_DIR=${DEVICE_CAPTURE_DIR:-$DEVICE_ARTIFACTS/capture}
export DEVICE_CAPTURE_DIR
mkdir -p "$DEVICE_ARTIFACTS" || exit 3

suite_pid=
FAILED_SUITES=
STATE_OWNED=0
RECORDING=0

stop_suite() {
    [ -n "$suite_pid" ] || return 0
    kill -TERM "$suite_pid" 2>/dev/null || true
    wait "$suite_pid" 2>/dev/null || true
    suite_pid=
}

interrupted() {
    local code=$1
    stop_suite
    exit "$code"
}

# One video for the run and an event beside every suite. `capture` is optional:
# a missing harness prints one reason and the checks run unchanged.
record_start() {
    local version
    [ "$record" = 1 ] || return 0
    if ! command -v "$CAPTURE" >/dev/null 2>&1; then
        printf 'recording skipped: adb-harness not found\n'
        record=0
        return 0
    fi
    version=$("$CAPTURE" --version 2>/dev/null | head -n 1 || true)
    printf 'recording: %s%s\n' "$CAPTURE" "${version:+ ($version)}"
    local -a target=()
    [ -n "$serial" ] && target=(--serial "$serial")
    if "$CAPTURE" start --dir "$DEVICE_CAPTURE_DIR" "${target[@]}" \
            >> "$DEVICE_ARTIFACTS/capture.log" 2>&1; then
        RECORDING=1
    else
        printf 'recording skipped: capture start failed'
        if [ -s "$DEVICE_ARTIFACTS/capture.log" ]; then
            printf ': %s' "$(tail -n 1 "$DEVICE_ARTIFACTS/capture.log")"
        fi
        printf '\n'
        record=0
    fi
}

record_event() {
    [ "$RECORDING" = 1 ] || return 0
    local -a target=()
    [ -n "$serial" ] && target=(--serial "$serial")
    "$CAPTURE" event "$1" --dir "$DEVICE_CAPTURE_DIR" "${target[@]}" \
        >> "$DEVICE_ARTIFACTS/capture.log" 2>&1 || true
}

record_stop() {
    [ "$RECORDING" = 1 ] || return 0
    RECORDING=0
    local -a target=()
    local name
    [ -n "$serial" ] && target=(--serial "$serial")
    if ! "$CAPTURE" stop --dir "$DEVICE_CAPTURE_DIR" "${target[@]}" \
            >> "$DEVICE_ARTIFACTS/capture.log" 2>&1; then
        printf 'recording: capture stop failed (the checks are unaffected)\n'
        return 0
    fi
    if [ -z "$FAILED_SUITES" ]; then
        printf 'recording: %s\n' "$DEVICE_CAPTURE_DIR"
        return 0
    fi
    mkdir -p "$DEVICE_ARTIFACTS/frames" || return 0
    for name in $FAILED_SUITES; do
        if ! "$CAPTURE" frames --event "check:$name:failed" \
                --out "$DEVICE_ARTIFACTS/frames" --dir "$DEVICE_CAPTURE_DIR" \
                "${target[@]}" >> "$DEVICE_ARTIFACTS/capture.log" 2>&1; then
            printf 'recording: no frame for check:%s:failed\n' "$name"
        fi
    done
    printf 'recording: frames of the failures in %s\n' "$DEVICE_ARTIFACTS/frames"
}

cleanup() {
    local code=$?
    trap - EXIT INT TERM
    stop_suite
    record_stop
    if [ "$STATE_OWNED" = 1 ] && [ -f "$DEVICE_STATE/ready" ]; then
        if state_restore; then
            rm -rf "$DEVICE_STATE"
        else
            printf 'FAIL the device state was not fully restored\n' >&2
            printf '  saved state kept in %s; rerun bash tests/device.sh --restore\n' \
                "$DEVICE_STATE" >&2
            [ "$code" -eq 0 ] && code=1
        fi
    elif [ "$STATE_OWNED" = 1 ]; then
        rm -rf "$DEVICE_STATE"
    fi
    exit "$code"
}
trap cleanup EXIT
trap 'interrupted 130' INT
trap 'interrupted 143' TERM

# A previous run that was killed never reached its trap. Repair what it left
# before changing more; its snapshot is still the truth about the phone.
if [ -f "$DEVICE_STATE/ready" ]; then
    printf 'a previous run left the device changed; restoring it first\n' >&2
    if ! state_restore; then
        printf 'FAIL the previous run could not be restored; not starting\n' >&2
        exit 1
    fi
    rm -rf "$DEVICE_STATE"
fi

# Setup may heal an empty device list, but only with --connect, and every
# attempt is timestamped. A reachability suite reads the same log and fails when
# the time to a working target exceeded its bound, so a reconnect cannot hide.
: > "$DEVICE_RECONNECT_LOG" 2>/dev/null || true
setup_start=$(now_ms)
reconnect_ensure "$DEVICE_RECONNECT_MAX" || true
DEVICE_SETUP_RECONNECT_MS=$(( $(now_ms) - setup_start ))
DEVICE_RECONNECT_ATTEMPTS=$(grep -c '^attempt ' "$DEVICE_RECONNECT_LOG" 2>/dev/null || true)
DEVICE_RECONNECT_ATTEMPTS=${DEVICE_RECONNECT_ATTEMPTS:-0}
export DEVICE_SETUP_RECONNECT_MS DEVICE_RECONNECT_ATTEMPTS

# The device check comes before anything that could look like a test result.
if [ -n "$serial" ]; then
    state=$("$adb" -s "$serial" get-state 2>&1) || true
else
    state=$("$adb" get-state 2>&1) || true
fi
state=$(printf '%s\n' "$state" | tr -d '\r' | tail -n 1)
if [ "$state" != device ]; then
    echo "skip: no device: adb get-state returned '${state:-no output}'; no suite ran" >&2
    exit 3
fi

# A debuggable install is what makes run-as work for the journal and prefs.
# build.sh's APK is not debuggable, so name the reason instead of guessing.
if ! dev_adb shell pm path "$PKG" 2>/dev/null | grep -q '^package:'; then
    echo "cannot run: $PKG is not installed on the device" >&2
    exit 3
fi
if ! dev_adb shell "run-as $PKG sh -c 'true'" >/dev/null 2>&1; then
    echo "cannot run: run-as $PKG was refused; install the debuggable Gradle build" >&2
    exit 3
fi

unlocked=$(keeper diag 2>/dev/null | field unlocked || true)
if [ "$unlocked" != true ]; then
    echo "cannot run: the user is not unlocked (the Wi-Fi prefs are unreadable)" >&2
    exit 3
fi

STATE_OWNED=1
if ! state_snapshot; then
    echo "cannot run: the state snapshot failed; nothing was changed" >&2
    exit 3
fi

printf 'device suite: %s\n' "${serial:-the only attached device}"
printf 'artifacts: %s\n' "$DEVICE_ARTIFACTS"
printf 'state: mode=%s ssid=%s passphrase=%s service=%s\n' \
    "$(cat "$DEVICE_STATE/mode")" \
    "$(cat "$DEVICE_STATE/ssid")" \
    "$(cat "$DEVICE_STATE/passphrase")" \
    "$(cat "$DEVICE_STATE/service")"
if [ "$DEVICE_SETUP_RECONNECT_MS" -gt 0 ]; then
    printf 'reconnect: setup took %sms in %s attempt(s)\n' \
        "$DEVICE_SETUP_RECONNECT_MS" "$DEVICE_RECONNECT_ATTEMPTS"
fi

record_start

suite_is_slow() {
    grep -q '^# device-slow: 1$' "$1"
}

# The ERR trap is for an unexpected non-zero command. Assertions exit
# deliberately, so they are not reported twice.
device_suite_abort() {
    local name=$1 line=$2 rc=$3 tail
    printf 'FAIL %s aborted at line %s: %s (exit %s)\n' \
        "$name" "$line" "${BASH_COMMAND:-unknown}" "$rc"
    tail=$(journal | tail -n 20 || true)
    printf '  journal (last 20):\n'
    if [ -n "$tail" ]; then
        printf '%s\n' "$tail" | sed 's/^/    /'
    else
        printf '    (empty or unavailable)\n'
    fi
    artifact_failure "$name-abort"
    exit 1
}

failures=0
skips=0
passed=0
ran=0
for suite in "${suites[@]}"; do
    name=$(basename "$suite" .sh)
    # --only is an explicit request for that suite, even a slow one.
    if [ "$slow" -eq 0 ] && [ "$only_set" -eq 0 ] && suite_is_slow "$suite"; then
        [ "$quiet" = 1 ] || printf 'skip %s (slow: pass --slow to include it)\n' "$name"
        continue
    fi

    export DEVICE_SUITE_NAME=$name
    record_event "check:$name:start"
    start=$(now_ms)
    ( set -Eeuo pipefail
      trap 'device_suite_abort "$name" "$LINENO" "$?"' ERR
      # shellcheck disable=SC1090
      . "$suite"
    ) &
    suite_pid=$!
    wait "$suite_pid"
    rc=$?
    suite_pid=
    elapsed=$(( $(now_ms) - start ))
    ran=$((ran + 1))
    case $rc in
        0)
            passed=$((passed + 1))
            record_event "check:$name:end"
            [ "$quiet" = 1 ] || printf 'PASS %s\n' "$name"
            timing "$name" "$elapsed" passed
            ;;
        3)
            skips=$((skips + 1))
            record_event "check:$name:blocked"
            [ "$quiet" = 1 ] || printf 'SKIP %s\n' "$name"
            timing "$name" "$elapsed" failed
            ;;
        *)
            failures=$((failures + 1))
            FAILED_SUITES="$FAILED_SUITES $name"
            record_event "check:$name:failed"
            printf 'FAIL %s (exit %s)\n' "$name" "$rc"
            artifact_failure "$name"
            timing "$name" "$elapsed" failed
            ;;
    esac
done

record_stop

printf '\n'
if [ "$ran" -eq 0 ]; then
    echo "no suite ran (slow suites need --slow)" >&2
    exit 3
fi
if [ "$failures" -ne 0 ]; then
    printf 'FAIL %s of %s suite(s), %s skipped\n' \
        "$failures" "$((passed + failures + skips))" "$skips"
    exit 1
fi
if [ "$skips" -ne 0 ]; then
    printf 'SKIP %s suite(s), %s passed: no suite reported a pass on a skip\n' \
        "$skips" "$passed"
    exit 3
fi
printf 'PASS %s suite(s)\n' "$passed"
exit 0
