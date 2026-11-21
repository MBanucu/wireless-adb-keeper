#!/usr/bin/env bash
# unlock.sh -- unlock the phone's keyguard with its PIN, over adb.
#
#   PHONE_PIN=4862 ./unlock.sh              # unlock now
#   ./unlock.sh --pin 4862 --wait           # wait for boot first, then unlock
#   ./unlock.sh --check                     # print the state, change nothing
#
# The PIN is never stored here: pass --pin or PHONE_PIN.
#
# Exit status: 0 unlocked (or already unlocked), 1 timeout/locked, 2 usage.
set -uo pipefail

SERIAL=${PHONE_SERIAL:-}
PIN=${PHONE_PIN:-}
WAIT=0
CHECK=0
QUIET=0
TIMEOUT=120

usage() {
    sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
}

while [ $# -gt 0 ]; do
    case "$1" in
        --pin) PIN=${2:-}; shift ;;
        --wait) WAIT=1 ;;
        --check) CHECK=1 ;;
        --quiet) QUIET=1 ;;
        --timeout) TIMEOUT=${2:-}; shift ;;
        -h|--help) usage ;;
        *) echo "unknown argument: $1" >&2; usage ;;
    esac
    shift
done

if [ "$CHECK" = 0 ] && [ -z "$PIN" ]; then
    echo "no PIN: pass --pin <digits> or set PHONE_PIN" >&2
    exit 2
fi

pick_serial() {
    [ -n "$SERIAL" ] && return 0
    local list count usb
    list=$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
    count=$(printf '%s\n' "$list" | grep -c . || true)
    case "$count" in
        0) echo "no adb device; plug the phone in" >&2; return 1 ;;
        1) SERIAL=$list ;;
        *)
            usb=$(printf '%s\n' "$list" | grep -v ':' | head -n 1 || true)
            SERIAL=${usb:-$(printf '%s\n' "$list" | head -n 1)}
            ;;
    esac
}

pick_serial || exit 1
d() { adb -s "$SERIAL" "$@"; }
say() { [ "$QUIET" = 1 ] || printf '%s\n' "$*"; }

deadline=$(( SECONDS + TIMEOUT ))
remaining() { echo $(( deadline - SECONDS )); }

is_unlocked() { d shell dumpsys user 2>/dev/null | grep -q 'State: RUNNING_UNLOCKED'; }

if [ "$CHECK" = 1 ]; then
    if is_unlocked; then
        echo "unlocked"
        exit 0
    fi
    echo "locked"
    exit 1
fi

if [ "$WAIT" = 1 ]; then
    # adbd comes back before the framework; one round trip on the phone then,
    # exactly like reboot-unlock.sh, instead of an adb call per tick.
    while ! d get-state >/dev/null 2>&1; do
        [ "$(remaining)" -le 0 ] && { echo "timeout: adbd did not come back" >&2; exit 1; }
        sleep 0.2
    done
    until d shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 0.1; done; echo booted' \
            2>/dev/null | grep -q booted; do
        [ "$(remaining)" -le 0 ] && { echo "timeout: sys.boot_completed never became 1" >&2; exit 1; }
        sleep 0.2
    done
fi

if is_unlocked; then
    say "already unlocked"
    exit 0
fi

# Swipe up from three quarters of the screen to a sixth: works on any size.
size=$(d shell wm size 2>/dev/null | awk -F': ' '/Physical size/ { print $2 }' | tr -d '\r')
width=${size%x*}
height=${size#*x}
if [ -z "${width:-}" ] || [ -z "${height:-}" ] || [ "$width" = "$size" ]; then
    width=1080
    height=2400
fi
x=$(( width / 2 ))
y_from=$(( height * 3 / 4 ))
y_to=$(( height / 6 ))

attempt=0
while ! is_unlocked; do
    attempt=$(( attempt + 1 ))
    if [ "$(remaining)" -le 0 ] || [ "$attempt" -gt 15 ]; then
        echo "timeout: still locked after $attempt attempts" >&2
        exit 1
    fi
    d shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
    d shell wm dismiss-keyguard >/dev/null 2>&1
    is_unlocked && break
    d shell input swipe "$x" "$y_from" "$x" "$y_to" 120 >/dev/null 2>&1
    sleep 0.3
    d shell input text "$PIN" >/dev/null 2>&1
    d shell input keyevent 66 >/dev/null 2>&1
    for _ in 1 2 3 4 5 6; do
        is_unlocked && break
        sleep 0.25
    done
done

say "unlocked"
