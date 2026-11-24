#!/data/data/com.termux/files/usr/bin/bash
# Device-free checks for tools/reboot-verdict.sh. A fake keeper.sh prints a
# snapshot + journal fixture, so the pre/post reboot verdict can be exercised
# without adb or a phone.
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
    FIXTURE="$PRE" KEEPER="$KEEPER" REBOOT_VERDICT_STATE="$STATE" \
        bash "$TOOL" record --quiet
}

check() {
    local fixture=$1 state=${2:-$STATE}
    if [ "$#" -ge 2 ]; then shift 2; else shift 1; fi
    FIXTURE="$fixture" KEEPER="$KEEPER" REBOOT_VERDICT_STATE="$state" \
        bash "$TOOL" check "$@"
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

if [ "$failed" -ne 0 ]; then
    printf 'FAIL %d of %d reboot-verdict checks\n' "$failed" "$total"
    exit 1
fi
printf 'PASS %d reboot-verdict checks\n' "$total"
