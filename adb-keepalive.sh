#!/data/data/com.termux/files/usr/bin/bash
# Keeps `adb` attached to this phone while Android restarts wireless debugging.
#
# adbd picks a new random port every time wireless debugging is toggled, so a
# one-shot `adb connect` goes stale. This loop notices the disconnect, finds the
# new port and reconnects. It is the only piece the app cannot do itself: the app
# has no permission to run the adb client or read adbd's port.
#
# Port discovery, cheapest first:
#   0. the port the app pinned adbd to (ADB_PIN_PORT, 5555)
#   1. the port used last time (survives a plain Wi-Fi reconnect)
#   2. mDNS: adbd advertises _adb-tls-connect._tcp itself (adb-connect from
#      adb-harness, pinned in tools/requirements.txt)
#   3. the phone's TCP table if a serial is attached, otherwise a TCP scan of
#      the kernel's ephemeral range (PORT_START/PORT_END override the range;
#      the default is 32768-60999)
#
# Usage:  ./adb-keepalive.sh            run in foreground (Ctrl-C to stop)
#         ./adb-keepalive.sh &          run in background
# Log:    adb-keepalive.log

set -u
SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd) || exit 1
cd "$SCRIPT_DIR" || exit 1
# shellcheck source=discover.sh
. "$SCRIPT_DIR/discover.sh" || exit 1

LOG="adb-keepalive.log"
MDNS=0
MDNS_INFO=
if discover_mdns_available; then
    MDNS=1
    MDNS_INFO=$(discover_mdns_version)
fi

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

# A wake lock left held after the watchdog is gone keeps the phone awake for
# nothing. Every normal exit path (Ctrl-C, TERM from a boot script) runs this;
# only SIGKILL cannot be caught.
LOCKED=0
cleanup() {
    rm -f "$PIDFILE"
    if [ "$LOCKED" = "1" ]; then
        termux-wake-unlock >/dev/null 2>&1
    fi
}
trap cleanup EXIT
trap 'exit 0' INT TERM

# Shizuku runs a process as the shell user so apps can use adb-level APIs. Its
# server dies with the phone, and it is started by an adb command, so restart it
# whenever adb comes back (and periodically, in case it was killed meanwhile).
# Optional: everything here is skipped if Shizuku or the starter is missing.
SHIZUKU=unknown

shizuku_available() {
    [ "$SHIZUKU" = "1" ] && return 0
    [ "$SHIZUKU" = "0" ] && return 1
    command -v start-shizuku >/dev/null 2>&1 || { SHIZUKU=0; return 1; }
    if adb shell pm path moe.shizuku.privileged.api >/dev/null 2>&1; then
        SHIZUKU=1
        return 0
    fi
    # Only conclude "not installed" once a plain shell command works, so a
    # still-starting adbd does not permanently disable the check.
    adb shell true >/dev/null 2>&1 && SHIZUKU=0
    return 1
}

ensure_shizuku() {
    shizuku_available || return 0
    if adb shell ps -A 2>/dev/null | grep -q shizuku_server; then
        return 0
    fi
    log "shizuku server is down, starting it"
    if start-shizuku >/dev/null 2>&1; then
        log "shizuku server started"
        return 0
    fi
    log "shizuku start failed"
    return 1
}


# Ports that accept a TCP connection but never speak adb (other apps listening
# on random ports). Remembered so later rounds skip them.
BAD=""

# The keeper app pins adbd to 5555 when it can (pin-adb-port.sh). Prefer that
# port, so this loop re-attaches there instead of hunting for a TLS port that no
# longer exists. soft=1: a closed 5555 must stay a candidate for later rounds.
PINNED_PORT=$ADB_PIN_PORT

# try_port <port> [soft]
#   soft=1: authoritative candidate (mDNS), never remembered as bad.
try_port() {
    local port="$1" soft="${2:-0}"
    [ -z "$port" ] && return 1
    if [ "$soft" = "0" ]; then
        case " $BAD " in *" $port "*) return 1 ;; esac
    fi
    if discover_connect "$port" 3; then
        return 0
    fi
    [ "$soft" = "0" ] && BAD="$BAD $port"
    return 1
}

