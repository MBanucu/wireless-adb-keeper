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
    # exec-out streams the XML in one call: no device temp file, no `cat`, and
    # a failed dump leaves the file empty instead of answering from a stale
    # hierarchy. `dump` still waits for the UI to be idle, which is the
    # synchronization point the waiting loops below rely on.
    d exec-out uiautomator dump /dev/tty 2>/dev/null > "$UI_XML"
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

def node_bounds(node):
    m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', attr(node, "bounds"))
    return tuple(map(int, m.groups())) if m else None

if mode == "rows":
    for label, top, bottom in labels:
        for checked, stop, sbottom, _ in switches:
            if stop < bottom and sbottom > top:
                print(f"{'on ' if checked == 'true' else 'off'} {label}")
                break
elif mode == "scroll":
    # Bounds of the largest scrollable node, so a swipe can stay inside the
    # list instead of starting on the fixed header.
    best = None
    for n in nodes:
        if attr(n, "scrollable") != "true":
            continue
        b = node_bounds(n)
        if not b:
            continue
        area = (b[2] - b[0]) * (b[3] - b[1])
        if best is None or area > best[0]:
            best = (area, b)
    if best is None:
        sys.exit(1)
    print(*best[1])
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

# The display is the fallback when the dump has no scrollable node yet.
size=$(d shell wm size 2>/dev/null |
    sed -n 's/^.*: \([0-9][0-9]*\)x\([0-9][0-9]*\)$/\1 \2/p' | tail -n 1)
SCREEN_W=${size% *}
SCREEN_H=${size#* }
case "$SCREEN_W:$SCREEN_H" in
    *[!0-9:]*|:*) SCREEN_W=1440 SCREEN_H=3200 ;;
esac

# One controlled page. On this ROM a VSCROLL unit is 256 px (64 dp) and the
# list viewport is ~2602 px, so 9 units move 2304 px and leave about a row of
# overlap. Unlike a swipe this has no fling, so it cannot skip a screenful;
# a 250 ms swipe used to overshoot and the dump could miss the row entirely.
# The slow swipe stays as a fallback for a build that ignores axis scroll.
SCROLL_UNITS=9
scroll_page() {
    local dir=$1 left=0 top=0 right=$SCREEN_W bottom=$SCREEN_H geom x y from to
    local have_bounds=0
    if geom=$(ask scroll 2>/dev/null) && [ -n "$geom" ]; then
        # shellcheck disable=SC2086
        set -- $geom
        left=$1 top=$2 right=$3 bottom=$4
        have_bounds=1
    fi
    x=$(( (left + right) / 2 ))
    y=$(( (top + bottom) / 2 ))
    if [ "$have_bounds" = 1 ]; then
        if [ "$dir" = down ]; then
            d shell input touchscreen scroll "$x" "$y" --axis "VSCROLL,-$SCROLL_UNITS" \
                && return 0
        else
            d shell input touchscreen scroll "$x" "$y" --axis "VSCROLL,$SCROLL_UNITS" \
                && return 0
        fi
        # A build without axis scroll falls through to the slow swipe below.
    fi
    if [ "$dir" = down ]; then
        from=$(( top + (bottom - top) * 9 / 10 ))
        to=$(( top + (bottom - top) * 15 / 100 ))
    else
        from=$(( top + (bottom - top) * 15 / 100 ))
        to=$(( top + (bottom - top) * 9 / 10 ))
    fi
    d shell input swipe "$x" "$from" "$x" "$to" 700
}
scroll_down() { scroll_page down; }
scroll_up() { scroll_page up; }

# Waits for a labelled node to appear, then leaves its centre in $coords.
# Each iteration is one idle-synchronized dump, so there is no fixed sleep.
wait_node() {
    local want=$1 deadline=$(( $(date +%s) + 8 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        hierarchy
        if coords=$(ask node "$want" 2>/dev/null); then
            return 0
        fi
    done
    return 1
}

# Waits until the autostart rows exist, which is how the screen is known to be
# laid out rather than merely started. Paced by the dumps, not by a timer.
wait_rows() {
    local deadline=$(( $(date +%s) + 8 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        hierarchy
        ask rows 2>/dev/null | grep -q . && return 0
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
# launcher path is the fallback because it depends on the home screen. -S
# restarts the vendor app first: a reused activity keeps its old scroll
# position, and scrolling a long list back to the top costs many dumps.
open_screen() {
    d shell am start -S -n "$AUTOSTART_ACTIVITY" >/dev/null 2>&1
    wait_rows && return 0
    d shell am start -S -n "$MAIN_ACTIVITY" >/dev/null 2>&1
    wait_node "App Management" || fail_screen
    tap_at $coords
    wait_node "Auto Start Management" || fail_screen
    tap_at $coords
    wait_rows || fail_screen
}

# Finds the target row, scrolling down and then back up. Sets $state.
find_row() {
    for _ in $(seq 1 12); do
        hierarchy
        if state=$(ask state "$TARGET" 2>/dev/null); then
            return 0
        fi
        scroll_down
    done
    for _ in $(seq 1 12); do
        hierarchy
        if state=$(ask state "$TARGET" 2>/dev/null); then
            return 0
        fi
        scroll_up
    done
    return 1
}

d shell pm list packages "$MANAGER" 2>/dev/null | grep -qx "package:$MANAGER" \
    || { echo "$MANAGER is not installed; this is not a DokeOS phone" >&2; exit 3; }

require_unlocked
open_screen

if [ "$MODE" = list ]; then
    # Scroll the list once, collecting rows from each screenful.
    {
        for _ in $(seq 1 10); do
            hierarchy
            ask rows
            scroll_down
        done
        hierarchy
        ask rows
    } | awk 'NF && !seen[$0]++'
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

# Polls the target row until its switch reads $1; the dump is the wait.
await_state() {
    local want=$1 deadline=$(( $(date +%s) + 5 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        hierarchy
        state=$(ask state "$TARGET" 2>/dev/null) || continue
        [ "$state" = "$want" ] && return 0
    done
    return 1
}

if [ "$MODE" = off ]; then
    if [ "$state" = "false" ]; then
        echo "$TARGET is already off"
        exit 0
    fi
    coords=$(ask switch "$TARGET") || { echo "the row has no switch" >&2; exit 1; }
    tap_at $coords
    if await_state false; then
        echo "$TARGET is off"
        exit 0
    fi
    echo "$TARGET is still on" >&2
    exit 1
fi

if [ "$state" = "true" ]; then
    echo "$TARGET is already allowed"
    exit 0
fi

coords=$(ask switch "$TARGET") || { echo "the row has no switch" >&2; exit 1; }
tap_at $coords
if await_state true; then
    echo "$TARGET is allowed now"
    exit 0
fi
echo "$TARGET is still off" >&2
exit 1
