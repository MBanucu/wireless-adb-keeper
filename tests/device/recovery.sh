#!/data/data/com.termux/files/usr/bin/bash
# recovery.sh - `am force-stop`, then the control path the user has: the
# monitor is running, 5555 listens again within the documented window, and the
# journal has a new svc.start. The #39 reboot scenario is opt-in.
#
# device-slow: 0
set -euo pipefail
if [ -z "${DEVICE_STATE:-}" ]; then
    echo "run through: bash tests/device.sh --only recovery" >&2
    exit 2
fi
# shellcheck disable=SC1091
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

# The journal is a 200-line ring: a new svc.start can evict an old one, so a
# count can stay flat (or fall) across a recovery. Compare the newest marker.
last_start() {
    journal | grep ' svc\.start' | tail -n 1 || true
}

starts_before=$(last_start)
force_stop_app
assert_contains "force-stop stops the monitor" 'service=stopped' \
    "$(keeper status)"

# keeper.sh connect is the documented recovery command: it makes a provider
# call, and if the service is gone the wrapper starts it explicitly.
keeper connect >/dev/null || true

running=0
listening=0
deadline=$(( $(date +%s) + 30 ))
while [ "$(date +%s)" -lt "$deadline" ]; do
    if [ "$(keeper status | field service)" = running ]; then
        running=1
    fi
    if port_listening; then
        listening=1
    fi
    if [ "$running" -eq 1 ] && [ "$listening" -eq 1 ]; then
        break
    fi
    sleep 2
done
assert_eq "the monitor service is running after force-stop" 1 "$running"
assert_eq "5555 listens within 30s after force-stop" 1 "$listening"

starts_after=$(last_start)
assert_eq "the journal has a new svc.start" 1 \
    "$([ -n "$starts_after" ] && [ "$starts_after" != "$starts_before" ] \
        && echo 1 || echo 0)"

if [ "${DEVICE_ALLOW_REBOOT:-0}" != 1 ]; then
    skip "reboot recovery (#39)" "pass --allow-reboot to opt in"
    exit 0
fi

wait_after_reboot() {
    local deadline=$(( $(date +%s) + 180 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        case ${PHONE_SERIAL:-} in
            *:*)
                if [ "$(dev_adb get-state 2>/dev/null || true)" != device ]; then
                    "$ADB" connect "$PHONE_SERIAL" >/dev/null 2>&1 || true
                fi
                ;;
            *)
                if ! dev_adb shell true >/dev/null 2>&1; then
                    "$ADB" reconnect >/dev/null 2>&1 || true
                fi
                ;;
        esac
        if [ "$(dev_adb get-state 2>/dev/null || true)" = device ] \
            && [ "$(dev_adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; then
            return 0
        fi
        sleep 3
    done
    return 1
}

starts_before=$(last_start)
dev_adb reboot
assert_eq "the device came back after reboot" 1 \
    "$(wait_after_reboot && echo 1 || echo 0)"

running=0
listening=0
deadline=$(( $(date +%s) + 120 ))
while [ "$(date +%s)" -lt "$deadline" ]; do
    status=$(keeper status 2>/dev/null || true)
    if [ "$(printf '%s\n' "$status" | field service)" = running ]; then
        running=1
    fi
    if port_listening; then
        listening=1
    fi
    if [ "$running" -eq 1 ] && [ "$listening" -eq 1 ]; then
        break
    fi
    sleep 3
done
assert_eq "the monitor service starts after reboot" 1 "$running"
assert_eq "5555 listens within 120s after reboot" 1 "$listening"
starts_after=$(last_start)
assert_eq "the boot path journals a new svc.start" 1 \
    "$([ -n "$starts_after" ] && [ "$starts_after" != "$starts_before" ] \
        && echo 1 || echo 0)"
