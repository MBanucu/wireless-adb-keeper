#!/data/data/com.termux/files/usr/bin/bash
# Device-free checks for tools/reboot-verdict.sh. A fake keeper.sh prints a
# snapshot + journal fixture and a fake adb scripts the pre-unlock samples, so
# the pre/post reboot verdict can be exercised without adb or a phone.
set -euo pipefail

ROOT=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd) || exit 1
TOOL="$ROOT/tools/reboot-verdict.sh"
TMP=$(mktemp -d) || exit 1
trap 'rm -rf "$TMP"' EXIT

KEEPER="$TMP/keeper.sh"
cat >"$KEEPER" <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
exec cat "$FIXTURE"
EOF
chmod +x "$KEEPER"

# Every check runs with an explicit adb: the host may have a real phone
# attached, and the pre-unlock sampler must never read it by accident.
ADB_NONE="$TMP/adb-none"
cat >"$ADB_NONE" <<'EOF'
#!/usr/bin/env bash
# No device at all: every adb call fails.
exit 1
EOF
chmod +x "$ADB_NONE"

SCENARIOS="$TMP/scenarios"
mkdir -p "$SCENARIOS"
export FAKE_SCENARIO_DIR="$SCENARIOS"

# Fake adb for the pre-unlock sampler. Each `shell` call returns the next
# scenario block, so the boot timeline is scripted without a device or sleeps.
ADB_FAKE="$TMP/adb-fake"
cat >"$ADB_FAKE" <<'EOF'
#!/usr/bin/env bash
if [ "${1:-}" = "-s" ]; then shift 2; fi
case "${1:-}" in
    shell)
        case "${2:-}" in
            "cat /proc/sys/kernel/random/boot_id")
                [ -n "${FAKE_KERNEL_ID:-}" ] || exit 1
                printf '%s\n' "$FAKE_KERNEL_ID"
                ;;
            *)
                dir=${FAKE_SCENARIO_DIR:?}
                n=$(( $(cat "$dir/count" 2>/dev/null || echo 0) + 1 ))
                printf '%s\n' "$n" >"$dir/count"
                f=$dir/$n
                [ -f "$f" ] || f=$dir/last
                [ -f "$f" ] || exit 1
                cat "$f"
                ;;
        esac
        ;;
    connect)
        printf '%s\n' "${FAKE_CONNECT_OUT:-connected to $2}"
        case "${FAKE_CONNECT_OUT:-}" in *failed*) exit 1 ;; esac
        ;;
    disconnect) printf 'disconnected %s\n' "${2:-}" ;;
    *) exit 1 ;;
esac
EOF
chmod +x "$ADB_FAKE"

PRE="$TMP/pre.txt"
POST="$TMP/post.txt"
STATE="$TMP/state"

cat >"$PRE" <<'EOF'
app_version=1.9 (10)
android=15 (sdk 35)
boot_id=100
uptime_s=70000
process_started_after_boot_s=69000
unlocked=true
mode=auto
service=running
port_5555=listening on 5555
pin_result=none
wifi_result=none
last_result=suggestion default: active
wifi_mode=applied (start command)
wifi_passphrase=saved=1 missing=0 unreadable=0
suggestion_held=1
journal_events=42
-- journal (oldest first) --
2026-11-24T09:00:00Z 69990s info svc.start unlocked=true
2026-11-24T09:00:01Z 69991s info svc.mode service created: auto-connect, suggestion default: active (status 0)
EOF

total=0
failed=0

expect() {
    local want=$1 name=$2
    shift 2
    total=$((total + 1))
    set +e
    "$@" >"$TMP/out" 2>"$TMP/err"
    local got=$?
    set -e
    if [ "$got" -ne "$want" ]; then
        failed=$((failed + 1))
        printf 'FAIL %s: exit %d, wanted %d\n' "$name" "$got" "$want"
        sed 's/^/  /' "$TMP/out" "$TMP/err"
        return 0
    fi
    printf 'ok   %s\n' "$name"
}

