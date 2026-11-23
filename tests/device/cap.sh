#!/data/data/com.termux/files/usr/bin/bash
# cap.sh - flap the mode until the 200-event ring is full, then assert exactly
# 200 lines and that the newest event survived. Slow on purpose: 210 provider
# calls plus the monitor's reloads.
#
# device-slow: 1
set -euo pipefail
if [ -z "${DEVICE_STATE:-}" ]; then
    echo "run through: bash tests/device.sh --slow --only cap" >&2
    exit 2
fi
# shellcheck disable=SC1091
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

prev_mode=$(keeper status | field mode)
case $prev_mode in
    auto-connect) restore_mode=auto ;;
    local-only)   restore_mode=local ;;
    *)
        echo "cannot read the current mode: <$prev_mode>" >&2
        exit 1
        ;;
esac

if [ "$(keeper status | field service)" != running ]; then
    keeper start >/dev/null
fi
assert_eq "the monitor service is running" 1 \
    "$(wait_for_service 20 && echo 1 || echo 0)"

# 210 mode writes guarantee more than the 200-event cap. The reload is
# asynchronous, so the assertions below wait for the ring rather than assuming
# it is full the instant the loop returns.
for i in $(seq 1 210); do
    if [ $((i % 2)) -eq 1 ]; then
        keeper mode local >/dev/null
    else
        keeper mode auto >/dev/null
    fi
done

deadline=$(( $(date +%s) + 30 ))
while [ "$(date +%s)" -lt "$deadline" ]; do
    lines=$(journal | wc -l | tr -d ' ')
    newest=$(journal | tail -n 1)
    if [ "$lines" -ge 200 ] \
        && printf '%s' "$newest" | grep -q 'svc\.mode' \
        && printf '%s' "$newest" | grep -q 'auto-connect'; then
        break
    fi
    sleep 1
done
lines=$(journal | wc -l | tr -d ' ')
newest=$(journal | tail -n 1)
assert_eq "the journal is capped at exactly 200 lines" 200 "$lines"
assert_contains "the newest journal event is a mode change" 'svc.mode' "$newest"
assert_contains "the newest mode change is the last one written" 'auto-connect' "$newest"

keeper mode "$restore_mode" >/dev/null
assert_eq "the previous mode is back" "$prev_mode" "$(keeper status | field mode)"
