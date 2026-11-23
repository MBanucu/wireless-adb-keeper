#!/data/data/com.termux/files/usr/bin/bash
# errors.sh - the documented failure shapes: unknown methods, a missing SSID,
# and the token/uid gate. A wrapper validation failure must exit non-zero; a
# provider error must come back as error= with a 4xx code.
#
# device-slow: 0
set -euo pipefail
if [ -z "${DEVICE_STATE:-}" ]; then
    echo "run through: bash tests/device.sh --only errors" >&2
    exit 2
fi
# shellcheck disable=SC1091
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

run_fails() {
    local what=$1 rc out_file
    shift
    out_file=$DEVICE_STATE/errors.out
    if "$@" >"$out_file" 2>&1; then
        rc=0
    else
        rc=$?
    fi
    out=$(cat "$out_file")
    assert_eq "$what exits non-zero" 1 "$([ "$rc" -ne 0 ] && echo 1 || echo 0)"
}

out=
run_fails "an unknown keeper.sh command" keeper not-a-command
assert_contains "the unknown command is explained" 'unknown command' "$out"

run_fails "keeper.sh save without an SSID" keeper save
assert_contains "the save usage is printed" 'usage:' "$out"

run_fails "keeper.sh mode with an invalid value" keeper mode sideways
assert_contains "the mode usage is printed" 'usage:' "$out"

raw=$(content_call --method zz-not-a-method)
code=$(reply_code "$raw")
data=$(printf '%s\n' "$raw" | strip_envelope)
assert_eq "an unknown provider method is a 400" 400 "$code"
assert_eq "the unknown method reply starts error=" 'error=' "${data:0:6}"
assert_contains "the unknown method is named" 'error=unknown method' "$data"

raw=$(content_call --method save_wifi)
code=$(reply_code "$raw")
data=$(printf '%s\n' "$raw" | strip_envelope)
assert_eq "a missing SSID is a 400" 400 "$code"
assert_eq "the missing SSID reply starts error=" 'error=' "${data:0:6}"
assert_contains "the missing SSID is explained" 'error=ssid is required' "$data"

# The provider gate is uid based, and adb shell is uid 2000, so the refusal is
# only reachable through a non-shell uid. That needs root or a helper app on
# this project's unrooted target; a caller can supply the prefix when testing
# a rooted build. Everywhere else this is a named skip, not a pass.
if [ -n "${DEVICE_FOREIGN_UID_CMD:-}" ]; then
    raw=$(dev_adb shell \
        "$DEVICE_FOREIGN_UID_CMD content call --uri $AUTH --method status" 2>&1 || true)
    data=$(printf '%s\n' "$raw" | strip_envelope)
    assert_eq "a non-shell uid reply starts error=" 'error=' "${data:0:6}"
    assert_contains "a non-shell uid is refused" 'error=refused' "$data"
else
    skip "provider 403 for a non-shell uid" \
        "no non-shell uid without root; set DEVICE_FOREIGN_UID_CMD to a prefix such as 'su 10123 -c'"
fi
