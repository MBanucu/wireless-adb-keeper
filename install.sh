#!/data/data/com.termux/files/usr/bin/bash
# install.sh -- install the app and hand it the grants it cannot ask for.
#
#   ./install.sh                      # canonical Gradle APK, else wifidebug.apk
#   ./install.sh path/to/app.apk
#   ./install.sh --autostart          # also enable the DokeOS autostart row
#   PHONE_SERIAL=192.168.77.232:5555 ./install.sh
#
# With no argument the canonical build wins: the Gradle debug APK at
# build-gradle/outputs/apk/debug/wireless-adb-keeper-debug.apk, which includes pairing. If that
# file is absent, the dependency-free bootstrap APK ./build.sh signs is used
# instead (wifidebug.apk). The two are signed with different keys, so installing
# one over the other requires an uninstall first.
#
# A reinstall loses every `pm grant`: POST_NOTIFICATIONS and
# ACCESS_FINE_LOCATION come back ungranted, WRITE_SECURE_SETTINGS and
# Termux's RUN_COMMAND are never granted automatically at all. Without
# RUN_COMMAND the app still runs, still logs, and quietly stops being able to
# pin adbd to port 5555 — which is the moment you have no cable and need it
# most. So the grants live here, next to the install, instead of in a README
# nobody re-reads.
#
# DokeOS adds a second prerequisite that no grant can satisfy: the vendor
# autostart list decides whether the package may receive boot broadcasts at all.
# The install always prints the screen; --autostart drives it, and only on a
# phone that is already unlocked. It does so with the app's own accessibility
# service (one settings write, then `content call --method autostart`, well
# under a second), and falls back to autostart-allow.sh, which walks the same
# screen with uiautomator at about two seconds per dump.
set -uo pipefail

here=$(cd "$(dirname "$0")" && pwd)
APK=""
ALLOW_AUTOSTART=0
SERIAL=${PHONE_SERIAL:-}
PKG=io.github.mbanucu.wirelessadbkeeper
LABEL="Wireless ADB Keeper"
MANAGER=com.blackview.systemmanager

usage() {
    cat <<'USAGE'
install.sh -- install the app and hand it the grants it cannot ask for.

  ./install.sh                      # canonical Gradle APK, else wifidebug.apk
  ./install.sh path/to/app.apk
  ./install.sh --autostart          # also enable the DokeOS autostart row
  PHONE_SERIAL=192.168.77.232:5555 ./install.sh
USAGE
    exit "${1:-0}"
}

while [ $# -gt 0 ]; do
    case "$1" in
        --autostart) ALLOW_AUTOSTART=1 ;;
        -h|--help) usage 0 ;;
        -*) echo "unknown option: $1" >&2; usage 2 ;;
        *)
            [ -z "$APK" ] || { echo "only one APK path is accepted" >&2; usage 2; }
            APK=$1
            ;;
    esac
    shift
done

if [ -z "$APK" ]; then
    for candidate in \
        build-gradle/outputs/apk/debug/wireless-adb-keeper-debug.apk \
        wifidebug.apk \
        ; do
        if [ -f "$candidate" ]; then
            APK=$candidate
            break
        fi
    done
fi

[ -n "$APK" ] || {
    echo "no APK found; run tools/termux-build.sh (canonical) or ./build.sh (bootstrap), or pass the APK path" >&2
    exit 1
}
[ -f "$APK" ] || { echo "no APK at $APK; pass a path to an existing APK" >&2; exit 1; }
echo "installing $APK"

pick_serial() {
    [ -n "$SERIAL" ] && return 0
    SERIAL=$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }' | head -n 1)
    [ -n "$SERIAL" ]
}
pick_serial || { echo "no adb device; plug in the cable or adb connect <ip>:5555" >&2; exit 1; }
d() { adb -s "$SERIAL" "$@"; }

d install -r -d "$APK" || exit 1

