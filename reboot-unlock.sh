#!/usr/bin/env bash
# reboot-unlock.sh -- restart the phone and unlock it as soon as it can be
# unlocked, so a dev loop does not sit through the boot screen.
#
#   ./reboot-unlock.sh --reboot --pin 4862          # reboot, then unlock
#   ./reboot-unlock.sh --pin 4862                   # already booting: unlock
#   ./reboot-unlock.sh --boot-only                  # wait for boot, stay locked
#   ./reboot-unlock.sh --reboot --boot-only --timeout 120
#
# Polling strategy: boot completion is waited for with ONE adb round trip and a
# 100 ms loop running on the phone (`getprop sys.boot_completed`), instead of
# several adb calls per second from here. Unlocking is then a handful of input
# events, retried until the user state says RUNNING_UNLOCKED.
#
# Exit status: 0 unlocked (or booted with --boot-only), 1 timeout, 2 usage.
set -uo pipefail

SERIAL=${PHONE_SERIAL:-}
PIN=${PHONE_PIN:-}
REBOOT=0
BOOT_ONLY=0
TIMEOUT=300

usage() {
    sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
}

while [ $# -gt 0 ]; do
    case "$1" in
        --reboot) REBOOT=1 ;;
        --boot-only) BOOT_ONLY=1 ;;
        --pin) PIN=${2:-}; shift ;;
        --timeout) TIMEOUT=${2:-}; shift ;;
        -h|--help) usage ;;
        *) echo "unknown argument: $1" >&2; usage ;;
    esac
    shift
done

if [ "$BOOT_ONLY" = 0 ] && [ -z "$PIN" ]; then
    echo "no PIN: pass --pin <digits> or set PHONE_PIN" >&2
    exit 2
fi

start=$(date +%s%N)
elapsed() {
    local ms=$(( ($(date +%s%N) - start) / 1000000 ))
    printf '%d.%03d' $(( ms / 1000 )) $(( ms % 1000 ))
}
say() { printf 't+%ss  %s\n' "$(elapsed)" "$*"; }

# One device is the common case; when USB and Wi-Fi are both attached, the USB
# serial is the one that comes back after a reboot on its own.
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

deadline=$(( SECONDS + TIMEOUT ))
remaining() { echo $(( deadline - SECONDS )); }

if [ "$REBOOT" = 1 ]; then
    d reboot || { echo "adb reboot failed" >&2; exit 1; }
    say "reboot sent ($SERIAL)"
fi

# adbd is back long before the framework has booted; wait for the transport.
while ! d get-state >/dev/null 2>&1; do
    if [ "$(remaining)" -le 0 ]; then
        echo "timeout: adbd did not come back" >&2
        exit 1
    fi
    sleep 0.2
done
say "adbd up"

# The wait happens on the phone: one round trip, 100 ms granularity, and it
# survives this shell being slow. A killed adbd (wireless debugging restart)
# drops the shell, so re-issue it while time remains.
until d shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 0.1; done; echo booted' \
        2>/dev/null | grep -q booted; do
    if [ "$(remaining)" -le 0 ]; then
        echo "timeout: sys.boot_completed never became 1" >&2
        exit 1
    fi
    sleep 0.2
done
say "boot_completed"

if [ "$BOOT_ONLY" = 1 ]; then
    say "done, still locked"
    exit 0
fi

is_unlocked() { d shell dumpsys user 2>/dev/null | grep -q 'State: RUNNING_UNLOCKED'; }

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
