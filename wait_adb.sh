#!/data/data/com.termux/files/usr/bin/bash
# Wait for adbd to appear and connect. Used after Wi-Fi was disconnected.
#
# The pinned port is tried first, so a phone the app has pinned to 5555
# reconnects without a scan. Then the port mDNS advertises, then the shared
# discovery helper (the phone's TCP table if a serial is attached, otherwise a
# loopback scan). PORT_START/PORT_END and ADB_PIN_PORT override the defaults.
#
# Result: the last stdout line is machine readable, for the app:
#   RESULT connected=<port> | RESULT error=timeout
set -u
SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd) || exit 1
cd "$SCRIPT_DIR" || exit 1
# shellcheck source=discover.sh
. "$SCRIPT_DIR/discover.sh" || exit 1

for round in $(seq 1 40); do
    adb kill-server >/dev/null 2>&1
    adb start-server >/dev/null 2>&1

    connected=""
    if discover_connect "$ADB_PIN_PORT" 4; then
        connected="$ADB_PIN_PORT"
    fi
    if [ -z "$connected" ]; then
        for port in $(discover_mdns 3); do
            [ "$port" = "$ADB_PIN_PORT" ] && continue
            if discover_connect "$port" 4; then
                connected="$port"
                break
            fi
        done
    fi
    if [ -z "$connected" ]; then
        for port in $(discover_ports); do
            [ "$port" = "$ADB_PIN_PORT" ] && continue
            if discover_connect "$port" 4; then
                connected="$port"
                break
            fi
        done
    fi

    if [ -n "$connected" ]; then
        echo "CONNECTED 127.0.0.1:$connected"
        adb devices -l
        echo "RESULT connected=$connected"
        exit 0
    fi
    echo "round $round: no adbd yet"
    sleep 12
done
echo "TIMEOUT: adbd never appeared"
echo "RESULT error=timeout"
exit 1
