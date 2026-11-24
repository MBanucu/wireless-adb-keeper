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

# mDNS discovery is `adb-connect` from adb-harness (pinned in
# tools/requirements.txt); the local adb-mdns.py fork was retired in #68.
# Resolution order: ADB_CONNECT when the caller sets it (an empty value
# disables mDNS), then the pinned venv beside this script, then PATH.
_DISCOVER_ADB_CONNECT=
_DISCOVER_ADB_CONNECT_RESOLVED=0

discover_mdns_command() {
    if [ "$_DISCOVER_ADB_CONNECT_RESOLVED" = 0 ]; then
        _DISCOVER_ADB_CONNECT_RESOLVED=1
        _DISCOVER_ADB_CONNECT=
        if [ "${ADB_CONNECT+set}" = set ]; then
            case "${ADB_CONNECT:-}" in
                '') ;;
                */*)
                    [ -x "$ADB_CONNECT" ] && _DISCOVER_ADB_CONNECT=$ADB_CONNECT
                    ;;
                *)
                    _DISCOVER_ADB_CONNECT=$(command -v "$ADB_CONNECT" 2>/dev/null || true)
                    ;;
            esac
        else
            for candidate in "$DISCOVER_DIR/tools/.venv/bin/adb-connect" \
                    "$DISCOVER_DIR/.venv/bin/adb-connect"; do
                if [ -x "$candidate" ]; then
                    _DISCOVER_ADB_CONNECT=$candidate
                    break
                fi
            done
            if [ -z "$_DISCOVER_ADB_CONNECT" ]; then
                _DISCOVER_ADB_CONNECT=$(command -v adb-connect 2>/dev/null || true)
            fi
        fi
    fi
    printf '%s\n' "$_DISCOVER_ADB_CONNECT"
}

# discover_serial: serial of the first attached device, if any.
discover_serial() {
    adb devices 2>/dev/null | awk 'NR > 1 && $2 == "device" { print $1; exit }'
}

# discover_mdns_available: the adb-harness browser can be run.
discover_mdns_available() {
    local cmd
    cmd=$(discover_mdns_command)
    [ -n "$cmd" ] && [ -x "$cmd" ]
}

# discover_mdns_version: what the browser is, and which exit-code contract it
# speaks. PyPI 0.2.3 predates the documented 0/1/2/3 table and only exits 0 on
# success and 1 on a failure; say so instead of pretending the codes differ.
discover_mdns_version() {
    local cmd out
    cmd=$(discover_mdns_command)
    if [ -z "$cmd" ]; then
        printf 'none (mDNS disabled)\n'
        return 0
    fi
    if out=$("$cmd" --version 2>/dev/null) && [ -n "$out" ]; then
        printf '%s (0/1/2/3 exit codes)\n' "$out"
    else
        printf '%s (predates --version: only 0/1, 1 means no advertisement)\n' "$cmd"
    fi
}

# discover_mdns_service <service> [seconds]: ports for one advertised service.
# Returns 0 with ports, 1 when nothing is advertised, 3 when the browser or a
# prerequisite is missing. The 3 is what lets callers tell "wireless debugging
# is off" from "adb-harness is not installed"; older adb-harness releases have
# no exit 3, so their stderr is checked for the same two prerequisites.
discover_mdns_service() {
    local service="${1:-}" seconds="${2:-3}" cmd out rc ports pairing=0
    [ -n "$service" ] || return 1
    discover_mdns_available || return 3
    cmd=$(discover_mdns_command)
    case "$service" in
        "$DISCOVER_PAIRING_SERVICE") pairing=1 ;;
    esac
    if [ "$pairing" = 1 ]; then
        out=$("$cmd" --pair --timeout "$seconds" 2>&1)
    else
        out=$("$cmd" --timeout "$seconds" 2>&1)
    fi
    rc=$?
    case "$rc" in
        0) ;;
        1)
            # New contract: nothing advertised or nothing answered. Older
            # releases also use 1 for a missing prerequisite; their message is
            # the only way to tell, so every caller falls back to the scan.
            case "$out" in
                *"zeroconf is missing"*|*"adb is not in PATH"*) return 3 ;;
            esac
            return 1
            ;;
        2) return 3 ;;  # usage error: a flag the installed release does not know
        3) return 3 ;;  # documented prerequisite: no adb or no python-zeroconf
        *) return 3 ;;  # anything else is unusable, so fall back rather than hang
    esac
    if [ "$pairing" = 1 ]; then
        ports=$(printf '%s\n' "$out" |
            sed -n 's/^adb pair .*:\([0-9][0-9]*\)$/\1/p')
    else
        ports=$(printf '%s\n' "$out" | tr ',' '\n' | sed -n \
            -e 's/^connected to .*:\([0-9][0-9]*\)$/\1/p' \
            -e 's/^already connected: .*:\([0-9][0-9]*\).*$/\1/p')
    fi
    [ -n "$ports" ] || return 1
    printf '%s\n' "$ports" | awk '!seen[$0]++'
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
