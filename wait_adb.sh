#!/data/data/com.termux/files/usr/bin/bash
# Wait for adbd to appear and connect. Used after Wi-Fi was disconnected.
#
# The pinned port is tried first, so a phone the app has pinned to 5555
# reconnects without a scan. Then the shared loopback range is scanned; the
# keeper app's scan does not run while this one does.
set -u
SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd) || exit 1
cd "$SCRIPT_DIR" || exit 1
# shellcheck source=discover.sh
. "$SCRIPT_DIR/discover.sh" || exit 1

scan() {
    python3 - "$PORT_START" "$PORT_END" <<'PY'
import asyncio, sys
async def check(p, sem):
    async with sem:
        try:
            _, w = await asyncio.wait_for(asyncio.open_connection("127.0.0.1", p), timeout=0.2)
            w.close(); return p
        except Exception: return None
async def main():
    start, end = int(sys.argv[1]), int(sys.argv[2])
    sem = asyncio.Semaphore(700)
    out = []
    for chunk in range(start, end + 1, 5000):
        r = await asyncio.gather(*(check(p, sem) for p in range(chunk, min(chunk + 5000, end + 1))))
        out += [x for x in r if x]
    print(" ".join(str(x) for x in sorted(out)))
asyncio.run(main())
PY
}

# try_connect <port>: connect and wait for adb to report "device".
try_connect() {
    local port="$1" state
    [ -n "$port" ] && [ "$port" != "5037" ] || return 1
    timeout 5 adb connect "127.0.0.1:$port" >/dev/null 2>&1
    for _ in 1 2 3 4; do
        state=$(adb devices | awk -v a="127.0.0.1:$port" '$1==a{print $2}')
        if [ "$state" = "device" ]; then
            return 0
        fi
        sleep 1
    done
    adb disconnect "127.0.0.1:$port" >/dev/null 2>&1
    return 1
}

for round in $(seq 1 40); do
    adb kill-server >/dev/null 2>&1
    adb start-server >/dev/null 2>&1

    if try_connect "$ADB_PIN_PORT"; then
        echo "CONNECTED 127.0.0.1:$ADB_PIN_PORT"
        adb devices -l
        exit 0
    fi

    ports=$(scan)
    for p in $ports; do
        [ "$p" = "$ADB_PIN_PORT" ] && continue
        if try_connect "$p"; then
            echo "CONNECTED 127.0.0.1:$p"
            adb devices -l
            exit 0
        fi
    done
    echo "round $round: no adbd yet"
    sleep 12
done
echo "TIMEOUT: adbd never appeared"
exit 1
