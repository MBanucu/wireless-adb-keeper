#!/usr/bin/env bash
# Check tools/preunlock-connect.sh without a phone, a reboot or a network.
#
# avahi-browse and adb are fakes fed from a state directory; the ports they
# advertise are real loopback listeners started by this script, so "the port
# still accepts a connection" is exercised for real. Discovery is
# fixture-driven: an advertised port with no listener must be skipped, an
# address that is not the phone must be filtered, and a dead record alone must
# fail instead of reaching `adb connect`.
#
#   bash tools/preunlock-connect-test.sh
#
# Wired into `./gradlew probeScripts` and therefore `probe`.
set -u

ROOT=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd) || exit 1
WORK=$(mktemp -d "${TMPDIR:-/tmp}/preunlock-connect.XXXXXX") || exit 1
LISTENERS=$WORK/listeners
: > "$LISTENERS"
cleanup() {
    while read -r pid; do
        kill "$pid" 2>/dev/null
    done < "$LISTENERS"
    rm -rf "$WORK"
}
trap cleanup EXIT

mkdir -p "$WORK/bin" "$WORK/repo/tools" "$WORK/state"
cp "$ROOT/tools/preunlock-connect.sh" "$WORK/repo/tools/" || exit 1
cp "$ROOT/discover.sh" "$ROOT/scan_ports.py" "$WORK/repo/" || exit 1
chmod +x "$WORK/repo/tools/preunlock-connect.sh"

failures=0
check() {
    local what=$1 ok=$2
    if [ "$ok" = 1 ]; then
        echo "ok   $what"
    else
        echo "FAIL $what"
        failures=$((failures + 1))
    fi
}

syntax() {
    local script=$1
    if bash -n "$ROOT/$script" 2>/dev/null; then
        check "bash -n $script" 1
    else
        check "bash -n $script" 0
    fi
}

syntax tools/preunlock-connect.sh
syntax discover.sh
if python3 -c 'import sys; compile(open(sys.argv[1]).read(), sys.argv[1], "exec")' \
    "$ROOT/scan_ports.py" 2>/dev/null; then
    check "python3 compiles scan_ports.py" 1
else
    check "python3 compiles scan_ports.py" 0
fi

# The fake browser replays a resolved-avahi fixture.
cat > "$WORK/bin/avahi-browse" <<'FAKE_AVAHI'
#!/bin/sh
[ -s "$FAKE_STATE/avahi.out" ] || exit 0
cat "$FAKE_STATE/avahi.out"
FAKE_AVAHI

# The fake adb-harness helper advertises whatever the state file says. With
# connect_attaches it also attaches the phone, as the real CLI does.
cat > "$WORK/bin/adb-connect" <<'FAKE_ADB_CONNECT'
#!/bin/sh
[ -s "$FAKE_STATE/connect_ports" ] || exit 1
port=$(head -n 1 "$FAKE_STATE/connect_ports")
if [ -f "$FAKE_STATE/connect_attaches" ]; then
    printf '127.0.0.1:%s\n' "$port" > "$FAKE_STATE/connected"
fi
echo "connected to 127.0.0.1:$port"
FAKE_ADB_CONNECT

# The fake adb only attaches to ports that are in state/ports, which is what a
# listener or a successful `tcpip` writes.
cat > "$WORK/bin/adb" <<'FAKE_ADB'
#!/bin/sh
set -u
state=${FAKE_STATE:?}
printf '%s\n' "$*" >> "$state/calls.log"

