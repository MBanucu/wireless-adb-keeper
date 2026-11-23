#!/data/data/com.termux/files/usr/bin/bash
# Check pair-local.sh with a fake adb and a fake mDNS helper.
#
# No device, no pairing and no network: the fake adb answers `pair`, `connect`,
# `devices` and `shell id` from files in a state directory, and the fake
# adb-mdns.py returns the ports those files advertise. Wired into
# `./gradlew probeScripts` and therefore `probe`.
set -u

ROOT=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd) || exit 1
WORK=$(mktemp -d "${TMPDIR:-/tmp}/pair-local.XXXXXX") || exit 1
trap 'rm -rf "$WORK"' EXIT

mkdir -p "$WORK/bin" "$WORK/repo" "$WORK/state"
for f in pair-local.sh discover.sh scan_ports.py; do
    cp "$ROOT/$f" "$WORK/repo/$f" || exit 1
done

# The fake helper replaces adb-mdns.py in the copied tree, so discovery finds
# the state files rather than the real zeroconf browser.
cat > "$WORK/repo/adb-mdns.py" <<'FAKE_MDNS'
#!/usr/bin/env bash
set -u
state=${FAKE_STATE:?}
service=${2:-_adb-tls-connect._tcp.local.}
case "$service" in
    *_adb-tls-pairing._tcp*) file=pair_ports ;;
    *) file=connect_ports ;;
esac
if [ -f "$state/$file" ]; then
    cat "$state/$file"
fi
FAKE_MDNS
chmod +x "$WORK/repo/adb-mdns.py"

# discover_mdns_available() asks python3 for zeroconf. The fake only answers
# that probe; the scenarios here never reach the scan.
cat > "$WORK/bin/python3" <<'FAKE_PY'
#!/usr/bin/env bash
if [ "${FAKE_NO_ZEROCONF:-}" = 1 ]; then
    exit 1
fi
exit 0
FAKE_PY
chmod +x "$WORK/bin/python3"

cat > "$WORK/bin/adb" <<'FAKE_ADB'
#!/usr/bin/env bash
set -u
state=${FAKE_STATE:?}
printf '%s\n' "$*" >> "$state/calls.log"

case "${1:-}" in
    -s)
        shift
        serial=${1:-}
        shift || true
        if [ "${1:-}" = shell ] && [ "${2:-}" = id ]; then
            if [ -f "$state/connected_port" ] &&
                [ "$serial" = "127.0.0.1:$(cat "$state/connected_port")" ]; then
                echo "uid=2000(shell) gid=2000(shell) groups=2000(shell) context=u:r:shell:s0"
            else
                echo "error: device '$serial' not found" >&2
                exit 1
            fi
        fi
        ;;
    pair)
        hostport=${2:-}
        port=${hostport##*:}
        mode=$(cat "$state/pair_mode" 2>/dev/null || echo ok)
        if [ "$mode" = wrong ]; then
            echo "Failed: Wrong pairing code" >&2
            exit 1
        fi
        if [ "$mode" = refused ]; then
            echo "Failed: unable to connect to $hostport: Connection refused" >&2
            exit 1
        fi
        if grep -qx "$port" "$state/pair_ports" 2>/dev/null; then
            echo "Successfully paired to $hostport [guid=adb-test]"
            exit 0
        fi
        echo "Failed: unable to connect to $hostport: Connection refused" >&2
        exit 1
        ;;
    devices)
        echo "List of devices attached"
        if [ -f "$state/connected_port" ]; then
            printf '127.0.0.1:%s\tdevice\n' "$(cat "$state/connected_port")"
        fi
        ;;
    connect)
        hostport=${2:-}
        port=${hostport##*:}
        if grep -qx "$port" "$state/connect_ports" 2>/dev/null; then
            printf '%s\n' "$port" > "$state/connected_port"
            echo "connected to $hostport"
            exit 0
        fi
        echo "failed to connect to '$hostport': Connection refused" >&2
        exit 1
        ;;
    disconnect)
        rm -f "$state/connected_port"
        ;;
    kill-server|start-server)
        ;;
esac
exit 0
FAKE_ADB
chmod +x "$WORK/bin/adb"

failures=0
check() {
    local what="$1" ok="$2"
    if [ "$ok" = 1 ]; then
        echo "ok   $what"
    else
        echo "FAIL $what"
        failures=$((failures + 1))
    fi
}

state() {
    rm -rf "$WORK/state"
    mkdir -p "$WORK/state"
    : > "$WORK/state/calls.log"
    EXTRA_ENV=()
}

