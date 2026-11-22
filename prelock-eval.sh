#!/usr/bin/env bash
# prelock-eval.sh -- wait for a booted, still-locked phone, give the network
# 10 seconds to come up, then say what is reachable before the first unlock.
#
#   ./prelock-eval.sh            # wait for boot, settle 10 s, report
#   ./prelock-eval.sh --settle 20
#
# It never unlocks; ../phone-link-tools/unlock.sh does that.
set -uo pipefail

SERIAL=${PHONE_SERIAL:-}
SETTLE=10
TIMEOUT=300

while [ $# -gt 0 ]; do
    case "$1" in
        --settle) SETTLE=${2:-10}; shift ;;
        --timeout) TIMEOUT=${2:-300}; shift ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
    shift
done

start=$(date +%s%N)
elapsed() {
    local ms=$(( ($(date +%s%N) - start) / 1000000 ))
    printf '%d.%03d' $(( ms / 1000 )) $(( ms % 1000 ))
}
say() { printf 't+%ss  %s\n' "$(elapsed)" "$*"; }
d() { adb -s "$SERIAL" "$@"; }

deadline=$(( SECONDS + TIMEOUT ))

# The phone may still be rebooting: wait for a transport, then switch to USB if
# no serial was given.
while ! adb devices | awk 'NR > 1 && $2 == "device" { found=1 } END { exit !found }'; do
    [ "$SECONDS" -lt "$deadline" ] || { echo "timeout: no adb device" >&2; exit 1; }
    sleep 0.5
done
if [ -z "$SERIAL" ]; then
    SERIAL=$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }' \
        | grep -v ':' | head -n 1)
fi
while ! d get-state >/dev/null 2>&1; do
    [ "$SECONDS" -lt "$deadline" ] || { echo "timeout: no adbd" >&2; exit 1; }
    sleep 0.2
done
say "adbd up"

until d shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 0.1; done; echo booted' \
        2>/dev/null | grep -q booted; do
    [ "$SECONDS" -lt "$deadline" ] || { echo "timeout: no boot" >&2; exit 1; }
    sleep 0.2
done
say "boot_completed"

# Connections need a moment after the framework is up; the receivers land about
# a second later, the interfaces a few more.
# Wait for a listener rather than trusting a fixed settle: poll until 1080 or
# 1081 is bound, or the settle budget runs out.
waited=0
while [ "$waited" -lt "$SETTLE" ]; do
    if d shell netstat -ltn 2>/dev/null | grep -qE ':1080|:1081'; then
        break
    fi
    sleep 1
    waited=$(( waited + 1 ))
done
say "waited ${waited}s for a listener"

say "user state: $(d shell dumpsys user 2>/dev/null | grep -m1 'State:' | tr -s ' ')"
say "addresses:"
d shell ip -4 addr show 2>/dev/null | awk '/^[0-9]+:/ {iface=$2} /inet / {print "    " iface, $2}'
say "wifi: $(d shell cmd wifi status 2>/dev/null | grep -m1 'is enabled' | tr -s ' ')"
say "wifi: $(d shell cmd wifi status 2>/dev/null | grep -m1 'is connected' | tr -s ' ')"
say "listeners:"
d shell netstat -ltn 2>/dev/null | grep -E ':1080|:1081' | awk '{print "    " $4}' | sort -u
say "unlock: $(d logcat -b events -d 2>/dev/null | grep -m1 USER_UNLOCKED | cut -d" " -f2 || echo none)"
conn_test() {
    local host=$1 port=$2
    if timeout 3 bash -c "exec 3<>/dev/tcp/$host/$port" 2>/dev/null; then
        echo "reachable"
    else
        echo "no connection"
    fi
}

say "conn test 192.168.77.232:1080 -> $(conn_test 192.168.77.232 1080)"
say "conn test 192.168.77.232:1081 -> $(conn_test 192.168.77.232 1081)"
say "boot events:"
d logcat -b events -d 2>/dev/null \
    | grep -E 'LOCKED_BOOT_COMPLETED|am_foreground_service_start.*(dbprobe|phonelink)' \
    | sed 's/^/    /' | head -10
