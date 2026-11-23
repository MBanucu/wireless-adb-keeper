#!/data/data/com.termux/files/usr/bin/bash
# status.sh - the Status page as text: every documented field, the port state
# against an independent listening check, and two calls that agree.
#
# device-slow: 0
set -euo pipefail
if [ -z "${DEVICE_STATE:-}" ]; then
    echo "run through: bash tests/device.sh --only status" >&2
    exit 2
fi
# shellcheck disable=SC1091
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

first=$(keeper status)

# The unconditional fields of Keeper.status(): permission and location are
# separate because location needs Android 10+, which is the app's floor.
for key in \
    permission location wifi wireless service pinned_port mode job termux ssid sdk \
    ; do
    count=$(printf '%s\n' "$first" | grep -c "^$key=" || true)
    assert_eq "status has the '$key' field once" 1 "$count"
done
mode=$(printf '%s\n' "$first" | field mode)
if [ "$mode" = local-only ]; then
    count=$(printf '%s\n' "$first" | grep -c '^local_link=' || true)
    assert_eq "local-only status has the 'local_link' field once" 1 "$count"
fi

diag=$(keeper diag)
port=$(printf '%s\n' "$diag" | field port_5555)
status_port=$(printf '%s\n' "$first" | field pinned_port)
assert_eq "diag has a port_5555 field" 1 "$([ -n "$port" ] && echo 1 || echo 0)"
assert_eq "status pinned_port matches diag port_5555" "$port" "$status_port"

listening=0
if port_listening; then
    listening=1
fi
case $port in
    'listening on 5555'*)
        assert_eq "port_5555 says listening and 5555 accepts" 1 "$listening"
        ;;
    *)
        assert_eq "port_5555 says not listening and 5555 is closed" 0 "$listening"
        ;;
esac

second=$(keeper status)
# last_result is the last connect outcome and local_link is a live network
# state; both may legitimately change between two calls. Everything else is
# the saved configuration and must agree.
normalize() {
    grep -v -E '^(last_result|local_link)=' || true
}
diff_out=$(diff -u \
    <(printf '%s\n' "$first" | normalize) \
    <(printf '%s\n' "$second" | normalize) || true)
assert_eq "two status calls agree (ignoring last_result/local_link)" "" "$diff_out"