# Enables the keeper's own AutostartAccess service, keeping whatever else the
# user already had enabled. This is the one grant the app cannot always make
# for itself: it holds WRITE_SECURE_SETTINGS only because of the pm grant above,
# and the accessibility manager may still refuse the write.
enable_autostart_service() {
    local component="$PKG/io.github.mbanucu.wirelessadbkeeper.AutostartAccess"
    local current
    current=$(d shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r')
    case ":$current:" in
        *":$component:"*) ;;
        *)
            if [ -z "$current" ] || [ "$current" = "null" ]; then
                current="$component"
            else
                current="$current:$component"
            fi
            d shell settings put secure enabled_accessibility_services "$current" >/dev/null 2>&1 \
                || return 1
            ;;
    esac
    d shell settings put secure accessibility_enabled 1 >/dev/null 2>&1 || return 1
    return 0
}

# Every grant here is one the app cannot obtain on its own: three are
# signature/development permissions, and Termux's is only reachable through
# `pm grant` because Termux refuses to run commands for an app it has not
# been told to trust.
for perm in \
    android.permission.WRITE_SECURE_SETTINGS \
    android.permission.ACCESS_FINE_LOCATION \
    android.permission.POST_NOTIFICATIONS \
    com.termux.permission.RUN_COMMAND \
    ; do
    if d shell pm grant "$PKG" "$perm" 2>/dev/null; then
        echo "granted $perm"
    else
        echo "could not grant $perm" >&2
    fi
done

# Report what the app thinks it has, rather than what we asked for.
d shell dumpsys package "$PKG" 2>/dev/null \
    | grep -E 'com\.termux\.permission\.RUN_COMMAND|WRITE_SECURE_SETTINGS' \
    | grep -E 'granted=' | sed 's/^ *//' || true

# The vendor gate is independent of every grant above. It is printed even
# without --autostart, because a reboot is the only way to notice it.
if d shell pm list packages 2>/dev/null | grep -qx "package:$MANAGER" \
    || [ "$(d shell getprop ro.blackview.autostart_manager 2>/dev/null)" = "1" ]; then
    echo
    echo "DokeOS autostart prerequisite (issue #93):"
    echo "  System Manager > App Management > Auto Start Management -> allow \"$LABEL\""
    echo "  Without it the boot broadcasts reach the app only after you open it by hand,"
    echo "  and the monitor and port 5555 wait for that launch."
    if [ "$ALLOW_AUTOSTART" = 1 ]; then
        if d shell dumpsys user 2>/dev/null | grep -q 'RUNNING_UNLOCKED'; then
            allowed=1
            if enable_autostart_service; then
                # The app may not start this activity from the background, but
                # adb can, and the service only has to read and click.
                d shell am start -n "$MANAGER/com.freeme.freemanager.autoboot.AutoStartMainActivity" \
                    >/dev/null 2>&1
                sleep 1
                reply=$(d shell content call --uri "content://$PKG.control" \
                    --method autostart 2>/dev/null)
                case "$reply" in
                    *"result=allowed"*)
                        echo "  autostart allowed (accessibility service, no screen walk)"
                        allowed=0
                        ;;
                    *)
                        echo "  the accessibility service did not switch the row: $reply" >&2
                        ;;
                esac
            else
                echo "  could not enable the accessibility service; trying the screen walk" >&2
            fi
            if [ "$allowed" = 1 ]; then
                if "$here/autostart-allow.sh" --serial "$SERIAL" "$LABEL"; then
                    allowed=0
                fi
            fi
            if [ "$allowed" = 0 ]; then
                echo "  autostart allowed; verify with ./keeper.sh diag | grep oem.autostart"
            else
                echo "autostart was not enabled; open the screen above by hand" >&2
                exit 1
            fi
        else
            echo "  not driving the screen now: the phone is locked." >&2
            echo "  unlock it and run: ./autostart-allow.sh \"$LABEL\"" >&2
        fi
    else
        echo "  opt in to drive the screen: ./install.sh --autostart [APK]"
    fi
fi

# Opening the panel starts MonitorService (and shows the state in the UI).
d shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1 \
    || echo "could not open the panel; start it by hand" >&2

echo "installed on $SERIAL; wireless debugging is pinned on the next check"
