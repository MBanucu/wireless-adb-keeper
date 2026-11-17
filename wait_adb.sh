#!/data/data/com.termux/files/usr/bin/bash
# Wait for adbd to appear and connect. Used after Wi-Fi was disconnected.
set -u
cd "$(dirname "$0")"

scan() {
    python3 - <<'PY'
import asyncio
async def check(p, sem):
    async with sem:
        try:
            _, w = await asyncio.wait_for(asyncio.open_connection("127.0.0.1", p), timeout=0.2)
            w.close(); return p
        except Exception: return None
async def main():
    sem = asyncio.Semaphore(700)
    out = []
    for start in range(30000, 50001, 5000):
        r = await asyncio.gather(*(check(p, sem) for p in range(start, min(start + 5000, 50001))))
        out += [x for x in r if x]
    print(" ".join(str(x) for x in sorted(out)))
asyncio.run(main())
PY
}

for round in $(seq 1 40); do
    adb kill-server >/dev/null 2>&1
    adb start-server >/dev/null 2>&1
    ports=$(scan)
    for p in $ports; do
        [ "$p" = "5037" ] && continue
        timeout 5 adb connect "127.0.0.1:$p" >/dev/null 2>&1
        for _ in 1 2 3 4; do
            state=$(adb devices | awk -v a="127.0.0.1:$p" '$1==a{print $2}')
            if [ "$state" = "device" ]; then
                echo "CONNECTED 127.0.0.1:$p"
                adb devices -l
                exit 0
            fi
            sleep 1
        done
        adb disconnect "127.0.0.1:$p" >/dev/null 2>&1
    done
    echo "round $round: no adbd yet"
    sleep 12
done
echo "TIMEOUT: adbd never appeared"
exit 1
