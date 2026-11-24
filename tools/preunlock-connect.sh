#!/usr/bin/env bash
# Attach this host to adbd before the phone is unlocked.
#
# Wireless debugging's TLS listener answers while the phone is still
# RUNNING_LOCKED: adbd starts at boot, and a host key adbd already trusts
# reaches `adb shell` without the keyguard. The port is random per adbd start
# and the mDNS record can outlive it, so every advertised candidate is proved
# by a TCP connect and then by `adb connect` reporting "device".
#
# This runs on the laptop, not in Termux: `adb mdns services` is unsupported in
# common adb builds, so discovery uses avahi-browse, then adb-harness's
# adb-connect (the shared helper in discover.sh), then the range scan from
# scan_ports.py. The existing ~/.android/adbkey is the only credential; pair the
# host with the phone once (see docs/preunlock-connect.md).
#
# Usage:
#   tools/preunlock-connect.sh [--host ADDR] [--tcpip [PORT]] [--seconds N]
#                              [--no-scan]
#   PREUNLOCK_HOST=192.168.77.232 tools/preunlock-connect.sh --tcpip 5555
#
#   --host ADDR     phone address; default: an attached adb serial, the
#                   USB-attached phone's wlan0 address, or the mDNS record
#   --tcpip [PORT]  after connecting, send `adb tcpip PORT` (default
#                   ADB_PIN_PORT, 5555) and reconnect on it
#   --seconds N     mDNS timeout (default MDNS_SECONDS, 3)
#   --no-scan       skip the 32768-60999 fallback scan
#
# Env: PREUNLOCK_HOST, ADB_PIN_PORT, MDNS_SECONDS, PORT_START, PORT_END,
#      AVAHI_BROWSE, ADB_CONNECT (an empty AVAHI_BROWSE/ADB_CONNECT disables
#      that source).
# Exit: 0 connected (and, with --tcpip, listening on that port).
# The last stdout line is machine readable, like the Termux scripts:
#   RESULT connected=192.168.77.232:33801
#   RESULT connected=192.168.77.232:5555 tcpip=5555
#   RESULT error=no-host | RESULT error=no-adbd | RESULT error=no-adb
#   RESULT error=tcpip-refused tcpip=5555 | RESULT error=port-closed port=5555

set -u
SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd) || exit 1
ROOT=$(CDPATH='' cd -- "$SCRIPT_DIR/.." && pwd) || exit 1
# shellcheck source-path=SCRIPTDIR/.. source=discover.sh
. "$ROOT/discover.sh" || exit 1

MDNS_SECONDS=${MDNS_SECONDS:-3}
EXPLICIT_HOST=${PREUNLOCK_HOST:-}
TCPIP_PORT=
DO_SCAN=1
TRIED=" "
CONNECTED=
CANDIDATES=0

say() {
    printf '%s\n' "$*"
}

warn() {
    printf '%s\n' "$*" >&2
}

fail() {
    local code=$1 message=$2
    shift 2
    warn "preunlock-connect: $message"
    printf 'RESULT error=%s' "$code"
    [ "$#" -eq 0 ] || printf ' %s' "$*"
    printf '\n'
    exit 1
}

is_port() {
    case "${1:-}" in
        '' | *[!0-9]*) return 1 ;;
    esac
    [ "$1" -ge 1 ] && [ "$1" -le 65535 ]
}

usage() {
    cat <<'USAGE'
Usage: tools/preunlock-connect.sh [--host ADDR] [--tcpip [PORT]]
                                  [--seconds N] [--no-scan]

  --host ADDR     phone address; default: an attached adb serial, the
                  USB-attached phone's wlan0 address, or the mDNS record
  --tcpip [PORT]  after connecting, send `adb tcpip PORT` (default
                  ADB_PIN_PORT, 5555) and reconnect on it
  --seconds N     mDNS timeout for adb-connect (default MDNS_SECONDS, 3)
  --no-scan       skip the 32768-60999 fallback scan

Env: PREUNLOCK_HOST, ADB_PIN_PORT, MDNS_SECONDS, PORT_START, PORT_END,
     AVAHI_BROWSE, ADB_CONNECT (an empty AVAHI_BROWSE/ADB_CONNECT disables
     that source).
USAGE
    exit 0
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --host)
            [ "$#" -ge 2 ] || fail usage "usage: $0 [--host ADDR] [--tcpip [PORT]] [--seconds N] [--no-scan]"
            EXPLICIT_HOST=$2
            shift 2
            ;;
        --host=*)
            EXPLICIT_HOST=${1#*=}
            shift
            ;;
        --tcpip)
            TCPIP_PORT=$ADB_PIN_PORT
            if [ "$#" -ge 2 ] && is_port "$2"; then
                TCPIP_PORT=$2
                shift
            fi
            shift
            ;;
        --tcpip=*)
            TCPIP_PORT=${1#*=}
            is_port "$TCPIP_PORT" ||
                fail usage "--tcpip wants a port in 1-65535, got '$TCPIP_PORT'"
            shift
            ;;
        --seconds)
            [ "$#" -ge 2 ] || fail usage "--seconds wants a number"
            MDNS_SECONDS=$2
            shift 2
            ;;
        --seconds=*)
            MDNS_SECONDS=${1#*=}
            shift
            ;;
        --no-scan)
            DO_SCAN=0
            shift
            ;;
        -h | --help)
            usage
            ;;
        *)
            fail usage "unknown argument '$1' (see --help)"
            ;;
    esac
