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

cat >"$POST" <<'EOF'
app_version=1.9 (10)
android=15 (sdk 35)
boot_id=101
uptime_s=60
process_started_after_boot_s=45
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
2026-11-24T10:00:00Z 50s info boot.received android.intent.action.BOOT_COMPLETED after unlock
2026-11-24T10:00:01Z 51s info boot.jobs periodic + immediate ensure scheduled
2026-11-24T10:00:02Z 52s info svc.start unlocked=true
2026-11-24T10:00:02Z 52s info svc.mode user unlocked: auto-connect, suggestion default: active (status 0)
2026-11-24T10:00:02Z 52s info pin.state listening on 5555
2026-11-24T10:00:03Z 53s info wifi.suggest.job monitor stopped; re-registered the saved suggestions: suggestion default: active (status 0)
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

expect 0 "a passing verdict is machine-readable" check "$POST" "$STATE" --json
python3 - "$TMP/out" <<'PY'
import json
import sys
with open(sys.argv[1]) as f:
    data = json.load(f)
assert data["schema"] == 1
assert data["ok"] is True
assert data["boot_id_before"] == "100"
assert data["boot_id_after"] == "101"
PY

if [ "$failed" -ne 0 ]; then
    printf 'FAIL %d of %d reboot-verdict checks\n' "$failed" "$total"
    exit 1
fi
printf 'PASS %d reboot-verdict checks\n' "$total"
