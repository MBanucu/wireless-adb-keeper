# shellcheck shell=bash
# Shared adbd discovery for the scripts that look for it.
#
# Sourced, not executed. This file's directory is resolved from BASH_SOURCE
# rather than the cwd: the app runs pin-adb-port.sh with cwd $HOME, so a
# relative ./scan_ports.py would look in the wrong place.

DISCOVER_DIR=$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)

# The keeper app pins adbd here when it can (pin-adb-port.sh).
ADB_PIN_PORT=${ADB_PIN_PORT:-5555}

# The two services adbd advertises over mDNS. The pairing one exists only while
# the "Pair device with pairing code" dialog is open; pair-local.sh reads it.
DISCOVER_CONNECT_SERVICE=_adb-tls-connect._tcp.local.
# shellcheck disable=SC2034  # used by pair-local.sh after sourcing this file
DISCOVER_PAIRING_SERVICE=_adb-tls-pairing._tcp.local.

# adbd's random TLS port is a listening socket in Linux's ephemeral range.
# Read the kernel's value when it is readable; on Android SELinux denies Termux
# access to /proc/sys/net/ipv4/ip_local_port_range, so the documented Linux
# default is the normal path on the phone. PORT_START/PORT_END override both.
if [ -z "${PORT_START:-}" ] || [ -z "${PORT_END:-}" ]; then
    _discover_kstart=""
    _discover_kend=""
    { read -r _discover_kstart _discover_kend < /proc/sys/net/ipv4/ip_local_port_range; } 2>/dev/null || true
    PORT_START=${PORT_START:-${_discover_kstart:-32768}}
    PORT_END=${PORT_END:-${_discover_kend:-60999}}
    unset _discover_kstart _discover_kend
fi

# discover_serial: serial of the first attached device, if any.
discover_serial() {
    adb devices 2>/dev/null | awk 'NR > 1 && $2 == "device" { print $1; exit }'
}

# discover_mdns_available: the advertisement can be read directly.
discover_mdns_available() {
    [ -x "$DISCOVER_DIR/adb-mdns.py" ] &&
        python3 -c 'import zeroconf' >/dev/null 2>&1
}

# discover_mdns_service <service> [seconds]: ports for one advertised service.
discover_mdns_service() {
    local service="${1:-}" seconds="${2:-3}"
    [ -n "$service" ] || return 0
    discover_mdns_available || return 0
    "$DISCOVER_DIR/adb-mdns.py" "$seconds" "$service" 2>/dev/null
}

# discover_mdns [seconds]: ports adbd advertises for connecting, newest first.
discover_mdns() {
    discover_mdns_service "$DISCOVER_CONNECT_SERVICE" "${1:-3}"
}

# discover_proc <serial>: print listening ports from the phone's TCP tables.
#
# adbd's port is in the kernel's socket table already, so with a serial
# attached one `adb shell cat` round trip replaces tens of thousands of
# loopback connects. The tables are host-wide; any serial for this phone works.
# State 0A is LISTEN and the local port is the hex field after the address.
# Wildcard and LAN listeners are listed too: the caller still proves the port by
# connecting, so no address filter is needed.
discover_proc() {
    local serial="${1:-}" hex port
    [ -n "$serial" ] || return 0
    adb -s "$serial" shell 'cat /proc/net/tcp /proc/net/tcp6' 2>/dev/null |
        tr -d '\r' |
        while read -r _ local_addr _ state _; do
            [ "$state" = "0A" ] || continue
            hex=${local_addr##*:}
            port=$((16#$hex))
            [ "$port" -ge "$PORT_START" ] && [ "$port" -le "$PORT_END" ] || continue
            [ "$port" = "5037" ] || echo "$port"
        done | sort -n -u
}

# discover_scan: last resort, a loopback connect scan of the shared range.
discover_scan() {
    python3 "$DISCOVER_DIR/scan_ports.py" "$PORT_START" "$PORT_END"
}

# discover_ports: candidate ports without waiting for mDNS. Uses the phone's
# TCP table when an adb serial is attached, then falls back to the scan.
discover_ports() {
    local serial ports
    serial=$(discover_serial)
    if [ -n "$serial" ]; then
        ports=$(discover_proc "$serial")
        if [ -n "$ports" ]; then
            printf '%s\n' "$ports"
            return 0
        fi
    fi
    discover_scan
}

# discover_connect <port> [attempts]: connect and wait for "device".
# Returns 1 and drops the dead serial when the port never comes up.
discover_connect() {
    local port="${1:-}" attempts="${2:-4}" i state
    [ -n "$port" ] && [ "$port" != "5037" ] || return 1
    timeout 5 adb connect "127.0.0.1:$port" >/dev/null 2>&1
    i=0
    while [ "$i" -lt "$attempts" ]; do
        state=$(adb devices 2>/dev/null | awk -v a="127.0.0.1:$port" '$1 == a { print $2; exit }')
        [ "$state" = "device" ] && return 0
        i=$((i + 1))
        if [ "$i" -lt "$attempts" ]; then
            sleep 1
        fi
    done
    adb disconnect "127.0.0.1:$port" >/dev/null 2>&1
    return 1
}
