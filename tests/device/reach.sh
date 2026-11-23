#!/data/data/com.termux/files/usr/bin/bash
# reach.sh - the adb link, timed, with auto-heal explicitly off. Setup may have
# reconnected before the first suite (only with --connect); this suite checks
# that it stayed inside the bound and prints every attempt, rather than letting
# a reconnect hide a target that is only just reachable.
#
# device-slow: 0
set -euo pipefail
if [ -z "${DEVICE_STATE:-}" ]; then
    echo "run through: bash tests/device.sh --only reach" >&2
    exit 2
fi
# shellcheck disable=SC1091
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

autoheal_off

start=$(now_ms)
online=0
if adb_online; then
    online=1
fi
elapsed=$(( $(now_ms) - start ))
assert_eq "adb answers get-state for the selected target" 1 "$online"

bound=${DEVICE_REACH_BOUND_MS:-15000}
setup=${DEVICE_SETUP_RECONNECT_MS:-0}
attempts=${DEVICE_RECONNECT_ATTEMPTS:-0}
printf 'reach: get-state %sms, setup %sms in %s attempt(s), bound %sms\n' \
    "$elapsed" "$setup" "$attempts" "$bound"
# A target that needed a reconnect is not a failure by itself; one that needed
# more time than the bound is. The attempts stay in the output either way.
assert_eq "setup reconnect stayed within ${bound}ms" 1 \
    "$([ "$setup" -le "$bound" ] && echo 1 || echo 0)"
if [ "$attempts" -gt 0 ]; then
    reconnect_report
fi
