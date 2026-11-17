#!/data/data/com.termux/files/usr/bin/bash
# Keeps `adb` attached to this phone while Android restarts wireless debugging.
#
# adbd picks a new random port every time wireless debugging is toggled, so a
# one-shot `adb connect` goes stale. This loop notices the disconnect, finds the
# new port and reconnects. It is the only piece the app cannot do itself: the app
# has no permission to run the adb client or read adbd's port.
#
# Port discovery, cheapest first:
#   1. the port used last time (survives a plain Wi-Fi reconnect)
#   2. mDNS: adbd advertises _adb-tls-connect._tcp itself (adb-mdns.py)
#   3. a TCP scan of 30000-50000, only if mDNS stays silent
#
# Usage:  ./adb-keepalive.sh            run in foreground (Ctrl-C to stop)
#         ./adb-keepalive.sh &          run in background
# Log:    adb-keepalive.log

set -u
cd "$(dirname "$0")"

LOG="adb-keepalive.log"
PORT_START=${PORT_START:-30000}
PORT_END=${PORT_END:-50000}
MDNS=0
python3 -c 'import zeroconf' >/dev/null 2>&1 && [ -x ./adb-mdns.py ] && MDNS=1

log() {
    echo "$(date '+%H:%M:%S') $*" | tee -a "$LOG"
    # Keep the log small; the loop can run for weeks.
    if [ "$(wc -c < "$LOG" 2>/dev/null || echo 0)" -gt 200000 ]; then
        tail -n 400 "$LOG" > "$LOG.tmp" && mv "$LOG.tmp" "$LOG"
    fi
}

# Two scans running at once make the kernel drop connection attempts, so the
# scan silently misses the real port. Only ever run one copy.
#
# A flock cannot be used here: the adb server forks itself and inherits the
# lock fd, so the lock stays held after this script is gone. A pid file checked
# against /proc is immune to that.
PIDFILE=".adb-keepalive.pid"
if [ -f "$PIDFILE" ]; then
    old=$(cat "$PIDFILE" 2>/dev/null)
    if [ -n "${old:-}" ] && tr '\0' ' ' < "/proc/$old/cmdline" 2>/dev/null | grep -q adb-keepalive; then
        log "already running (pid $old), exiting"
        exit 0
    fi
fi
echo $$ > "$PIDFILE"
trap 'rm -f "$PIDFILE"' EXIT INT TERM

scan() {
    python3 - "$PORT_START" "$PORT_END" <<'PY'
import asyncio, sys

start, end = int(sys.argv[1]), int(sys.argv[2])

async def check(port, sem):
    async with sem:
        try:
            _, w = await asyncio.wait_for(
                asyncio.open_connection("127.0.0.1", port), timeout=0.3)
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

state_of() {
    adb devices | awk -v a="127.0.0.1:$1" '$1==a{print $2}'
}

# Ports that accept a TCP connection but never speak adb (other apps listening
# on random ports). Remembered so later rounds skip them.
BAD=""

# try_port <port> [soft]
#   soft=1: authoritative candidate (mDNS), never remembered as bad.
try_port() {
    local port="$1" soft="${2:-0}"
    [ -z "$port" ] && return 1
    if [ "$soft" = "0" ]; then
        case " $BAD " in *" $port "*) return 1 ;; esac
    fi
    timeout 5 adb connect "127.0.0.1:$port" >/dev/null 2>&1
    for _ in 1 2 3; do
        case "$(state_of "$port")" in
            device) return 0 ;;
        esac
        sleep 1
    done
    adb disconnect "127.0.0.1:$port" >/dev/null 2>&1
    [ "$soft" = "0" ] && BAD="$BAD $port"
    return 1
}

command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock >/dev/null 2>&1
log "watching for wireless debugging (ports $PORT_START-$PORT_END, mdns=$MDNS)"

last=""
was_connected=""
mdns_misses=0

while true; do
    current=$(adb devices | awk '$2=="device"{print $1; exit}')

    if [ -n "$current" ]; then
        if [ "$was_connected" != "$current" ]; then
            log "connected: $current"
            was_connected="$current"
            last="${current##*:}"
            mdns_misses=0
        fi
        sleep 5
        continue
    fi

    if [ -n "$was_connected" ]; then
        log "lost: $was_connected"
        was_connected=""
    fi

    # 1. Fast path: a plain Wi-Fi reconnect usually keeps the same port.
    if try_port "$last"; then
        log "reconnected on previous port: 127.0.0.1:$last"
        was_connected="127.0.0.1:$last"
        continue
    fi

    # 2. mDNS: the advertisement carries the new port directly.
    if [ "$MDNS" = "1" ]; then
        started=$(date +%s)
        ports=$(./adb-mdns.py 3 2>/dev/null)
        took=$(( $(date +%s) - started ))
        if [ -n "$ports" ]; then
            mdns_misses=0
            for p in $ports; do
                if try_port "$p" 1; then
                    last="$p"
                    log "reconnected via mdns: 127.0.0.1:$p (found in ${took}s)"
                    was_connected="127.0.0.1:$p"
                    break
                fi
            done
            [ -n "$was_connected" ] && continue
        else
            mdns_misses=$(( mdns_misses + 1 ))
        fi
    fi

    # 3. Last resort: TCP scan. With mDNS available, give the advertisement a
    #    couple of rounds before paying for it.
    if [ "$MDNS" = "1" ] && [ "$mdns_misses" -lt 2 ]; then
        sleep 2
        continue
    fi

    started=$(date +%s)
    ports=$(scan)
    took=$(( $(date +%s) - started ))
    for p in $ports; do
        [ "$p" = "5037" ] && continue
        if try_port "$p"; then
            last="$p"
            log "reconnected: 127.0.0.1:$p (scan ${took}s)"
            was_connected="127.0.0.1:$p"
            break
        fi
    done

    if [ -z "$was_connected" ]; then
        log "no adbd yet (scanned ${#ports} candidates in ${took}s)"
        sleep 3
    fi
done
