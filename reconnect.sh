#!/data/data/com.termux/files/usr/bin/bash
# Reconnect ADB to this phone's wireless debugging port.
#
# adbd picks a new random port every time wireless debugging is restarted,
# so the previous "adb connect 127.0.0.1:PORT" address goes stale. This tries
# the pinned port first, then scans localhost for the new port and connects to
# the first one that accepts us.
#
# Usage: ./reconnect.sh [--full]
#   --full  also scan the Wi-Fi subnet in addition to localhost

set -u
SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd) || exit 1
cd "$SCRIPT_DIR" || exit 1
# shellcheck source=discover.sh
. "$SCRIPT_DIR/discover.sh" || exit 1

echo "== current devices =="
adb devices -l

if adb devices | awk '$2=="device"{found=1} END{exit !found}'; then
    echo "== already connected =="
    exit 0
fi

# try_connect <port>: connect and wait for adb to report "device".
try_connect() {
    local port="$1" state
    [ -n "$port" ] && [ "$port" != "5037" ] || return 1
    echo "-- trying 127.0.0.1:$port"
    timeout 8 adb connect "127.0.0.1:$port" >/dev/null 2>&1
    for _ in $(seq 1 6); do
        state=$(adb devices | awk -v a="127.0.0.1:$port" '$1==a{print $2}')
        if [ "$state" = "device" ]; then
            echo "connected: 127.0.0.1:$port"
            return 0
        fi
        sleep 1
    done
    return 1
}

if try_connect "$ADB_PIN_PORT"; then
    exit 0
fi

echo "== scanning ports $PORT_START-$PORT_END =="
PORTS=$(python3 - "$PORT_START" "$PORT_END" <<'PY'
import asyncio, sys

start, end = int(sys.argv[1]), int(sys.argv[2])

async def check(port, sem):
    async with sem:
        try:
            _, w = await asyncio.wait_for(
                asyncio.open_connection("127.0.0.1", port), timeout=0.5)
            w.close()
            return port
        except Exception:
            return None

async def main():
    sem = asyncio.Semaphore(1500)
    results = await asyncio.gather(*(check(p, sem) for p in range(start, end)))
    print(" ".join(str(x) for x in sorted(r for r in results if r)))

asyncio.run(main())
PY
)

if [ -z "$PORTS" ]; then
    echo "no listening port found in range"
    echo "hint: enable wireless debugging, or run:"
    echo "  am broadcast -a io.github.mbanucu.wirelessadbkeeper.ENSURE -n io.github.mbanucu.wirelessadbkeeper/.EnsureReceiver"
    exit 1
fi

echo "candidates: $PORTS"
for port in $PORTS; do
    [ "$port" = "5037" ] && continue
    [ "$port" = "$ADB_PIN_PORT" ] && continue
    if try_connect "$port"; then
        exit 0
    fi
done

echo "no working port found"
echo "if a wireless-debugging trust dialog is on screen, allow it (tick 'Always allow on this network')"
exit 1
