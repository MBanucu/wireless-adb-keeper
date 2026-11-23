#!/data/data/com.termux/files/usr/bin/bash
# Run the on-phone suites over keeper.sh, with a state snapshot and a restore
# trap. This is deliberately not part of CI: it needs an installed debug build
# and a phone with wireless debugging already working.
#
#   bash tests/device.sh                 # everything except slow suites
#   bash tests/device.sh --only diag     # one suite
#   bash tests/device.sh --slow          # include the ring-buffer flap
#   bash tests/device.sh --allow-reboot  # opt in to the #39 reboot scenario
#
# shellcheck disable=SC2329
# Exit codes: 0 all selected checks passed; 1 a suite or the restore failed;
# 2 usage/unknown suite; 3 no device; 4 setup or named skip, nothing passed.
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
slow=0
allow_reboot=0

usage() {
    cat <<'USAGE'
Run the on-phone suites over keeper.sh, with a state snapshot and a restore
trap. This needs an installed debug build and a phone with wireless debugging
already working; it is deliberately not part of CI.

  bash tests/device.sh                 # everything except slow suites
  bash tests/device.sh --only diag     # one suite
  bash tests/device.sh --slow          # include the ring-buffer flap
  bash tests/device.sh --allow-reboot  # opt in to the #39 reboot scenario

Exit codes: 0 all selected checks passed; 1 a suite or the restore failed;
2 usage/unknown suite; 3 no device; 4 setup or a suite skipped.
USAGE
    exit "${1:-0}"
}

while [ "$#" -gt 0 ]; do
    case $1 in
        --only)
            [ "$#" -ge 2 ] || { echo "--only needs a suite name" >&2; exit 2; }
            only=$2
            shift 2
            ;;
        --only=*)
            only=${1#*=}
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
        -h|--help|help)
            usage 0
            ;;
        *)
            echo "unknown option: $1" >&2
            usage 2
            ;;
    esac
done

if [ -n "$only" ]; then
    case $only in
        */*|.*) echo "--only takes a suite name, not a path: $only" >&2; exit 2 ;;
    esac
    only=${only%.sh}
    [ -n "$only" ] || { echo "--only needs a suite name" >&2; exit 2; }
fi

# The device check comes before anything that could look like a test result.
if ! command -v "$adb" >/dev/null 2>&1; then
    echo "skip: no device: '$adb' is not on PATH; no suite ran" >&2
    exit 3
fi
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

if [ ! -f "$suite_dir/lib.sh" ]; then
    echo "no $suite_dir/lib.sh; run from the repository" >&2
    exit 4
fi

DEVICE_STATE=$(mktemp -d "${TMPDIR:-/tmp}/wak-device.XXXXXX") || exit 4
DEVICE_PID=$$
DEVICE_ALLOW_REBOOT=$allow_reboot
DEVICE_SLOW=$slow
DEVICE_ROOT=$root
export DEVICE_STATE DEVICE_PID DEVICE_ALLOW_REBOOT DEVICE_SLOW DEVICE_ROOT

# shellcheck disable=SC1091
. "$suite_dir/lib.sh" || { rm -rf "$DEVICE_STATE"; exit 4; }

suite_pid=
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

cleanup() {
    local code=$?
    trap - EXIT INT TERM
    stop_suite
    if [ -f "$DEVICE_STATE/ready" ]; then
        if ! state_restore; then
            echo "FAIL the device state was not fully restored" >&2
            code=1
        fi
    fi
    rm -rf "$DEVICE_STATE"
    exit "$code"
}
trap cleanup EXIT
trap 'interrupted 130' INT
trap 'interrupted 143' TERM

# A debuggable install is what makes run-as work for the journal and prefs.
# build.sh's APK is not debuggable, so name the reason instead of guessing.
if ! dev_adb shell pm path "$PKG" 2>/dev/null | grep -q '^package:'; then
    echo "cannot run: $PKG is not installed on the device" >&2
    exit 4
fi
if ! dev_adb shell "run-as $PKG sh -c 'true'" >/dev/null 2>&1; then
    echo "cannot run: run-as $PKG was refused; install the debuggable Gradle build" >&2
    exit 4
fi

unlocked=$(keeper diag 2>/dev/null | field unlocked || true)
if [ "$unlocked" != true ]; then
    echo "cannot run: the user is not unlocked (the Wi-Fi prefs are unreadable)" >&2
    exit 4
fi

if ! state_snapshot; then
    echo "cannot run: the state snapshot failed; nothing was changed" >&2
    exit 4
fi

mapfile -t discovered < <(
    shopt -s nullglob
    cd "$suite_dir" && printf '%s\n' *.sh
)

suites=()
for file in "${discovered[@]}"; do
    base=${file%.sh}
    [ "$base" = lib ] && continue
    if [ -n "$only" ] && [ "$base" != "$only" ]; then
        continue
    fi
    suites+=("$suite_dir/$file")
done
if [ -n "$only" ] && [ "${#suites[@]}" -eq 0 ]; then
    echo "no suite named '$only' under $suite_dir" >&2
    exit 2
fi
if [ "${#suites[@]}" -eq 0 ]; then
    echo "no device suites under $suite_dir" >&2
    exit 4
fi

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
    exit 1
}

printf 'device suite: %s\n' "${serial:-the only attached device}"
printf 'state: mode=%s ssid=%s passphrase=%s service=%s\n' \
    "$(cat "$DEVICE_STATE/mode")" \
    "$(cat "$DEVICE_STATE/ssid")" \
    "$(cat "$DEVICE_STATE/passphrase")" \
    "$(cat "$DEVICE_STATE/service")"

failures=0
skips=0
passed=0
ran=0
for suite in "${suites[@]}"; do
    name=$(basename "$suite" .sh)
    if [ "$slow" -eq 0 ] && [ -z "$only" ] && suite_is_slow "$suite"; then
        printf 'skip %s (slow: pass --slow to include it)\n' "$name"
        continue
    fi

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
            printf 'PASS %s\n' "$name"
            timing "$name" "$elapsed" passed
            ;;
        4)
            skips=$((skips + 1))
            printf 'SKIP %s\n' "$name"
            timing "$name" "$elapsed" skipped
            ;;
        *)
            failures=$((failures + 1))
            printf 'FAIL %s (exit %s)\n' "$name" "$rc"
            timing "$name" "$elapsed" failed
            ;;
    esac
done

printf '\n'
if [ "$ran" -eq 0 ]; then
    echo "no suite ran (slow suites need --slow)" >&2
    exit 4
fi
if [ "$failures" -ne 0 ]; then
    printf 'FAIL %s of %s suite(s), %s skipped\n' \
        "$failures" "$((passed + failures + skips))" "$skips"
    exit 1
fi
if [ "$skips" -ne 0 ]; then
    printf 'SKIP %s suite(s), %s passed: no suite reported a pass on a skip\n' \
        "$skips" "$passed"
    exit 4
fi
printf 'PASS %s suite(s)\n' "$passed"
exit 0
