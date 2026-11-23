#!/data/data/com.termux/files/usr/bin/bash
# wifi-on.sh -- turns this phone's Wi-Fi radio on through the runner's own adb.
#
# The app cannot do it itself: since Android 10 an app may not toggle Wi-Fi, and
# setWifiEnabled() just returns false. adb's shell user still can, through
# `svc wifi enable`, and Termux holds the adb client whose key this phone has
# already paired. So the app asks Termux to run this script.
#
# Loopback is deliberate: it keeps working while the radio is off, as long as
# adbd is listening, which pin-adb-port.sh keeps true.
set -u

serial=$(adb devices 2>/dev/null | awk 'NR > 1 && $2 == "device" { print $1; exit }')
if [ -z "${serial:-}" ]; then
    adb connect 127.0.0.1:5555 >/dev/null 2>&1 || true
    sleep 1
    serial=$(adb devices 2>/dev/null | awk 'NR > 1 && $2 == "device" { print $1; exit }')
fi
if [ -z "${serial:-}" ]; then
    echo "wifi-on: no adb device to ask" >&2
    exit 1
fi

adb -s "$serial" shell svc wifi enable
state=$(adb -s "$serial" shell settings get global wifi_on 2>/dev/null | tr -d '\r')
echo "wifi_on=${state:-unknown}"
[ "${state:-}" = "1" ]
