#!/data/data/com.termux/files/usr/bin/bash
# autostart-allow.sh -- allowlist a package in DokeOS's autostart manager over adb.
#
#   ./autostart-allow.sh "Wireless ADB Keeper"          # allow it (default)
#   ./autostart-allow.sh --check "Wireless ADB Keeper"  # report only, no toggle
#   ./autostart-allow.sh --off "Wireless ADB Keeper"    # remove the allowance
#   ./autostart-allow.sh --list                         # print the allowlist
#
# The screen is System Manager > App Management > Auto Start Management. DokeOS
# keeps that list behind a signature permission
# (com.freeme.freemanager.permission.ACCESS_INTERFACE), so neither adb nor an
# ordinary app can write it; this drives the screen with uiautomator instead.
# The phone must be unlocked: uiautomator cannot read the keyguard.
#
# Exit status: 0 allowed (or already, or --check found it allowed), 1 not
# found or still off, 2 usage, 3 the list could not be read (locked, or the
# ROM is not showing the screen this script expects).
set -uo pipefail

SERIAL=${PHONE_SERIAL:-}
MODE=allow
TARGET=""
MANAGER=com.blackview.systemmanager
AUTOSTART_ACTIVITY=$MANAGER/com.freeme.freemanager.autoboot.AutoStartMainActivity
MAIN_ACTIVITY=$MANAGER/.MainActivity
SCREEN="System Manager > App Management > Auto Start Management"
DUMP_PATH=/data/local/tmp/autostart-ui.xml

usage() {
    sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
}

while [ $# -gt 0 ]; do
    case "$1" in
        --list) MODE=list ;;
        --off) MODE=off ;;
        --check) MODE=check ;;
        --serial) SERIAL=${2:-}; shift ;;
        -h|--help) usage ;;
        -*) echo "unknown option: $1" >&2; usage ;;
        *) TARGET=$1 ;;
    esac
    shift
done

[ "$MODE" = list ] || [ -n "$TARGET" ] || usage

pick_serial() {
    [ -n "$SERIAL" ] && return 0
    local list count usb
    list=$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
    count=$(printf '%s\n' "$list" | grep -c . || true)
    case "$count" in
        0) echo "no adb device; plug the phone in" >&2; return 1 ;;
        1) SERIAL=$list ;;
        *) usb=$(printf '%s\n' "$list" | grep -v ':' | head -n 1 || true); SERIAL=${usb:-$(printf '%s\n' "$list" | head -n 1)} ;;
    esac
}
pick_serial || exit 1
d() { adb -s "$SERIAL" "$@"; }

# The hierarchy is read with Python below, so it needs a writable temp file.
# $TMPDIR is Termux's private tmp; /tmp is usually not writable from the app uid.
UI_XML=$(mktemp "${TMPDIR:-/data/data/com.termux/files/usr/tmp}/autostart-ui.XXXXXX" 2>/dev/null) \
    || UI_XML=$(mktemp 2>/dev/null) \
    || { echo "cannot create a temporary file; set TMPDIR to a writable directory" >&2; exit 3; }
trap 'rm -f "$UI_XML"' EXIT

# Reads the last dumped hierarchy and answers questions about it.
#   node <text>          centre of the first node whose text matches
#   state <text>         checked state of the switch on that node's row
#   switch <text>        centre of that switch
#   rows                 every label with a switch, one per line
hierarchy() {
    d shell uiautomator dump "$DUMP_PATH" >/dev/null 2>&1
    d shell cat "$DUMP_PATH" 2>/dev/null > "$UI_XML"
}

ask() {
    python3 - "$UI_XML" "$@" <<'PY'
import re, sys
xml_path = sys.argv[1]
mode = sys.argv[2] if len(sys.argv) > 2 else ""
arg = sys.argv[3] if len(sys.argv) > 3 else ""
try:
    with open(xml_path, encoding="utf-8", errors="replace") as f:
        xml = f.read()
except OSError:
    sys.exit(1)

def attr(node, key):
    m = re.search(key + r'="([^"]*)"', node)
    return m.group(1) if m else ""

def centre(bounds):
    m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', bounds)
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2

nodes = [m.group(0) for m in re.finditer(r'<node[^>]*>', xml)]
labels = []
for n in nodes:
    text = attr(n, "text") or attr(n, "content-desc")
    if not text.strip():
        continue
    b = attr(n, "bounds")
    m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', b)
    if not m:
        continue
    labels.append((text, int(m.group(2)), int(m.group(4))))

switches = []
for n in nodes:
    if attr(n, "checkable") == "true":
        b = attr(n, "bounds")
        m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', b)
        if m:
            switches.append((attr(n, "checked"), int(m.group(2)), int(m.group(4)), centre(b)))

def row_for(text):
    for label, top, bottom in labels:
        if arg.lower() in label.lower():
            return label, top, bottom
    return None

if mode == "rows":
    for label, top, bottom in labels:
        for checked, stop, sbottom, _ in switches:
            if stop < bottom and sbottom > top:
                print(f"{'on ' if checked == 'true' else 'off'} {label}")
                break
elif mode == "node":
    hit = row_for(arg)
    if not hit:
        sys.exit(1)
    label, top, bottom = hit
    for n in nodes:
        if (attr(n, "text") or attr(n, "content-desc")) == label:
            c = centre(attr(n, "bounds"))
            if c:
                print(c[0], c[1])
                break
elif mode in ("switch", "state"):
    hit = row_for(arg)
    if not hit:
        sys.exit(1)
    label, top, bottom = hit
    for checked, stop, sbottom, c in switches:
        if stop < bottom and sbottom > top:
            print(c[0], c[1]) if mode == "switch" else print(checked)
            break
    else:
        sys.exit(1)
else:
    sys.exit(2)
PY
}