done

case "$MDNS_SECONDS" in
    '' | *[!0-9]*)
        fail usage "--seconds wants a number, got '$MDNS_SECONDS'"
        ;;
esac
[ -z "$TCPIP_PORT" ] || is_port "$TCPIP_PORT" ||
    fail usage "--tcpip wants a port in 1-65535, got '$TCPIP_PORT'"

# avahi-browse speaks parsable mDNS on the host; adb-harness is the shared
# fallback, mirroring discover.sh's ADB_CONNECT resolution.
avahi_browse_command() {
    if [ "${AVAHI_BROWSE+set}" = set ]; then
        case "${AVAHI_BROWSE:-}" in
            '') return 1 ;;
            */*)
                [ -x "$AVAHI_BROWSE" ] || return 1
                printf '%s\n' "$AVAHI_BROWSE"
                ;;
            *)
                command -v "$AVAHI_BROWSE" 2>/dev/null || return 1
                ;;
        esac
    else
        command -v avahi-browse 2>/dev/null || return 1
    fi
}

# The _adb-tls-connect._tcp name avahi registers (it appends .local itself).
mdns_service() {
    printf '%s\n' "${DISCOVER_CONNECT_SERVICE%.local.}"
}

# known_hosts: addresses the phone is known to answer on, best first. An
# explicit --host wins; otherwise an attached network serial gives the address
# without a round trip, and a USB serial asks wlan0 (read-only).
known_hosts() {
    local serial
    if [ -n "$EXPLICIT_HOST" ]; then
        printf '%s\n' "$EXPLICIT_HOST"
        return 0
    fi
    adb devices 2>/dev/null |
        awk 'NR > 1 && $2 == "device" && $1 ~ /:[0-9]+$/ { print $1 }' |
        while IFS= read -r serial; do
            printf '%s\n' "${serial%:*}"
        done
    adb devices 2>/dev/null |
        awk 'NR > 1 && $2 == "device" && $1 !~ /:/ { print $1 }' |
        while IFS= read -r serial; do
            adb -s "$serial" shell ip -4 addr show wlan0 2>/dev/null |
                tr -d '\r' |
                sed -n 's/.*inet \([0-9][0-9.]*\)\/.*/\1/p'
        done
}

# avahi_candidates: "address port" for each resolved IPv4 record. IPv6 is
# skipped: the host connects over the phone's IPv4 Wi-Fi address.
avahi_candidates() {
    local cmd out
    cmd=$(avahi_browse_command) || return 0
    out=$(timeout 10 "$cmd" -rtp "$(mdns_service)" 2>/dev/null) || true
    [ -n "$out" ] || return 0
    printf '%s\n' "$out" |
        awk -F';' '$1 == "=" && $3 == "IPv4" && $8 != "" && $9 ~ /^[0-9]+$/ {
            print $8, $9
        }'
}

# keep_known: drop records for an address that is not this phone. With no
# known address (mDNS is the only source) every record is a candidate.
keep_known() {
    local addr port host known=
    while read -r addr port; do
        [ -n "$addr" ] || continue
        if [ -z "$hosts" ]; then
            printf '%s %s\n' "$addr" "$port"
            continue
        fi
        known=
        for host in $hosts; do
            [ "$addr" = "$host" ] && known=1
        done
        [ -n "$known" ] && printf '%s %s\n' "$addr" "$port"
    done
}

# harness_candidates: ports adb-connect saw, paired with a known address.
# Its success requires the same key adbd stores, so a stale record is already
# filtered out; the caller still proves the port.
harness_candidates() {
    local ports port host
    discover_mdns_available || return 0
    ports=$(discover_mdns_service "$DISCOVER_CONNECT_SERVICE" "$MDNS_SECONDS") || return 0
    # adb-connect attaches the phone itself, so its serial now names the
    # address even when nothing was attached before.
    if [ -z "$hosts" ]; then
        hosts=$(known_hosts | awk 'NF && !seen[$0]++')
    fi
    for port in $ports; do
        for host in $hosts; do
            printf '%s %s\n' "$host" "$port"
        done
    done
}

device_state() {
    adb devices 2>/dev/null | awk -v s="$1" '$1 == s { print $2; exit }'
}

# tcp_probe: a stale advertisement is dropped here rather than by a slow
# `adb connect` timeout.
tcp_probe() {
    local host=$1 port=$2
    # shellcheck disable=SC2016  # expanded by the inner bash, not here
    timeout 3 bash -c 'exec 3<>"/dev/tcp/$1/$2"' _ "$host" "$port" 2>/dev/null
}

