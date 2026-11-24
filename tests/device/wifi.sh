#!/data/data/com.termux/files/usr/bin/bash
# wifi.sh - opt-in real-network check: give the saved profile its password,
# prove auto-connect registers it, and prove the secret never reaches the
# journal, SharedPreferences, the profile list or the diag report.
#
# The SSID and passphrase come from DEVICE_WIFI_SSID (default WP22) and
# DEVICE_WIFI_PASSPHRASE. The passphrase is never written by this file; the
# runner snapshots and restores the whole preference file, and the cleanup trap
# also clears the test password and restores the mode.
#
# device-slow: 0
set -euo pipefail
if [ -z "${DEVICE_STATE:-}" ]; then
    echo "run through: bash tests/device.sh --only wifi" >&2
    exit 2
fi
# shellcheck disable=SC1091
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

pass=${DEVICE_WIFI_PASSPHRASE:-}
ssid=${DEVICE_WIFI_SSID:-WP22}
if [ -z "$pass" ]; then
    echo "skip wifi: set DEVICE_WIFI_PASSPHRASE (optional DEVICE_WIFI_SSID) to run" >&2
    exit 3
fi
case $ssid in
    *[!A-Za-z0-9._-]*)
        echo "skip wifi: DEVICE_WIFI_SSID must be a plain SSID (letters, digits, . _ -)" >&2
        exit 3
        ;;
esac
case $pass in
    *[!A-Za-z0-9._-]*)
        echo "skip wifi: DEVICE_WIFI_PASSPHRASE must be letters, digits, . _ or - so it can be passed as one shell argument" >&2
        exit 3
        ;;
esac

status=$(keeper status)
saved_ssid=$(printf '%s\n' "$status" | field ssid)
mode=$(printf '%s\n' "$status" | field mode)
case $mode in
    auto-connect) restore_mode=auto ;;
    local-only)   restore_mode=local ;;
    *)
        echo "cannot read the current mode: <$mode>" >&2
        exit 1
        ;;
esac
if [ "$saved_ssid" != "$ssid" ]; then
    echo "skip wifi: the saved SSID is <$saved_ssid>, not <$ssid>" >&2
    exit 3
fi

# A provider call starts the app but not its foreground service. The redaction
# suite force-stops the app while removing its temp profile, so by the time this
# suite runs the monitor can be stopped; a revision bump is then never observed
# and the suggestion is never added. Start it here, before changing anything.
if [ "$(keeper status | field service)" != running ]; then
    keeper start >/dev/null 2>&1 || true
    if ! wait_for_service 20; then
        echo "cannot run wifi: the monitor service did not start" >&2
        exit 1
    fi
fi

profiles=$(keeper profiles)
if printf '%s\n' "$profiles" | grep -F "ssid=$ssid " | grep -q 'passphrase=set'; then
    echo "skip wifi: $ssid already has a stored passphrase; use a profile without one" >&2
    exit 3
fi
profile_line=$(printf '%s\n' "$profiles" | grep -F "ssid=$ssid " | head -n 1)
profile_id=$(printf '%s\n' "$profile_line" | sed -n 's/^id=\([^ ]*\).*/\1/p')
if [ -z "$profile_id" ]; then
    echo "skip wifi: could not find the id for $ssid" >&2
    exit 3
fi

# The CLI takes a passphrase as an argument and the test log records commands;
# use the provider directly with a redacted command label instead. SSID and
# passphrase are restricted to shell-safe bytes above.
save_wifi_secret() {
    local id_ssid=$1 secret=$2
    _last "content call --uri $AUTH --method save_wifi --arg $id_ssid --extra passphrase:<redacted>"
    dev_adb shell content call --uri "$AUTH" --method save_wifi \
        --arg "$id_ssid" --extra "passphrase:s:$secret"
}

cleanup() {
    # Leave local mode first so auto-connect removes any suggestion, then clear
    # the password and put the original mode back.
    keeper mode local >/dev/null 2>&1 || true
    save_wifi_secret "$ssid" "" >/dev/null 2>&1 || true
    keeper mode "$restore_mode" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# A failure must report that the secret was found without printing it.
assert_no_secret() {
    local what=$1 text=$2
    if printf '%s' "$text" | grep -qF -- "$pass"; then
        printf 'FAIL %s\n' "$what"
        printf '  actual: the configured passphrase appears in the output (value redacted)\n'
        exit 1
    fi
    ok_line "$what"
}

reply=$(save_wifi_secret "$ssid" "$pass")
assert_contains "the real profile stores the passphrase" 'passphrase=set' "$reply"
assert_eq "the preference file has a passphrase" present "$(passphrase_present)"
profiles=$(keeper profiles)
assert_contains "the profile list names the saved SSID" "ssid=$ssid" "$profiles"
assert_no_secret "the passphrase is absent from the profile list" "$profiles"
assert_no_secret "the passphrase is absent from SharedPreferences" "$(prefs)"

keeper mode auto >/dev/null
deadline=$(( $(date +%s) + 20 ))
last_result=
while :; do
    if ! status=$(keeper status); then
        echo "cannot read keeper status while waiting for the suggestion" >&2
        exit 1
    fi
    last_result=$(printf '%s\n' "$status" | field last_result)
    case $last_result in
        *"suggestion $profile_id: active"*) break ;;
    esac
    if [ "$(date +%s)" -ge "$deadline" ]; then
        break
    fi
    sleep 1
done
assert_contains "auto-connect registers the real SSID" \
    "suggestion $profile_id: active" "$last_result"
assert_no_secret "the passphrase is absent from the diag report" "$(keeper diag)"

cleanup
trap - EXIT
assert_eq "the previous mode is back" "$mode" "$(keeper status | field mode)"
assert_eq "the saved passphrase is absent again" absent "$(passphrase_present)"
