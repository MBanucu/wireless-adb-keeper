#!/data/data/com.termux/files/usr/bin/bash
# Pair this phone's own wireless debugging from Termux, over loopback.
#
# This is the bootstrap for a phone that has never been paired with a laptop:
# adbd's pairing service listens on the phone's own interfaces, so `adb pair`
# against 127.0.0.1 uses the adb client and the ~/.android/adbkey Termux already
# has. Afterwards the connect port is discovered with the shared helper and
# `adb shell id` must report uid=2000(shell): adbd only answers a key it stored.
#
# Usage:
#   ./pair-local.sh <6-digit-code>
#   ./pair-local.sh <6-digit-code> <pairing-port>
#   PAIR_CODE=<6-digit-code> ADB_PAIR_PORT=<pairing-port> ./pair-local.sh
#
# The code and the port are on the "Pair device with pairing code" dialog, which
# must stay open. Its `_adb-tls-pairing._tcp` advertisement does not outlive it.
# When mDNS cannot see that advertisement the port has to be typed; this script
# never scans for or guesses a pairing port.
#
# Exit: 0 paired, connected, and `adb shell id` reports the shell uid.

set -u
SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd) || exit 1
cd "$SCRIPT_DIR" || exit 1
# shellcheck source=discover.sh
. "$SCRIPT_DIR/discover.sh" || exit 1

MDNS_SECONDS=${MDNS_SECONDS:-3}

die() {
    echo "pair-local: $*" >&2
    exit 1
}

# A pasted code may carry spaces; the dialog itself prints six digits.
CODE=$(printf '%s' "${1:-${PAIR_CODE:-}}" | tr -d '[:space:]')
PAIR_PORT=${2:-${ADB_PAIR_PORT:-}}

case "$CODE" in
    [0-9][0-9][0-9][0-9][0-9][0-9]) ;;
    *)
        die "usage: $0 <6-digit-code> [pairing-port]  (or PAIR_CODE=<code> ADB_PAIR_PORT=<port> $0)"
        ;;
esac

command -v adb >/dev/null 2>&1 ||
    die "adb not found. Install it with: pkg install android-tools"

if [ -n "$PAIR_PORT" ]; then
    case "$PAIR_PORT" in
        *[!0-9]*)
            die "invalid pairing port '$PAIR_PORT' (expected 1-65535)"
            ;;
    esac
    if [ "$PAIR_PORT" -lt 1 ] || [ "$PAIR_PORT" -gt 65535 ]; then
        die "invalid pairing port '$PAIR_PORT' (expected 1-65535)"
    fi
    echo "== pairing port: $PAIR_PORT (typed) =="
else
    echo "== looking for the pairing service ($DISCOVER_PAIRING_SERVICE) =="
    PAIR_PORT=$(discover_mdns_service "$DISCOVER_PAIRING_SERVICE" "$MDNS_SECONDS" | head -n 1)
    if [ -z "$PAIR_PORT" ]; then
        if ! discover_mdns_available; then
            die "no mDNS browser: install python-zeroconf with 'pip install zeroconf', or type the port shown in the dialog: $0 <code> <port>"
        fi
        if [ -n "$(discover_mdns_service "$DISCOVER_CONNECT_SERVICE" "$MDNS_SECONDS")" ]; then
            die "no pairing service: wireless debugging is on, but \"Pair device with pairing code\" is not open. Open it, keep it on screen, and re-run."
        fi
        die "wireless debugging appears to be off: no pairing or connect advertisement was seen. Turn on Developer options -> Wireless debugging, open \"Pair device with pairing code\", and re-run."
    fi
    echo "== pairing port: $PAIR_PORT (mDNS) =="
fi

echo "== adb pair 127.0.0.1:$PAIR_PORT =="
pair_output=$(timeout 30 adb pair "127.0.0.1:$PAIR_PORT" "$CODE" 2>&1)
pair_status=$?
if [ -n "$pair_output" ]; then
    printf '%s\n' "$pair_output"
fi

if printf '%s\n' "$pair_output" | grep -qiE 'wrong|incorrect|invalid.*code|authentication|failed to authenticate'; then
    die "wrong pairing code. The dialog shows a new code each time it opens; re-open it and re-run."
fi
if [ "$pair_status" -ne 0 ]; then
    if printf '%s\n' "$pair_output" | grep -qiE 'refused|unable to connect|failed to connect|could not connect|timed out|timeout|reset|closed|no route'; then
        die "pairing port $PAIR_PORT is stale: nothing accepted the connection. The port changes every time the dialog opens; re-open \"Pair device with pairing code\" and re-run (drop any typed port so it is rediscovered)."
    fi
    die "adb pair failed (exit $pair_status). Is wireless debugging still on, with the pairing dialog open?"
fi

echo "== looking for the adbd connect service ($DISCOVER_CONNECT_SERVICE) =="
connect_ports=$(discover_mdns_service "$DISCOVER_CONNECT_SERVICE" "$MDNS_SECONDS")
if [ -z "$connect_ports" ]; then
    echo "-- no mDNS advertisement; using the shared discovery helper --"
    connect_ports=$(discover_ports)
fi
if [ -z "$connect_ports" ]; then
    die "paired, but no adbd connect port was found. Wireless debugging may have switched off; turn it back on and run ./reconnect.sh"
fi

echo "== connecting to the discovered port =="
connected=""
for port in $connect_ports; do
    [ "$port" = "$PAIR_PORT" ] && continue
    [ "$port" = "5037" ] && continue
    echo "-- trying 127.0.0.1:$port"
    if discover_connect "$port" 6; then
        connected=$port
        break
    fi
done
[ -n "$connected" ] ||
    die "paired, but no advertised port accepted an adb connection. Run ./reconnect.sh; if that also fails, toggle wireless debugging and re-run this script."

echo "connected: 127.0.0.1:$connected"

proof=$(timeout 10 adb -s "127.0.0.1:$connected" shell id 2>&1 | tr -d '\r')
printf '%s\n' "$proof"
case "$proof" in
    *"uid=2000(shell)"*) ;;
    *)
        die "connected to 127.0.0.1:$connected, but 'adb shell id' did not report uid=2000(shell)"
        ;;
esac

echo "paired and connected: 127.0.0.1:$connected"
exit 0
