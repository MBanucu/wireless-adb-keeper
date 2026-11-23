#!/data/data/com.termux/files/usr/bin/bash
# Pin adbd to a fixed TCP port, so `adb connect <phone>:5555` keeps working
# after adbd restarts instead of chasing a new random port every time.
#
# Why this is a script and not app code: `adb tcpip 5555` is an adb service
# request, so it needs an adb client and a key adbd accepts. The keeper app has
# neither; this phone's Termux adb is paired with adbd already. The app only
# decides when to run this — it calls it through Termux's RUN_COMMAND API, so
# allow-external-apps=true and the RUN_COMMAND permission are required (README).
#
# Usage:  ./pin-adb-port.sh
#   ADB_PIN_PORT   port to pin (default 5555)
# Log:    pin-adb-port.log
# Exit:   0 pinned, 1 nothing to attach to (wireless debugging off / no adbd)

set -u
SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd) || exit 1
cd "$SCRIPT_DIR" || exit 1
# shellcheck source=discover.sh
. "$SCRIPT_DIR/discover.sh" || exit 1

PORT=$ADB_PIN_PORT
LOG="pin-adb-port.log"

log() {
    echo "$(date '+%H:%M:%S') $*" >> "$LOG"
    if [ "$(wc -c < "$LOG" 2>/dev/null || echo 0)" -gt 100000 ]; then
        tail -n 300 "$LOG" > "$LOG.tmp" && mv "$LOG.tmp" "$LOG"
    fi
}

# The app retries on a timer and a scan is expensive; never run two copies.
# A flock is unusable here: the forked adb server inherits the lock fd.
PIDFILE=".pin-adb-port.pid"
if [ -f "$PIDFILE" ]; then
    old=$(cat "$PIDFILE" 2>/dev/null)
    if [ -n "${old:-}" ] && tr '\0' ' ' < "/proc/$old/cmdline" 2>/dev/null | grep -q pin-adb-port; then
        log "already running (pid $old), exiting"
        exit 0
    fi
fi
echo $$ > "$PIDFILE"
trap 'rm -f "$PIDFILE"' EXIT

state_of() {
    adb devices 2>/dev/null | awk -v a="127.0.0.1:$1" '$1==a{print $2}'
}

pinned() {
    [ "$(state_of "$PORT")" = device ]
}

if pinned; then
    log "already pinned on $PORT"
    exit 0
fi

# adb keeps serials it can no longer reach as "offline", and one of them can be
# named like the port we are about to use.
adb devices 2>/dev/null | awk '$2=="offline"{print $1}' | while read -r s; do
    adb disconnect "$s" >/dev/null 2>&1
done

mdns_ports() {
    [ -x ./adb-mdns.py ] && ./adb-mdns.py 3 2>/dev/null
}

# Last resort: adbd's TLS port is a listening socket in the ephemeral range.
scan() {
    python3 - "$PORT_START" "$PORT_END" <<'PY'
import asyncio, sys

start, end = int(sys.argv[1]), int(sys.argv[2])

async def check(port, sem):
    async with sem:
        try:
            _, w = await asyncio.wait_for(
                asyncio.open_connection("127.0.0.1", port), timeout=0.25)
            w.close()
            return port
        except Exception:
            return None

async def main():
    sem = asyncio.Semaphore(400)
    found = []
    for chunk in range(start, end + 1, 5000):
        results = await asyncio.gather(
            *(check(p, sem) for p in range(chunk, min(chunk + 5000, end + 1))))
        found += [p for p in results if p]
    print(" ".join(str(p) for p in sorted(found)))

asyncio.run(main())
PY
}

connect_on() {
    local port="$1"
    timeout 5 adb connect "127.0.0.1:$port" >/dev/null 2>&1
    for _ in 1 2 3 4 5; do
        [ "$(state_of "$port")" = device ] && return 0
        sleep 1
    done
    adb disconnect "127.0.0.1:$port" >/dev/null 2>&1
    return 1
}

# try_tcpip <port>: attach to adbd there and ask it to listen on $PORT instead.
# Returns 0 once adbd said yes (the old serial goes offline; that is expected).
try_tcpip() {
    local port="$1"
    [ "$port" = "$PORT" ] && return 1
    [ "$port" = "5037" ] && return 1
    connect_on "$port" || return 1
    log "attached to adbd on $port"
    if ! timeout 10 adb -s "127.0.0.1:$port" tcpip "$PORT" >/dev/null 2>&1; then
        log "tcpip $PORT refused on $port"
        adb disconnect "127.0.0.1:$port" >/dev/null 2>&1
        return 1
    fi
    adb disconnect "127.0.0.1:$port" >/dev/null 2>&1
    return 0
}

# 1. A serial the keepalive already holds (cheapest, usually the TLS port).
# 2. The port adbd advertises over mDNS.
# 3. A scan, when mDNS is unavailable or silent.
found=""
for port in $(adb devices 2>/dev/null | awk '$2=="device"{print $1}' | awk -F: 'NF>1{print $NF}'); do
    if try_tcpip "$port"; then found=1; break; fi
done
if [ -z "$found" ]; then
    for port in $(mdns_ports); do
        if try_tcpip "$port"; then found=1; break; fi
    done
fi
if [ -z "$found" ]; then
    log "scanning $PORT_START-$PORT_END for adbd"
    for port in $(scan); do
        if try_tcpip "$port"; then found=1; break; fi
    done
fi

if [ -z "$found" ]; then
    log "no adbd to attach to (wireless debugging off, or no Wi-Fi link)"
    exit 1
fi

for _ in 1 2 3 4 5; do
    if pinned; then
        log "pinned: 127.0.0.1:$PORT"
        exit 0
    fi
    timeout 5 adb connect "127.0.0.1:$PORT" >/dev/null 2>&1
    sleep 1
done

log "port $PORT did not come up after tcpip"
exit 1