contains() {
    # contains <text> <name>: the previous expect's stdout must name it.
    total=$((total + 1))
    if grep -qF -- "$1" "$TMP/out"; then
        printf 'ok   %s\n' "$2"
    else
        failed=$((failed + 1))
        printf 'FAIL %s: output does not contain %s\n' "$2" "$1"
        sed 's/^/  /' "$TMP/out"
    fi
}

record() {
    FIXTURE="$PRE" KEEPER="$KEEPER" REBOOT_VERDICT_STATE="${RECORD_STATE:-$STATE}" \
        ADB="${RECORD_ADB:-$ADB_NONE}" bash "$TOOL" record --quiet
}

check() {
    local fixture=$1 state=${2:-$STATE}
    if [ "$#" -ge 2 ]; then shift 2; else shift 1; fi
    FIXTURE="$fixture" KEEPER="$KEEPER" REBOOT_VERDICT_STATE="$state" \
        ADB="${CHECK_ADB:-$ADB_NONE}" REBOOT_VERDICT_POLL_INTERVAL=0 \
        bash "$TOOL" check "$@"
}

scenario() {
    # scenario <n> <uptime> <ce> <ip|-> <tls-prop> <listen ports...>
    local number=$1 uptime=$2 ce=$3 ip=$4 tls=$5
    shift 5
    {
        printf 'ce=%s\n' "$ce"
        printf 'tcp_port=5555\n'
        printf 'tls_port=%s\n' "$tls"
        printf 'uptime=%s\n' "$uptime"
        printf 'kernel_boot_id=kernel-new\n'
        printf 'ip_ok=1\n'
        [ "$ip" = - ] || printf 'wlan_ip=%s\n' "$ip"
        printf 'ss_ok=%s\n' "${SCENARIO_SS_OK:-1}"
        local port
        for port in "$@"; do printf 'listen=%s\n' "$port"; done
    } >"$SCENARIOS/$number"
}

reset_scenarios() {
    rm -f "$SCENARIOS/count"
}

preunlock_scenarios() {
    # preunlock_scenarios <open|closed>: 5555's state during the locked window.
    local state=$1
    local -a listeners=(53 33801)
    [ "$state" = open ] && listeners=(53 33801 5555)
    reset_scenarios
    scenario 1 20 '' - '' 53 5037 8022
    scenario 2 28 '' 192.168.77.232 33801 "${listeners[@]}"
    scenario 3 46 '' 192.168.77.232 33801 "${listeners[@]}"
    scenario 4 61 '' 192.168.77.232 33801 "${listeners[@]}"
    scenario 5 91 '' 192.168.77.232 33801 "${listeners[@]}"
    scenario 6 139 true 192.168.77.232 33801 53 33801
    cp "$SCENARIOS/6" "$SCENARIOS/last"
}

postunlock_scenario() {
    reset_scenarios
    scenario 1 200 true 192.168.77.232 '' 53 5555 33801
    cp "$SCENARIOS/1" "$SCENARIOS/last"
}

expect 0 "record a pre-reboot state" record

# The post-reboot journal has to be newer than the recorded wall clock, so the
# fixtures are generated after `record` instead of hard-coding a date.
recorded=$(sed -n 's/^recorded_at=//p' "$STATE")
boot_ts=$(python3 - "$recorded" <<'PY'
import datetime
import sys
t = datetime.datetime.strptime(sys.argv[1], "%Y-%m-%dT%H:%M:%SZ")
print((t + datetime.timedelta(minutes=1)).strftime("%Y-%m-%dT%H:%M:%SZ"))
PY
)

# A journal line's timestamp is its wall clock, so on one boot every line
# shares the same `timestamp - uptime` boot instant. Build them that way.
at() {
    date -u -d "$boot_ts + $1 seconds" +%Y-%m-%dT%H:%M:%SZ
}

