#!/usr/bin/env bash
# autostart-allow.sh -- allowlist a package in DokeOS's autostart manager over adb.
#
#   ./autostart-allow.sh "Phone link proxy"        # by the label DokeOS shows
#   ./autostart-allow.sh --list                    # print the allowlist
#
# DokeOS keeps the list in System Manager behind a signature permission
# (com.freeme.freemanager.permission.ACCESS_INTERFACE), so neither adb nor an
# ordinary app can write it; this drives the screen with uiautomator instead.
# The phone must be unlocked (unlock.sh --check says whether it is).
#
# Exit status: 0 allowed (or already), 1 not found/failed, 2 usage.
set -uo pipefail

SERIAL=${PHONE_SERIAL:-}
MODE=allow
TARGET=""
TIMEOUT=60

usage() {
    sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
}

while [ $# -gt 0 ]; do
    case "$1" in
        --list) MODE=list ;;
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

# Reads the last dumped hierarchy and answers questions about it.
#   node <text>          centre of the first node whose text matches
#   state <text>         checked state of the switch on that node's row
#   switch <text>        centre of that switch
#   rows                 every label with a switch, one per line
hierarchy() {
    d shell uiautomator dump /sdcard/autostart-ui.xml >/dev/null 2>&1
    d shell cat /sdcard/autostart-ui.xml 2>/dev/null > /tmp/autostart-ui.xml
}

ask() {
    python3 - "$@" <<'PY'
import re, sys
mode = sys.argv[1]
arg = sys.argv[2] if len(sys.argv) > 2 else ""
xml = open("/tmp/autostart-ui.xml", encoding="utf-8", errors="replace").read()

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

open_screen() {
    # Start from the app's home screen: a resumed screen would not show the
    # entry point the script taps first.
    d shell am force-stop com.blackview.systemmanager >/dev/null 2>&1
    d shell am start -n com.blackview.systemmanager/.MainActivity >/dev/null 2>&1
    wait_node "App Management" || { echo "cannot find App Management" >&2; exit 1; }
    tap_at $coords
    wait_node "Auto Start Management" || { echo "cannot find Auto Start Management" >&2; exit 1; }
    tap_at $coords
    sleep 1.5
}

open_screen

if [ "$MODE" = list ]; then
    # Scroll the list once, collecting rows from each screenful.
    seen=""
    for _ in 1 2 3 4 5 6; do
        hierarchy
        out=$(ask rows)
        printf '%s\n' "$out"
        scroll_down
        sleep 1
    done | awk 'NF && !seen[$0]++'
    exit 0
fi

# Find the row, scrolling down then back up.
found=0
for _ in 1 2 3 4 5 6 7 8; do
    hierarchy
    if state=$(ask state "$TARGET" 2>/dev/null); then
        found=1
        break
    fi
    scroll_down
    sleep 1
done
if [ "$found" = 0 ]; then
    for _ in 1 2 3 4 5 6 7 8; do
        hierarchy
        if state=$(ask state "$TARGET" 2>/dev/null); then
            found=1
            break
        fi
        scroll_up
        sleep 1
    done
fi
if [ "$found" = 0 ]; then
    echo "no app matching \"$TARGET\" in the list" >&2
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
