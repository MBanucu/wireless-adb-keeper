#!/data/data/com.termux/files/usr/bin/bash
# permissions.sh - revoke and grant ACCESS_FINE_LOCATION with the app running,
# and assert that both the provider snapshot and the diag snapshot follow.
#
# device-slow: 0
set -euo pipefail
if [ -z "${DEVICE_STATE:-}" ]; then
    echo "run through: bash tests/device.sh --only permissions" >&2
    exit 2
fi
# shellcheck disable=SC1091
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

PERM=android.permission.ACCESS_FINE_LOCATION

dump() {
    dev_adb shell dumpsys package "$PKG" 2>/dev/null | tr -d '\r' || true
}

ensure_service() {
    if [ "$(keeper status | field service)" != running ]; then
        keeper start >/dev/null
    fi
    assert_eq "the monitor service is running with the app" 1 \
        "$(wait_for_service 20 && echo 1 || echo 0)"
}

assert_permission() {
    local expected=$1 field_value status_text diag_text
    if [ "$expected" = granted ]; then
        field_value=granted
    else
        field_value='not granted'
    fi
    assert_eq "dumpsys reports location $expected" "$expected" \
        "$(permission_state "$(dump)" "$PERM")"
    ensure_service
    status_text=$(keeper status)
    assert_eq "status location is $field_value" "$field_value" \
        "$(printf '%s\n' "$status_text" | field location)"
    diag_text=$(keeper diag)
    # The audit row also carries the exact grant command when it is denied.
    assert_contains "diag perm.fine_location reports $field_value" "$field_value" \
        "$(printf '%s\n' "$diag_text" | field perm.fine_location)"
}

original=$(permission_state "$(dump)" "$PERM")
if [ "$original" = unknown ]; then
    skip "location permission" "dumpsys package does not report $PERM"
    exit "$BLOCKED"
fi
ensure_service

out=
if [ "$original" = granted ]; then
    if ! out=$(pm_revoke "$PERM" 2>&1); then
        skip "revoke $PERM" "pm refused: $out"
        exit "$BLOCKED"
    fi
    assert_permission denied
    if ! out=$(pm_grant "$PERM" 2>&1); then
        skip "grant $PERM" "pm refused: $out"
        exit "$BLOCKED"
    fi
    assert_permission granted
else
    if ! out=$(pm_grant "$PERM" 2>&1); then
        skip "grant $PERM" "pm refused: $out"
        exit "$BLOCKED"
    fi
    assert_permission granted
    if ! out=$(pm_revoke "$PERM" 2>&1); then
        skip "revoke $PERM" "pm refused: $out"
        exit "$BLOCKED"
    fi
    assert_permission denied
fi