boot_fixture() {
    # boot_fixture <out> <marker-delay-s> <process-delay-s> <unlock phrase>
    local out=$1 marker=$2 process=$3 unlock=$4
    cat >"$out" <<EOF
app_version=1.9 (10)
android=15 (sdk 35)
boot_id=101
uptime_s=$((marker + 50))
process_started_after_boot_s=$process
unlocked=true
mode=auto
service=running
port_5555=listening on 5555
pin_result=none
wifi_result=none
last_result=suggestion default: active
wifi_mode=applied (user unlocked)
wifi_passphrase=saved=1 missing=0 unreadable=0
suggestion_held=1
journal_events=44
-- journal (oldest first) --
2026-11-24T09:00:00Z 69990s info svc.start unlocked=true
2026-11-24T09:00:01Z 69991s info svc.mode service created: auto-connect, suggestion default: active (status 0)
$(at "$marker") ${marker}s info boot.received android.intent.action.LOCKED_BOOT_COMPLETED $unlock
$(at "$marker") ${marker}s info boot.jobs periodic + immediate ensure scheduled
$(at $((marker + 3))) $((marker + 3))s info svc.start unlocked=true
$(at $((marker + 3))) $((marker + 3))s info svc.mode user unlocked: auto-connect, suggestion default: active (status 0)
$(at $((marker + 3))) $((marker + 3))s info pin.state listening on 5555
$(at $((marker + 4))) $((marker + 4))s info wifi.suggest.job monitor stopped; re-registered the saved suggestions: suggestion default: active (status 0)
$(at $((marker + 5))) $((marker + 5))s info boot.received android.intent.action.BOOT_COMPLETED after unlock
EOF
}

# A clean boot: the receiver runs ~27 s after boot and before unlock, the
# service starts ~30 s in.
boot_fixture "$POST" 27 29 'before unlock'

expect 0 "a normal post-reboot state passes" check "$POST"
expect 1 "the same boot is rejected" check "$PRE"
expect 3 "a missing record is blocked" check "$POST" "$TMP/missing"

grep -v ' svc\.mode ' "$POST" >"$TMP/no-mode"
expect 1 "a missing svc.mode fails" check "$TMP/no-mode"

sed 's/^wifi_passphrase=.*/wifi_passphrase=saved=0 missing=1 unreadable=1/' \
    "$POST" >"$TMP/unreadable"
expect 1 "an unreadable passphrase fails" check "$TMP/unreadable"

grep -v ' boot\.received ' "$POST" >"$TMP/no-boot"
expect 1 "a missing boot.received fails" check "$TMP/no-boot"

sed 's/ svc\.mode user unlocked: auto-connect, suggestion default: active (status 0)/ svc.mode user unlocked: auto-connect, suggestion default: no password (status -2)/' \
    "$POST" | grep -v ' wifi\.suggest\.job ' >"$TMP/no-suggestion"
expect 1 "an inactive suggestion fails" check "$TMP/no-suggestion"

# The boot path logs LOCKED_BOOT_COMPLETED, then svc.start/svc.mode, then
# BOOT_COMPLETED. Anchoring on the last boot.received would skip those lines.
sed '/ svc\.mode user unlocked: auto-connect, suggestion default: active (status 0)/d' \
    "$POST" >"$TMP/modern-no-mode"
expect 1 "svc.mode between the two boot markers is required" check "$TMP/modern-no-mode"

# Diagnostics append a "-- previous ... --" section holding older events. A
# failure there must not fail the fresh boot (the regression this guards).
{
    cat "$POST"
    printf '%s\n' '-- previous build unknown, boot unknown: 200 events from the previous install --'
    printf '%s\n' '2026-11-24T08:15:13Z 57005s warn wifi.suggest.no_password default WP22: auto-connect needs the password'
    printf '%s\n' '2026-11-24T08:15:13Z 57005s info svc.mode service created: auto-connect, suggestion default: enter Wi-Fi password (status -2)'
} >"$TMP/previous"
expect 0 "the previous-install journal section is ignored" check "$TMP/previous"

# A failure logged after the current boot marker still fails.
{
    cat "$POST"
    printf '%s\n' "$(at 54) 54s error wifi.suggest.decrypt_failed default WP22"
} >"$TMP/boot-failure"
expect 1 "a boot-critical failure after the marker fails" check "$TMP/boot-failure"

