#!/data/data/com.termux/files/usr/bin/bash
# wifi-on.sh -- turns this phone's Wi-Fi radio on through the runner's own adb.
#
# The app cannot do it itself: since Android 10 an app may not toggle Wi-Fi, and
# setWifiEnabled() just returns false. adb's shell user still can, through
# `svc wifi enable`, and Termux holds the adb client whose key this phone has
# already paired. So the app asks Termux to run this script.
#
# The pinned port is tried first, because the app keeps adbd there even when the
# radio is off. If it is not pinned (or the connect fails), any serial that adb
# already holds will do.
#
# Result: the last stdout line is machine readable, for the app:
#   RESULT wifi=on | RESULT error=no-device | RESULT error=wifi-off state=0
set -u

PIN_PORT=${ADB_PIN_PORT:-5555}

# Connecting to loopback keeps working with the radio off, as long as adbd is
# listening, which pin-adb-port.sh keeps true.
adb connect "127.0.0.1:$PIN_PORT" >/dev/null 2>&1 || true
sleep 1
serial=$(adb devices 2>/dev/null | awk -v a="127.0.0.1:$PIN_PORT" '$1 == a && $2 == "device" { print $1; exit }')

if [ -z "${serial:-}" ]; then
    serial=$(adb devices 2>/dev/null | awk 'NR > 1 && $2 == "device" { print $1; exit }')
fi
if [ -z "${serial:-}" ]; then
    echo "wifi-on: no adb device to ask" >&2
    echo "RESULT error=no-device"
    exit 1
fi

adb -s "$serial" shell svc wifi enable
state=$(adb -s "$serial" shell settings get global wifi_on 2>/dev/null | tr -d '\r')
echo "wifi_on=${state:-unknown}"
if [ "${state:-}" = "1" ]; then
    echo "RESULT wifi=on"
    exit 0
fi
echo "RESULT error=wifi-off state=${state:-unknown}"
exit 1