ports() {
    printf '%s\n' "$1" > "$WORK/state/pair_ports"
    printf '%s\n' "$2" > "$WORK/state/connect_ports"
}

# run_case <name> <expected-exit> <expected-output> [pair-local arguments...]
# The full output is kept in LAST_OUT for extra assertions.
run_case() {
    local name="$1" want="$2" text="$3" out code
    shift 3
    out=$(cd "$WORK/repo" && env PATH="$WORK/bin:$PATH" FAKE_STATE="$WORK/state" \
        "${EXTRA_ENV[@]}" timeout 60 bash ./pair-local.sh "$@" 2>&1)
    code=$?
    LAST_OUT=$out
    if [ "$code" -eq "$want" ] && printf '%s\n' "$out" | grep -qiF -- "$text"; then
        check "$name: exit $code, '$text'" 1
    else
        check "$name: expected exit $want with '$text', got exit $code" 0
        printf '%s\n' "$out" | sed 's/^/     | /'
    fi
}

logged() {
    grep -qE -- "$1" "$WORK/state/calls.log"
}

syntax() {
    local script="$1"
    if bash -n "$ROOT/$script" 2>/dev/null; then
        check "bash -n $script" 1
    else
        check "bash -n $script" 0
    fi
}

syntax pair-local.sh
syntax discover.sh

# 1. Success: mDNS gives both ports, adb pairs, connects and answers as shell.
state
ports 37111 43111
run_case "success" 0 "uid=2000(shell)" 123456
if logged '^pair 127\.0\.0\.1:37111 123456$' &&
    logged '^connect 127\.0\.0\.1:43111$'; then
    check "success used the discovered pair and connect ports" 1
else
    check "success used the discovered pair and connect ports" 0
fi

# 2. Wrong code: reported as such, and no connect is attempted.
state
ports 37112 43112
printf 'wrong\n' > "$WORK/state/pair_mode"
run_case "wrong code" 1 "wrong pairing code" 123456
if logged '^connect '; then
    check "wrong code did not try to connect" 0
else
    check "wrong code did not try to connect" 1
fi

# 3. Wireless debugging off: neither advertisement is present.
state
: > "$WORK/state/pair_ports"
: > "$WORK/state/connect_ports"
run_case "wireless debugging off" 1 "wireless debugging appears to be off" 123456
if logged '^pair '; then
    check "wireless debugging off did not try to pair" 0
else
    check "wireless debugging off did not try to pair" 1
fi

# 4. Wireless debugging on, but the pairing dialog is not open.
state
: > "$WORK/state/pair_ports"
printf '43114\n' > "$WORK/state/connect_ports"
run_case "no pairing service" 1 "no pairing service" 123456

# 5. The advertised pairing port no longer accepts connections.
state
ports 37115 43115
printf 'refused\n' > "$WORK/state/pair_mode"
run_case "stale pairing port" 1 "is stale" 123456
if logged '^connect '; then
    check "stale pairing port did not try to connect" 0
else
    check "stale pairing port did not try to connect" 1
fi

# 6. No mDNS browser: the error says the port has to be typed. A typed port
#    skips pairing discovery; the connect port is still discovered normally.
state
: > "$WORK/state/pair_ports"
: > "$WORK/state/connect_ports"
EXTRA_ENV=(FAKE_NO_ZEROCONF=1)
run_case "no mDNS browser" 1 "zeroconf" 123456
state
ports 37116 43116
run_case "typed pairing port" 0 "uid=2000(shell)" 123456 37116
if logged '^pair 127\.0\.0\.1:37116 123456$' &&
    printf '%s\n' "$LAST_OUT" | grep -q 'typed'; then
    check "typed pairing port was used" 1
else
    check "typed pairing port was used" 0
fi

# The code may also come from the environment.
state
ports 37119 43119
EXTRA_ENV=(PAIR_CODE=123456)
run_case "code from PAIR_CODE" 0 "uid=2000(shell)"

# 7. A toggle changes both ports; the second run rediscovers them, proving
#    nothing is cached or hardcoded.
state
ports 37117 43117
run_case "rediscovery before toggle" 0 "127.0.0.1:43117" 123456
: > "$WORK/state/calls.log"
rm -f "$WORK/state/connected_port"
ports 37118 43118
run_case "rediscovery after toggle" 0 "127.0.0.1:43118" 123456
if logged '^pair 127\.0\.0\.1:37118 123456$' &&
    logged '^connect 127\.0\.0\.1:43118$' &&
    ! logged '43117'; then
    check "toggle rediscovered both ports" 1
else
    check "toggle rediscovered both ports" 0
fi

echo "$failures failure(s)" >&2
[ "$failures" -eq 0 ]