# The 2026-11-24 shape: the app-targeted boot broadcasts were enqueued only
# when the app was opened, 238 s after boot (DokeOS autostart). Every older
# check passed; the start latency is the only evidence.
boot_fixture "$TMP/late-start" 238 238 'after unlock'
expect 1 "a boot marker 238s late fails" check "$TMP/late-start"
contains '238s' "the late start names the measured delay"
contains 'launched by hand' "the late start says it looks manual"
contains 'Auto Start Management' "the late start names the DokeOS gate"
contains 'Doze/battery' "the late start names the Doze gate"

# A phone that stays locked delays the marker legitimately: warning, not a
# failure, while the process still starts inside the bound.
boot_fixture "$TMP/locked" 60 60 'after unlock'
expect 0 "a legitimately locked boot does not fail on lock alone" check "$TMP/locked"
contains 'warn' "the locked boot is reported as a warning"
contains "after unlock" "the locked boot names the unlock state"

# The bound is overridable; with a 300 s limit the same boot only warns.
expect 0 "the start-delay bound is overridable" \
    check "$TMP/late-start" "$STATE" --max-start-delay 300

# A non-numeric bound is documented as a usage error, so it is checked here.
expect 2 "a non-numeric start-delay bound is a usage error" \
    check "$TMP/late-start" "$STATE" --max-start-delay abc

# A process that restarted long after an on-time marker is not a blocked boot:
# the journal already proves the broadcast arrived at boot, so the gate is fine.
boot_fixture "$TMP/restarted" 27 240 'before unlock'
expect 0 "a restarted process after an on-time marker passes" \
    check "$TMP/restarted" "$STATE"
contains 'restarted after boot' "the restart is reported as the reason"
contains 'Not an autostart-gate failure' "the restart does not blame the boot gate"

expect 0 "a passing verdict is machine-readable" check "$POST" "$STATE" --json
python3 - "$TMP/out" <<'PY'
import json
import sys
with open(sys.argv[1]) as f:
    data = json.load(f)
assert data["schema"] == 1
assert data["ok"] is True
assert data["verdict"] == "pass"
assert data["boot_id_before"] == "100"
assert data["boot_id_after"] == "101"
assert data["max_start_delay_s"] == 90
assert data["start_delay_s"] == 29
assert data["boot_marker_delay_s"] == 27
assert isinstance(data["boot_epoch"], int)
PY

expect 1 "the late start is machine-readable" \
    check "$TMP/late-start" "$STATE" --json
python3 - "$TMP/out" <<'PY'
import json
import sys
with open(sys.argv[1]) as f:
    data = json.load(f)
assert data["ok"] is False
assert data["verdict"] == "fail"
assert data["start_delay_s"] == 238
assert data["boot_marker_delay_s"] == 238
PY

# Boot 135: Wi-Fi is up at 28 s, 5555 stays closed until after the 139 s
# unlock, and the pin only listens at 174 s. The sampler has to say so from
# the USB samples, because the post-unlock snapshot cannot.
boot_fixture "$TMP/preunlock-post" 27 29 'before unlock'
{
    grep -v ' pin\.state listening' "$TMP/preunlock-post"
    printf '%s\n' "$(at 139) 139s info svc.unlocked user unlocked"
    printf '%s\n' "$(at 174) 174s info pin.state listening on 5555"
} >"$TMP/preunlock-journal"

CHECK_ADB=$ADB_FAKE
preunlock_scenarios closed
expect 0 "a pre-unlock boot with a late pin still passes the post-unlock checks" \
    check "$TMP/preunlock-journal" "$STATE" --preunlock-wait 30
contains 'preunlock.wifi=up' "the samples record Wi-Fi up"
contains 'preunlock.tls=33801' "the samples record the TLS port"
contains 'preunlock.port5555=closed' "the samples record 5555 closed"
contains 'target=27 at=28' "the first sample is timed from boot"
contains 'unlock_at_s=139' "the unlock time comes from the journal"
contains 'pin_at_s=174' "the pin time comes from the journal"

# The strict flag turns the observed closed port into a failure. It is off by
# default, as the issue requires until the boot pin lands.
preunlock_scenarios closed
expect 1 "--require-preunlock-port fails while 5555 is closed before unlock" \
    check "$TMP/preunlock-journal" "$STATE" --preunlock-wait 30 --require-preunlock-port