# attempt <host> <port>: prove the port, attach with the existing host key, and
# leave CONNECTED set once adb reports the serial as "device".
attempt() {
    local host=$1 port=$2
    is_port "$port" || return 1
    [ "$port" = 5037 ] && return 1
    case "$TRIED" in
        *" $host:$port "*) return 1 ;;
    esac
    TRIED="$TRIED$host:$port "
    if ! tcp_probe "$host" "$port"; then
        say "-- $host:$port: closed, skipping"
        return 1
    fi
    say "-- $host:$port: open, connecting"
    # A serial the server still remembers as offline can eat the first
    # connect; drop it and retry rather than declaring the port stale.
    adb disconnect "$host:$port" >/dev/null 2>&1
    for _ in 1 2 3 4 5; do
        timeout 5 adb connect "$host:$port" >/dev/null 2>&1
        if [ "$(device_state "$host:$port")" = device ]; then
            CONNECTED="$host:$port"
            return 0
        fi
        sleep 1
    done
    adb disconnect "$host:$port" >/dev/null 2>&1
    say "   adb did not attach (stale port, or the host key is not paired)"
    return 1
}

command -v adb >/dev/null 2>&1 ||
    fail no-adb "adb not found on PATH; install android-tools/platform-tools"

hosts=$(known_hosts | awk 'NF && !seen[$0]++')
[ -n "$hosts" ] && say "== phone address: $(printf '%s' "$hosts" | tr '\n' ' ')=="

# 1. The pinned port, if this boot already has it (it is not pinned before
#    unlock, so this is usually a quick refused connect).
for host in $hosts; do
    attempt "$host" "$ADB_PIN_PORT" && break
done

# 2. mDNS via avahi-browse: the host-native browser, address included.
if [ -z "$CONNECTED" ]; then
    say "== looking for $(mdns_service) via avahi-browse =="
    while read -r host port; do
        CANDIDATES=$((CANDIDATES + 1))
        attempt "$host" "$port" && break
    done < <(avahi_candidates | keep_known)
fi

# 3. adb-harness's adb-connect, the same helper discover.sh uses.
if [ -z "$CONNECTED" ]; then
    say "== looking for $(mdns_service) via adb-connect =="
    while read -r host port; do
        CANDIDATES=$((CANDIDATES + 1))
        attempt "$host" "$port" && break
    done < <(harness_candidates)
fi

# 4. The shared range scan, last resort: mDNS unavailable, or every record
#    stale.
if [ -z "$CONNECTED" ] && [ "$DO_SCAN" = 1 ] && [ -n "$hosts" ]; then
    say "== scanning $PORT_START-$PORT_END on the phone =="
    for host in $hosts; do
        while read -r port; do
            attempt "$host" "$port" && break
        done < <(python3 "$ROOT/scan_ports.py" "$PORT_START" "$PORT_END" "$host" |
            awk '{ for (i = 1; i <= NF; i++) print $i }')
        [ -n "$CONNECTED" ] && break
    done
fi

if [ -z "$CONNECTED" ]; then
    if [ -z "$hosts" ] && [ "$CANDIDATES" -eq 0 ]; then
        fail no-host "no mDNS record and no phone address; pass --host <phone-ip> or attach the phone over USB once"
    fi
    fail no-adbd "nothing accepted an adb connection; is wireless debugging on, the phone on this network, and this host's key paired?"
fi

say "connected: $CONNECTED"

if [ -n "$TCPIP_PORT" ]; then
    say "== adb -s $CONNECTED tcpip $TCPIP_PORT =="
    if ! timeout 10 adb -s "$CONNECTED" tcpip "$TCPIP_PORT" >/dev/null 2>&1; then
        adb disconnect "$CONNECTED" >/dev/null 2>&1
        fail tcpip-refused "adbd refused tcpip $TCPIP_PORT" "tcpip=$TCPIP_PORT"
    fi
    host=${CONNECTED%:*}
    adb disconnect "$CONNECTED" >/dev/null 2>&1
    target="$host:$TCPIP_PORT"
    # A serial adb remembers as offline can make `adb connect` a no-op. Clear
    # the pinned serial too, as attempt() does, before the retry loop.
    adb disconnect "$target" >/dev/null 2>&1
    for _ in 1 2 3 4 5; do
        [ "$(device_state "$target")" = device ] && break
        timeout 5 adb connect "$target" >/dev/null 2>&1
        sleep 1
    done
    if [ "$(device_state "$target")" != device ]; then
        fail port-closed "$target did not come up after tcpip $TCPIP_PORT" "port=$TCPIP_PORT"
    fi
    CONNECTED=$target
    say "connected: $CONNECTED"
fi

# The issue's own proof: adbd answers the shell uid even before unlock. It is
# reported, not required: a locked userspace can still deny the shell service.
proof=$(timeout 10 adb -s "$CONNECTED" shell id 2>/dev/null | tr -d '\r' | head -n 1)
case "$proof" in
    *"uid=2000(shell)"*) say "shell: $proof" ;;
    '') ;;
    *) say "shell: $proof (not the shell uid)" ;;
esac

if [ -n "$TCPIP_PORT" ]; then
    printf 'RESULT connected=%s tcpip=%s\n' "$CONNECTED" "$TCPIP_PORT"
else
    printf 'RESULT connected=%s\n' "$CONNECTED"
fi
exit 0