case "${1:-}" in
    -s)
        shift
        serial=${1:-}
        shift || true
        case "${1:-}" in
            tcpip)
                port=${2:-}
                printf 'tcpip %s %s\n' "$serial" "$port" >> "$state/calls.log"
                if [ -f "$state/tcpip_refuse" ]; then
                    echo "error: closed" >&2
                    exit 1
                fi
                rm -f "$state/connected"
                printf '%s\n' "$port" >> "$state/ports"
                ;;
            shell)
                if [ "${2:-}" = id ]; then
                    if [ -f "$state/connected" ] &&
                        [ "$serial" = "$(cat "$state/connected")" ]; then
                        echo "uid=2000(shell) gid=2000(shell) groups=2000(shell) context=u:r:shell:s0"
                    else
                        echo "error: device '$serial' not found" >&2
                        exit 1
                    fi
                fi
                ;;
        esac
        ;;
    devices)
        echo "List of devices attached"
        if [ -f "$state/connected" ]; then
            printf '%s\tdevice\n' "$(cat "$state/connected")"
        fi
        if [ -f "$state/offline" ]; then
            printf '%s\toffline\n' "$(cat "$state/offline")"
        fi
        ;;
    connect)
        hostport=${2:-}
        port=${hostport##*:}
        if [ -f "$state/connect_fail_once" ]; then
            rm -f "$state/connect_fail_once"
            echo "failed to connect to '$hostport': Connection refused" >&2
            exit 1
        fi
        if [ -f "$state/offline" ] && [ "$(cat "$state/offline")" = "$hostport" ]; then
            echo "already connected to $hostport"
            exit 0
        fi
        if grep -qx "$port" "$state/ports" 2>/dev/null; then
            printf '%s\n' "$hostport" > "$state/connected"
            echo "connected to $hostport"
            exit 0
        fi
        echo "failed to connect to '$hostport': Connection refused" >&2
        exit 1
        ;;
    disconnect)
        serial=${2:-}
        if [ -f "$state/offline" ] && [ "$(cat "$state/offline")" = "$serial" ]; then
            rm -f "$state/offline"
        fi
        rm -f "$state/connected"
        ;;
esac
exit 0
FAKE_ADB
chmod +x "$WORK/bin/avahi-browse" "$WORK/bin/adb-connect" "$WORK/bin/adb"

# start_listener: a real loopback listener on an ephemeral port, printed once
# it is accepting.
start_listener() {
    local name=$1 out=$WORK/listener.$1
    rm -f "$out"
    python3 - "$out" >/dev/null 2>&1 <<'PY' &
import socket, sys
sock = socket.socket()
sock.bind(("127.0.0.1", 0))
sock.listen(16)
with open(sys.argv[1], "w") as handle:
    handle.write(str(sock.getsockname()[1]))
while True:
    try:
        conn, _ = sock.accept()
        conn.close()
    except OSError:
        break
PY
    echo "$!" >> "$LISTENERS"
    for _ in $(seq 50); do
        [ -s "$out" ] && break
        sleep 0.1
    done
    cat "$out"
}

# avahi_fixture <addr> <port> [addr port]...: resolved IPv4 records.
avahi_fixture() {
    : > "$WORK/state/avahi.out"
    while [ "$#" -ge 2 ]; do
        printf '+;wlan0;IPv4;adb-XPLO2EEA0001541-test;_adb-tls-connect._tcp;local\n' \
            >> "$WORK/state/avahi.out"
        printf '=;wlan0;IPv4;adb-XPLO2EEA0001541-test;_adb-tls-connect._tcp;local;Android.local;%s;%s;"v=ADB_SECURE_SERVICE_VERSION"\n' \
            "$1" "$2" >> "$WORK/state/avahi.out"
        shift 2
    done
}

state() {
    rm -rf "$WORK/state"
    mkdir -p "$WORK/state"
    : > "$WORK/state/calls.log"
    CASE_AVAHI=$WORK/bin/avahi-browse
    CASE_ADB_CONNECT=
    CASE_PORT_START=32768
    CASE_PORT_END=60999
    CASE_PIN_PORT=1
    LAST_OUT=
}

# run_case <name> <expected-exit> <expected-output> [script arguments...]
run_case() {
    local name=$1 want=$2 text=$3 out code
    shift 3
    out=$(cd "$WORK/repo" && env PATH="$WORK/bin:$PATH" FAKE_STATE="$WORK/state" \
        AVAHI_BROWSE="$CASE_AVAHI" ADB_CONNECT="$CASE_ADB_CONNECT" \
        ADB_PIN_PORT="$CASE_PIN_PORT" \
        PORT_START="$CASE_PORT_START" PORT_END="$CASE_PORT_END" \
        timeout 60 bash tools/preunlock-connect.sh "$@" 2>&1)
    code=$?
    LAST_OUT=$out
    if [ "$code" -eq "$want" ] && printf '%s\n' "$out" | grep -qF -- "$text"; then
        check "$name: exit $code, '$text'" 1
    else
        check "$name: expected exit $want with '$text', got exit $code" 0
        printf '%s\n' "$out" | sed 's/^/     | /'
    fi
}