contains 'port 5555 is open before unlock' "the failure names the pre-unlock port"
contains 'boot pin has not landed' "the failure names the missing boot pin"

# With 5555 listening while the phone was still locked, the requirement holds.
preunlock_scenarios open
expect 0 "--require-preunlock-port passes once 5555 listens before unlock" \
    check "$TMP/preunlock-journal" "$STATE" --preunlock-wait 30 --require-preunlock-port
contains 'preunlock.port5555=open' "the summary records the pre-unlock listener"

# If ss is unavailable, every locked sample has port5555=unknown. That is not
# proof that 5555 was open, so the strict flag must stay "not measured".
SCENARIO_SS_OK=0
preunlock_scenarios closed
SCENARIO_SS_OK=1
expect 0 "an unavailable socket table is not a pre-unlock pass" \
    check "$TMP/preunlock-journal" "$STATE" --preunlock-wait 30 --require-preunlock-port
contains 'not measured' "the unavailable sample says it was not measured"
contains 'preunlock.port5555=unknown' "the summary records the unknown port"

preunlock_scenarios closed
expect 0 "the pre-unlock samples are machine-readable" \
    check "$TMP/preunlock-journal" "$STATE" --preunlock-wait 30 --json
python3 - "$TMP/out" <<'PY'
import json
import sys
with open(sys.argv[1]) as f:
    data = json.load(f)
assert data["ok"] is True
assert data["preunlock_wifi"] == "up"
assert data["preunlock_tls"] == 33801
assert data["preunlock_port5555"] == "closed"
assert data["unlock_at_s"] == 139
assert data["pin_at_s"] == 174
samples = data["preunlock_samples"]
assert [s["target"] for s in samples] == ["27", "45", "60", "90", "unlock"]
assert [s["at_s"] for s in samples] == [28, 46, 61, 91, 139]
assert samples[0]["wifi"] == "up"
assert samples[0]["port5555"] == "closed"
assert samples[0]["tls_port"] == 33801
assert samples[0]["tls_probe"] == "ok"
assert samples[-1]["ce_available"] is True
PY

# A check started after the unlock cannot see the window. That is a warning
# under the strict flag, not a fabricated pass or a failure.
postunlock_scenario
expect 0 "a post-unlock-only check warns instead of inventing samples" \
    check "$POST" "$STATE" --require-preunlock-port
contains 'not measured' "the strict flag says the window was not measured"

# A check run before the reboot must not present the old boot as pre-unlock
# evidence; the kernel boot id recorded by `record` is the proof.
RECORD_STATE="$TMP/state-kernel"
RECORD_ADB="$ADB_FAKE"
export FAKE_KERNEL_ID=11111111-2222-3333-4444-555555555555
expect 0 "record keeps the kernel boot id" record
unset FAKE_KERNEL_ID
reset_scenarios
{
    printf 'ce=\n'
    printf 'tcp_port=5555\n'
    printf 'tls_port=\n'
    printf 'uptime=30\n'
    printf 'kernel_boot_id=11111111-2222-3333-4444-555555555555\n'
    printf 'ip_ok=1\n'
    printf 'wlan_ip=192.168.77.232\n'
    printf 'ss_ok=1\n'
    printf 'listen=53\n'
} >"$SCENARIOS/1"
cp "$SCENARIOS/1" "$SCENARIOS/last"
expect 0 "a check before the reboot does not sample the old boot" \
    check "$POST" "$TMP/state-kernel" --preunlock-wait 5
contains 'has not rebooted' "the unchanged kernel boot id is named"
unset RECORD_STATE RECORD_ADB
unset CHECK_ADB

expect 2 "a non-numeric pre-unlock wait is a usage error" \
    check "$POST" "$STATE" --preunlock-wait abc

if [ "$failed" -ne 0 ]; then
    printf 'FAIL %d of %d reboot-verdict checks\n' "$failed" "$total"
    exit 1
fi
printf 'PASS %d reboot-verdict checks\n' "$total"
