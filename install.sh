#!/data/data/com.termux/files/usr/bin/bash
# install.sh -- install the app and hand it the grants it cannot ask for.
#
#   ./install.sh                      # canonical Gradle APK, else wifidebug.apk
#   ./install.sh path/to/app.apk
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
set -uo pipefail

APK=${1:-}
SERIAL=${PHONE_SERIAL:-}
PKG=io.github.mbanucu.wirelessadbkeeper

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

# Opening the panel starts MonitorService (and shows the state in the UI).
d shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1 \
    || echo "could not open the panel; start it by hand" >&2

echo "installed on $SERIAL; wireless debugging is pinned on the next check"
