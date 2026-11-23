#!/data/data/com.termux/files/usr/bin/bash
# Reconnect ADB to this phone's wireless debugging port.
#
# adbd picks a new random port every time wireless debugging is restarted,
# so the previous "adb connect 127.0.0.1:PORT" address goes stale. This tries
# the pinned port first, then the port mDNS advertises, then the shared
# discovery helper (the phone's TCP table if a serial is attached, otherwise a
# loopback scan).
#
# Usage: ./reconnect.sh

set -u
SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd) || exit 1
cd "$SCRIPT_DIR" || exit 1
# shellcheck source=discover.sh
. "$SCRIPT_DIR/discover.sh" || exit 1

attempt() {
    echo "-- trying 127.0.0.1:$1"
    discover_connect "$1" 6
}

echo "== current devices =="
adb devices -l

if adb devices | awk '$2=="device"{found=1} END{exit !found}'; then
    echo "== already connected =="
    exit 0
fi

connected=""
if attempt "$ADB_PIN_PORT"; then
    connected="$ADB_PIN_PORT"
fi
if [ -z "$connected" ]; then
    for port in $(discover_mdns 3); do
        [ "$port" = "$ADB_PIN_PORT" ] && continue
        if attempt "$port"; then
            connected="$port"
            break
        fi
    done
fi
if [ -z "$connected" ]; then
    echo "== scanning ports $PORT_START-$PORT_END =="
    for port in $(discover_ports); do
        [ "$port" = "$ADB_PIN_PORT" ] && continue
        if attempt "$port"; then
            connected="$port"
            break
        fi
    done
fi

if [ -n "$connected" ]; then
    echo "connected: 127.0.0.1:$connected"
    exit 0
fi

echo "no working port found"
echo "hint: enable wireless debugging, or run:"
echo "  am broadcast -a io.github.mbanucu.wirelessadbkeeper.ENSURE -n io.github.mbanucu.wirelessadbkeeper/.EnsureReceiver"
echo "if a wireless-debugging trust dialog is on screen, allow it (tick 'Always allow on this network')"
exit 1