# Only take the lock if the Termux API command exists; LOCKED then gates the
# matching unlock in cleanup().
if command -v termux-wake-lock >/dev/null 2>&1; then
    if termux-wake-lock >/dev/null 2>&1; then
        LOCKED=1
    fi
fi
log "watching for wireless debugging (ports $PORT_START-$PORT_END, mdns=$MDNS${MDNS_INFO:+, $MDNS_INFO})"

last=""
was_connected=""
mdns_misses=0
poll_count=0
need_shizuku=0

while true; do
    current=$(adb devices | awk '$2=="device"{print $1; exit}')

    if [ -n "$current" ]; then
        if [ "$was_connected" != "$current" ]; then
            log "connected: $current"
            was_connected="$current"
            last="${current##*:}"
            mdns_misses=0
            need_shizuku=1
        fi
        # adb can accept the connection a moment before shell commands work, so
        # a failure here is retried on the next poll instead of being dropped.
        if [ "$need_shizuku" = "1" ]; then
            ensure_shizuku && need_shizuku=0
        else
            poll_count=$(( poll_count + 1 ))
            if [ $(( poll_count % 12 )) -eq 0 ]; then
                ensure_shizuku
            fi
        fi
        sleep 5
        continue
    fi

    if [ -n "$was_connected" ]; then
        log "lost: $was_connected"
        was_connected=""
    fi

    # 0. The port the keeper app pins adbd to, when it managed to.
    if try_port "$PINNED_PORT" 1; then
        log "reconnected on pinned port: 127.0.0.1:$PINNED_PORT"
        was_connected="127.0.0.1:$PINNED_PORT"
        last="$PINNED_PORT"
        need_shizuku=1
        continue
    fi

    # 1. Fast path: a plain Wi-Fi reconnect usually keeps the same port.
    if try_port "$last"; then
        log "reconnected on previous port: 127.0.0.1:$last"
        was_connected="127.0.0.1:$last"
        need_shizuku=1
        continue
    fi

    # 2. mDNS: adb-connect reads the advertisement and reports the port it
    #    connected to. An older adb-harness has no exit 3, so discover.sh
    #    recognises a missing prerequisite from its message instead.
    if [ "$MDNS" = "1" ]; then
        started=$(date +%s)
        ports=$(discover_mdns 3)
        mdns_status=$?
        took=$(( $(date +%s) - started ))
        if [ "$mdns_status" -eq 3 ]; then
            MDNS=0
            log "mdns unavailable (adb-connect/adb-harness); using the TCP table/scan"
        elif [ -n "$ports" ]; then
            mdns_misses=0
            for p in $ports; do
                if try_port "$p" 1; then
                    last="$p"
                    log "reconnected via mdns: 127.0.0.1:$p (found in ${took}s)"
                    was_connected="127.0.0.1:$p"
                    need_shizuku=1
                    break
                fi
            done
            [ -n "$was_connected" ] && continue
        else
            mdns_misses=$(( mdns_misses + 1 ))
        fi
    fi

    # 3. Last resort: the shared discovery helper. It reads the phone's TCP
    #    table when a serial is attached and scans the range otherwise. With
    #    mDNS available, give the advertisement a couple of rounds first.
    if [ "$MDNS" = "1" ] && [ "$mdns_misses" -lt 2 ]; then
        sleep 2
        continue
    fi

    started=$(date +%s)
    ports=$(discover_ports)
    took=$(( $(date +%s) - started ))
    for p in $ports; do
        [ "$p" = "5037" ] && continue
        if try_port "$p"; then
            last="$p"
            log "reconnected: 127.0.0.1:$p (scan ${took}s)"
            was_connected="127.0.0.1:$p"
            need_shizuku=1
            break
        fi
    done

    if [ -z "$was_connected" ]; then
        log "no adbd yet (scanned ${#ports} candidates in ${took}s)"
        sleep 3
    fi
done