logged() {
    grep -qE -- "$1" "$WORK/state/calls.log"
}

last_result() {
    printf '%s\n' "$LAST_OUT" | awk 'NF { line = $0 } END { print line }'
}

# 1. Success from the mDNS record alone: no --host, no attached serial.
state
port=$(start_listener avahi)
printf '%s\n' "$port" >> "$WORK/state/ports"
avahi_fixture 127.0.0.1 "$port"
run_case "avahi record connects" 0 "uid=2000(shell)" --no-scan
if [ "$(last_result)" = "RESULT connected=127.0.0.1:$port" ] &&
    logged "^connect 127\\.0\\.0\\.1:$port$"; then
    check "avahi record used address and port" 1
else
    check "avahi record used address and port" 0
fi

# 2. A stale record is dropped, the live one behind it is used.
state
dead=$(start_listener dead)
kill "$(tail -n 1 "$LISTENERS")" 2>/dev/null
sleep 0.2
good=$(start_listener good)
printf '%s\n' "$good" >> "$WORK/state/ports"
avahi_fixture 127.0.0.1 "$dead" 127.0.0.1 "$good"
run_case "stale record skipped" 0 "RESULT connected=127.0.0.1:$good" --no-scan
if logged "^connect 127\\.0\\.0\\.1:$dead$"; then
    check "stale record never reached adb connect" 0
else
    check "stale record never reached adb connect" 1
fi

# 3. Only a stale record, no scan: the port validation is the failure point.
state
dead=$(start_listener dead)
kill "$(tail -n 1 "$LISTENERS")" 2>/dev/null
sleep 0.2
avahi_fixture 127.0.0.1 "$dead"
run_case "dead record fails" 1 "RESULT error=no-adbd" --no-scan
if logged "^connect "; then
    check "dead record did not reach adb connect" 0
else
    check "dead record did not reach adb connect" 1
fi

# 4. Records for another interface's address are not this phone.
state
port=$(start_listener good)
printf '%s\n' "$port" >> "$WORK/state/ports"
other=$(start_listener other)
printf '%s\n' "$other" >> "$WORK/state/ports"
avahi_fixture 10.82.194.118 "$other" 127.0.0.1 "$port"
run_case "host filter" 0 "RESULT connected=127.0.0.1:$port" --host 127.0.0.1 --no-scan
if logged "10\\.82\\.194\\.118"; then
    check "other interface's record filtered out" 0
else
    check "other interface's record filtered out" 1
fi

# 5. Optional tcpip: attach on TLS, pin, reconnect on the pinned port.
state
tls=$(start_listener tls)
pinned=$(start_listener pinned)
printf '%s\n' "$tls" >> "$WORK/state/ports"
avahi_fixture 127.0.0.1 "$tls"
run_case "tcpip pins and reconnects" 0 "RESULT connected=127.0.0.1:$pinned tcpip=$pinned" \
    --host 127.0.0.1 --no-scan --tcpip "$pinned"
if logged "^tcpip 127\\.0\\.0\\.1:$tls $pinned$" &&
    logged "^connect 127\\.0\\.0\\.1:$pinned$"; then
    check "tcpip ran on the TLS serial and reconnected" 1
else
    check "tcpip ran on the TLS serial and reconnected" 0
fi

# 5b. adb still remembers the pinned serial offline after an earlier run.
# `adb connect` is a no-op for that serial, so tcpip's reconnect must clear
# it first, the same way attempt() does before its retry loop.
state
tls=$(start_listener tls_offline)
pinned=$(start_listener pinned_offline)
printf '%s\n' "$tls" >> "$WORK/state/ports"
avahi_fixture 127.0.0.1 "$tls"
printf '127.0.0.1:%s\n' "$pinned" > "$WORK/state/offline"
run_case "tcpip clears a remembered offline serial" 0 \
    "RESULT connected=127.0.0.1:$pinned tcpip=$pinned" \
    --host 127.0.0.1 --no-scan --tcpip "$pinned"