tap_at() { d shell input tap "$1" "$2"; }
scroll_down() { d shell input swipe 720 2500 720 1100 250; }
scroll_up() { d shell input swipe 720 1100 720 2500 250; }

# Waits for a labelled node to appear, then leaves its centre in $coords.
wait_node() {
    local want=$1 i
    for i in $(seq 1 12); do
        hierarchy
        if coords=$(ask node "$want" 2>/dev/null); then
            return 0
        fi
        sleep 0.5
    done
    return 1
}

fail_screen() {
    echo "cannot read the DokeOS autostart list." >&2
    echo "expected screen: $SCREEN" >&2
    echo "the phone must be unlocked, and this ROM must still use $MANAGER." >&2
    echo "open that screen by hand, then re-run: $0 --check \"<label>\"" >&2
    exit 3
}

require_unlocked() {
    if d shell dumpsys user 2>/dev/null | grep -q 'RUNNING_UNLOCKED'; then
        return 0
    fi
    echo "the phone is locked; unlock it first (uiautomator cannot read the keyguard)." >&2
    echo "expected screen after unlocking: $SCREEN" >&2
    exit 3
}

# Opens the list directly when the ROM exposes the vendor activity; the
# launcher path is the fallback because it depends on the home screen.
open_screen() {
    d shell am start -n "$AUTOSTART_ACTIVITY" >/dev/null 2>&1
    sleep 1
    hierarchy
    if ask rows 2>/dev/null | grep -q .; then
        return 0
    fi
    d shell am start -n "$MAIN_ACTIVITY" >/dev/null 2>&1
    wait_node "App Management" || fail_screen
    tap_at $coords
    wait_node "Auto Start Management" || fail_screen
    tap_at $coords
    sleep 1.5
    hierarchy
    ask rows 2>/dev/null | grep -q . || fail_screen
}

# Finds the target row, scrolling down and then back up. Sets $state.
find_row() {
    local i
    for i in 1 2 3 4 5 6 7 8; do
        hierarchy
        if state=$(ask state "$TARGET" 2>/dev/null); then
            return 0
        fi
        scroll_down
        sleep 1
    done
    for i in 1 2 3 4 5 6 7 8; do
        hierarchy
        if state=$(ask state "$TARGET" 2>/dev/null); then
            return 0
        fi
        scroll_up
        sleep 1
    done
    return 1
}

d shell pm list packages "$MANAGER" 2>/dev/null | grep -qx "package:$MANAGER" \
    || { echo "$MANAGER is not installed; this is not a DokeOS phone" >&2; exit 3; }

require_unlocked
open_screen

if [ "$MODE" = list ]; then
    # Scroll the list once, collecting rows from each screenful.
    for _ in 1 2 3 4 5 6; do
        hierarchy
        ask rows
        scroll_down
        sleep 1
    done | awk 'NF && !seen[$0]++'
    exit 0
fi

if ! find_row; then
    if [ "$MODE" = check ]; then
        echo "no app matching \"$TARGET\" in the autostart list; it may be on a"
        echo "different screen: $SCREEN" >&2
    else
        echo "no app matching \"$TARGET\" in the list on $SCREEN" >&2
        echo "check the installed label with: adb shell pm list packages | grep keeper" >&2
    fi
    exit 1
fi

if [ "$MODE" = check ]; then
    if [ "$state" = "true" ]; then
        echo "$TARGET is allowed"
        exit 0
    fi
    echo "$TARGET is off (not allowed at boot); enable it on $SCREEN" >&2
    exit 1
fi

if [ "$MODE" = off ]; then
    if [ "$state" = "false" ]; then
        echo "$TARGET is already off"
        exit 0
    fi
    coords=$(ask switch "$TARGET") || { echo "the row has no switch" >&2; exit 1; }
    tap_at $coords
    sleep 2
    hierarchy
    state=$(ask state "$TARGET" 2>/dev/null) || { echo "cannot re-read the row" >&2; exit 1; }
    [ "$state" = "false" ] && { echo "$TARGET is off"; exit 0; }
    echo "$TARGET is still on" >&2
    exit 1
fi

if [ "$state" = "true" ]; then
    echo "$TARGET is already allowed"
    exit 0
fi

coords=$(ask switch "$TARGET") || { echo "the row has no switch" >&2; exit 1; }
tap_at $coords
sleep 2
hierarchy
state=$(ask state "$TARGET" 2>/dev/null) || { echo "could not re-read the row" >&2; exit 1; }
if [ "$state" = "true" ]; then
    echo "$TARGET is allowed now"
    exit 0
fi
echo "$TARGET is still off" >&2
exit 1
