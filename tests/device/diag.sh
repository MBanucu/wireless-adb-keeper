#!/data/data/com.termux/files/usr/bin/bash
# diag.sh - the journal format, service ordering, both readers, and survival
# across `am force-stop`.
#
# device-slow: 0
set -euo pipefail
if [ -z "${DEVICE_STATE:-}" ]; then
    echo "run through: bash tests/device.sh --only diag" >&2
    exit 2
fi
# shellcheck disable=SC1091
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

# A service start is the only way a cold app writes svc.start and svc.mode.
# Force one, so the ordering check is not defeated by the ring having trimmed
# the service start from an earlier suite (cap.sh runs first under --slow).
force_stop_app
keeper start >/dev/null
assert_eq "the monitor service is running" 1 \
    "$(wait_for_service 20 && echo 1 || echo 0)"
sleep 1

journal_text=$(journal)
assert_eq "the journal is not empty" 1 \
    "$([ -n "$journal_text" ] && echo 1 || echo 0)"
bad=$(printf '%s\n' "$journal_text" | grep -Ev \
    '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z [0-9]+s (info|warn|error) [a-z0-9_]+(\.[a-z0-9_]+)+( .*)?$' \
    || true)
assert_eq "every journal line is 'time uptime level code detail'" "" "$bad"

# The last service start must be followed by its mode, not the mode from an
# earlier process. awk keeps one flag across the whole journal.
ordered=$(printf '%s\n' "$journal_text" | awk '
    / svc\.start/ { seen = 1; after = 0; next }
    / svc\.mode/ && seen { after = 1 }
    END { exit(after ? 0 : 1) }
' && echo yes || echo no)
assert_eq "svc.start precedes svc.mode" yes "$ordered"

wrapped=$(keeper diag)
direct=$(content_call --method diag | strip_envelope)
# The snapshot has live values: an uptime tick, a port check or a journal size
# can change between two consecutive readers. The configuration and
# permission fields must not.
normalize_diag() {
    grep -v -E '^(uptime_s|port_5555|pin_result|wifi_result|last_result|journal_events|journal_bytes)=' \
        || true
}
diff_out=$(diff -u \
    <(printf '%s\n' "$wrapped" | normalize_diag) \
    <(printf '%s\n' "$direct" | normalize_diag) || true)
assert_eq "keeper.sh diag and --method diag agree" "" "$diff_out"

before=$(journal)
before_count=$(printf '%s\n' "$before" | grep -c . || true)
last_line=$(printf '%s\n' "$before" | tail -n 1)
service_before=$(keeper status | field service)
force_stop_app
assert_contains "force-stop leaves the service stopped" 'service=stopped' \
    "$(keeper status)"
after=$(journal)
after_count=$(printf '%s\n' "$after" | grep -c . || true)
assert_contains "the journal survives am force-stop" "$last_line" "$after"
assert_eq "am force-stop did not lose journal lines" 1 \
    "$([ "$after_count" -ge "$before_count" ] && echo 1 || echo 0)"
if [ "$service_before" = running ]; then
    keeper start >/dev/null
    assert_eq "the monitor service came back after force-stop" 1 \
        "$(wait_for_service 20 && echo 1 || echo 0)"
fi