if logged "^disconnect 127\\.0\\.0\\.1:$pinned$"; then
    check "tcpip disconnected the remembered offline target" 1
else
    check "tcpip disconnected the remembered offline target" 0
fi

# 6. tcpip refused by adbd: reported, not swallowed.
state
tls=$(start_listener tls)
printf '%s\n' "$tls" >> "$WORK/state/ports"
avahi_fixture 127.0.0.1 "$tls"
: > "$WORK/state/tcpip_refuse"
CASE_PIN_PORT=5555
run_case "tcpip refused" 1 "RESULT error=tcpip-refused tcpip=5555" \
    --host 127.0.0.1 --no-scan --tcpip

# 6b. The first connect fails (a stale offline serial does this on a real
# host); the retry loop must still attach.
state
tls=$(start_listener retry)
printf '%s\n' "$tls" >> "$WORK/state/ports"
avahi_fixture 127.0.0.1 "$tls"
: > "$WORK/state/connect_fail_once"
run_case "transient connect retried" 0 "RESULT connected=127.0.0.1:$tls" \
    --host 127.0.0.1 --no-scan

# 7. No mDNS browser at all: the range scan finds the listener.
state
CASE_AVAHI=
port=$(start_listener scanned)
printf '%s\n' "$port" >> "$WORK/state/ports"
CASE_PORT_START=$((port - 2))
CASE_PORT_END=$((port + 2))
run_case "scan fallback" 0 "RESULT connected=127.0.0.1:$port" --host 127.0.0.1
if logged "^connect 127\\.0\\.0\\.1:$port$"; then
    check "scan found and connected the listener" 1
else
    check "scan found and connected the listener" 0
fi

# 8. adb-harness (adb-connect) as the discovery source, with no avahi.
state
CASE_AVAHI=
CASE_ADB_CONNECT=$WORK/bin/adb-connect
port=$(start_listener harness)
printf '%s\n' "$port" >> "$WORK/state/ports"
printf '%s\n' "$port" > "$WORK/state/connect_ports"
run_case "adb-connect source" 0 "RESULT connected=127.0.0.1:$port" --host 127.0.0.1 --no-scan
if logged "^connect 127\\.0\\.0\\.1:$port$"; then
    check "adb-connect port connected on the known address" 1
else
    check "adb-connect port connected on the known address" 0
fi

# 8b. adb-connect attached the phone even though no address was known before:
# its serial supplies the address for the port it advertised.
state
CASE_AVAHI=
CASE_ADB_CONNECT=$WORK/bin/adb-connect
port=$(start_listener harness_attach)
printf '%s\n' "$port" >> "$WORK/state/ports"
printf '%s\n' "$port" > "$WORK/state/connect_ports"
: > "$WORK/state/connect_attaches"
run_case "adb-connect attached serial" 0 "RESULT connected=127.0.0.1:$port" --no-scan

# 9. Nothing to go on: no mDNS source and no address.
state
CASE_AVAHI=
run_case "no host" 1 "RESULT error=no-host" --no-scan
if logged "^connect "; then
    check "no host did not reach adb connect" 0
else
    check "no host did not reach adb connect" 1
fi

# 10. IPv6 records are ignored; IPv4 is the address the host can use.
state
port=$(start_listener v4)
printf '%s\n' "$port" >> "$WORK/state/ports"
avahi_fixture 127.0.0.1 "$port"
printf '=;wlan0;IPv6;adb-XPLO2EEA0001541-test;_adb-tls-connect._tcp;local;Android.local;fe80::1%%wlan0;%s;"v=ADB_SECURE_SERVICE_VERSION"\n' \
    "$port" >> "$WORK/state/avahi.out"
run_case "IPv6 ignored" 0 "RESULT connected=127.0.0.1:$port" --no-scan
if logged "fe80"; then
    check "IPv6 record not dialled" 0
else
    check "IPv6 record not dialled" 1
fi

echo "$failures failure(s)" >&2
[ "$failures" -eq 0 ]
