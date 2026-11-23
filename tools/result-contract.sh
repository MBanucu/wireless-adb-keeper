#!/data/data/com.termux/files/usr/bin/bash
# Check the scripts' final RESULT line and their exit codes with a fake adb.
#
# No device and no Termux: this only exercises the contract the app parses
# (the last stdout line) and the failure paths that are quick to reach.
# Wired into `./gradlew probeScripts` and therefore `probe`.
set -u

ROOT=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd) || exit 1
WORK=$(mktemp -d "${TMPDIR:-/tmp}/result-contract.XXXXXX") || exit 1
trap 'rm -rf "$WORK"' EXIT

mkdir -p "$WORK/bin" "$WORK/repo"
for f in pin-adb-port.sh wait_adb.sh wifi-on.sh discover.sh scan_ports.py; do
    cp "$ROOT/$f" "$WORK/repo/$f" || exit 1
done
# Leave adb-mdns.py out: with it absent, discovery skips mDNS and stays local.
chmod +x "$WORK/repo/pin-adb-port.sh" "$WORK/repo/wait_adb.sh" "$WORK/repo/wifi-on.sh"

cat > "$WORK/bin/adb" <<'FAKE'
#!/bin/sh
mode=${FAKE_ADB_MODE:-device}
if [ "$1" = "-s" ]; then
    shift 2
fi
case "$1" in
    devices)
        if [ "$mode" != "no-device" ]; then
            printf 'List of devices attached\n127.0.0.1:5555\tdevice\n'
        fi
        ;;
    connect|disconnect)
        ;;
    shell)
        shift
        case "$*" in
            *"settings get global wifi_on"*)
                if [ "$mode" = "wifi-off" ]; then echo 0; else echo 1; fi
                ;;
        esac
        ;;
esac
exit 0
FAKE
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

syntax() {
    local script="$1"
    if bash -n "$ROOT/$script" 2>/dev/null; then
        check "bash -n $script" 1
    else
        check "bash -n $script" 0
    fi
}

# run <name> <expected-result> <expected-exit> <script> [env...]
run() {
    local name="$1" expected="$2" want="$3" script="$4"
    shift 4
    local out code last
    out=$(cd "$WORK/repo" && env PATH="$WORK/bin:$PATH" "$@" \
        timeout 40 bash "$script" 2>&1)
    code=$?
    last=$(printf '%s\n' "$out" | awk 'NF { line = $0 } END { print line }')
    if [ "$last" = "$expected" ] && [ "$code" = "$want" ]; then
        check "$name: $expected (exit $code)" 1
    else
        check "$name: expected $expected (exit $want), got '$last' (exit $code)" 0
        printf '%s\n' "$out" | sed 's/^/     | /'
    fi
}

syntax pin-adb-port.sh
syntax wait_adb.sh
syntax wifi-on.sh

# Already pinned: the cheapest success path in pin-adb-port.sh.
run "pin success" "RESULT pinned=5555" 0 pin-adb-port.sh \
    FAKE_ADB_MODE=device ADB_PIN_PORT=5555

# No adbd anywhere. Keep the scan to one port so the check stays quick.
run "pin no adbd" "RESULT error=no-adbd" 1 pin-adb-port.sh \
    FAKE_ADB_MODE=no-device ADB_PIN_PORT=5556 PORT_START=1 PORT_END=1

run "wait success" "RESULT connected=5555" 0 wait_adb.sh \
    FAKE_ADB_MODE=device ADB_PIN_PORT=5555

run "wifi on success" "RESULT wifi=on" 0 wifi-on.sh \
    FAKE_ADB_MODE=device ADB_PIN_PORT=5555

run "wifi on no device" "RESULT error=no-device" 1 wifi-on.sh \
    FAKE_ADB_MODE=no-device ADB_PIN_PORT=5555

run "wifi on refused" "RESULT error=wifi-off state=0" 1 wifi-on.sh \
    FAKE_ADB_MODE=wifi-off ADB_PIN_PORT=5555

echo "$failures failure(s)" >&2
[ "$failures" -eq 0 ]
