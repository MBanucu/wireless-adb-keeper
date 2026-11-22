#!/usr/bin/env bash
# install.sh -- install the app and hand it the grants it cannot ask for.
#
#   ./install.sh                      # build/wifidebug.apk, first device
#   ./install.sh path/to/app.apk
#   PHONE_SERIAL=192.168.77.232:5555 ./install.sh
#
# A reinstall loses every `pm grant`: POST_NOTIFICATIONS and
# ACCESS_FINE_LOCATION come back ungranted, WRITE_SECURE_SETTINGS and
# Termux's RUN_COMMAND are never granted automatically at all. Without
# RUN_COMMAND the app still runs, still logs, and quietly stops being able to
# pin adbd to port 5555 — which is the moment you have no cable and need it
# most. So the grants live here, next to the install, instead of in a README
# nobody re-reads.
set -uo pipefail

APK=${1:-build/wifidebug.apk}
SERIAL=${PHONE_SERIAL:-}
PKG=com.example.wifidebug

[ -f "$APK" ] || { echo "no APK at $APK; build first (see README)" >&2; exit 1; }

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
