#!/data/data/com.termux/files/usr/bin/bash
# redaction.sh - save a temp profile whose passphrase is a unique canary, then
# prove the canary is in neither the journal nor SharedPreferences, and delete
# the profile again.
#
# device-slow: 0
set -euo pipefail
if [ -z "${DEVICE_STATE:-}" ]; then
    echo "run through: bash tests/device.sh --only redaction" >&2
    exit 2
fi
# shellcheck disable=SC1091
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

ssid=$(temp_ssid)
canary="canary-${DEVICE_PID}-$(date +%s)-${RANDOM}"
reply=$(temp_profile "$ssid" "$canary")
assert_contains "the temp profile was saved" "ssid=$ssid" "$reply"
assert_contains "the temp profile has a stored passphrase" 'passphrase=set' "$reply"

# A running monitor reloads on the revision bump and may add a suggestion;
# either way the secret is already in the Keystore-backed prefs.
keeper mode auto >/dev/null || true
sleep 1

journal_text=$(journal)
all_prefs=$(prefs)
diag_text=$(keeper diag)

assert_not_contains "the canary is absent from the journal" "$canary" "$journal_text"
assert_count "the journal contains the canary zero times" 0 "$canary" "$journal_text"
assert_not_contains "the canary is absent from SharedPreferences" "$canary" "$all_prefs"
assert_count "SharedPreferences contains the canary zero times" 0 "$canary" "$all_prefs"
assert_not_contains "the canary is absent from the diag reply" "$canary" "$diag_text"

# Delete it now, not only in the runner's trap, so this suite also checks its
# own cleanup and the next assertion sees the original SSID.
drop_temp_profile
assert_eq "the temp profile is gone from status" \
    "$(cat "$DEVICE_STATE/ssid")" "$(keeper status | field ssid)"
assert_count "the canary is gone from SharedPreferences after cleanup" 0 \
    "$canary" "$(prefs)"
assert_count "the canary is gone from the journal after cleanup" 0 \
    "$canary" "$(journal)"
