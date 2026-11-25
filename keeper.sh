#!/data/data/com.termux/files/usr/bin/bash
# Drive the keeper from a shell instead of tapping through the app.
#
#   ./keeper.sh status
#   ./keeper.sh enable
#   ./keeper.sh wifi-on
#   ./keeper.sh start | stop
#   ./keeper.sh connect
#   ./keeper.sh mode auto | mode local
#   ./keeper.sh profiles      (id, SSID, security, passphrase state, priority)
#   ./keeper.sh save SSID [PASSWORD]
#   ./keeper.sh save --profile ID SSID [PASSWORD]
#   ./keeper.sh profile-rm ID
#   ./keeper.sh priority ID N
#   ./keeper.sh pin
#   ./keeper.sh pubkey
#   ./keeper.sh pair CODE      (6-digit code from Developer options)
#   ./keeper.sh pair-verify    (opens shell:id over the trusted port)
#   ./keeper.sh schedule
#   ./keeper.sh diag    (status snapshot + recent events)
#   ./keeper.sh autostart [LABEL]  (switch this app's DokeOS autostart row on)
#
# The app exposes a content provider for this; `content call` starts the app
# even when it was force-stopped, and prints the reply synchronously.
set -euo pipefail

PKG=io.github.mbanucu.wirelessadbkeeper
AUTH="content://$PKG.control"
ADB=${ADB:-adb}

if [ -n "${PHONE_SERIAL:-}" ]; then
    TARGET=(-s "$PHONE_SERIAL")
else
    TARGET=()
fi

call() {
    "$ADB" "${TARGET[@]}" shell content call --uri "$AUTH" "$@" \
        | sed -e 's/^Result: Bundle\[{//' -e 's/}\]$//' -e 's/^code=[0-9]*, data=//'
}

# adb shell joins its arguments with spaces and the remote shell splits them
# again, so a value with spaces has to be quoted for that second parse.
remote_quote() {
    printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"
}

usage() {
    sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//'
    exit "${1:-0}"
}

command=${1:-status}
[ $# -gt 0 ] && shift

case "$command" in
    status)   call --method status ;;
    enable)   call --method enable ;;
    wifi-on)  call --method wifi_on ;;
    connect)
        # Asking the service to connect needs the service to exist first.
        if ! call --method status | grep -qx 'service=running'; then
            "$ADB" "${TARGET[@]}" shell am start-foreground-service \
                -n "$PKG/.MonitorService" >/dev/null
            sleep 2
        fi
        call --method connect
        ;;
    stop)     call --method service_stop ;;
    pin)      call --method pin_port ;;
    pubkey)   call --method pubkey ;;
    pair)
        # The pairing dialog has to stay open while this runs; the app finds
        # its port over mDNS and never guesses it. The code is not echoed.
        if [ $# -lt 1 ] || [ -z "${1:-}" ]; then
            echo "usage: $0 pair CODE" >&2
            exit 2
        fi
        call --method pair --extra "code:s:$1"
        ;;
    pair-verify) call --method pair_verify ;;
    schedule) call --method schedule ;;
    diag)     call --method diag ;;
    autostart)
        # The app's own accessibility service clicks the vendor row; it needs no
        # screen walk and no adb help, so this is the fast path when the row is
        # off. The reply says what it saw: result=allowed|blocked, before=, after=.
        if [ $# -gt 0 ] && [ -n "$1" ]; then
            call --method autostart --arg "$(remote_quote "$1")"
        else
            call --method autostart
        fi
        ;;
    start)
        # Android refuses a background app starting its own foreground service,
        # but the shell may start it explicitly.
        "$ADB" "${TARGET[@]}" shell am start-foreground-service -n "$PKG/.MonitorService" \
            | tail -n 1
        ;;
    mode)
        case "${1:-}" in
            auto|local) call --method set_mode --arg "$1" ;;
            *) echo "usage: $0 mode auto|local" >&2; exit 2 ;;
        esac
        ;;
    profiles) call --method profiles ;;
    save)
        profile=
        if [ "${1:-}" = "--profile" ]; then
            if [ $# -lt 3 ] || [ -z "${2:-}" ] || [ -z "${3:-}" ]; then
                echo "usage: $0 save --profile ID SSID [PASSWORD]" >&2
                exit 2
            fi
            profile=$2
            shift 2
        fi
        if [ $# -lt 1 ] || [ -z "${1:-}" ]; then
            echo "usage: $0 save SSID [PASSWORD]" >&2
            exit 2
        fi
        if [ -n "$profile" ]; then
            if [ $# -ge 2 ]; then
                call --method save_wifi --arg "$1" --extra "id:s:$profile" \
                    --extra "passphrase:s:$2"
            else
                call --method save_wifi --arg "$1" --extra "id:s:$profile"
            fi
        elif [ $# -ge 2 ]; then
            call --method save_wifi --arg "$1" --extra "passphrase:s:$2"
        else
            call --method save_wifi --arg "$1"
        fi
        ;;
    profile-rm)
        if [ $# -lt 1 ] || [ -z "${1:-}" ]; then
            echo "usage: $0 profile-rm ID" >&2
            exit 2
        fi
        call --method remove_profile --arg "$1"
        ;;
    priority)
        if [ $# -lt 2 ] || [ -z "${1:-}" ] || [ -z "${2:-}" ]; then
            echo "usage: $0 priority ID N" >&2
            exit 2
        fi
        call --method set_priority --arg "$1" --extra "priority:s:$2"
        ;;
    -h|--help|help) usage 0 ;;
    *) echo "unknown command: $command" >&2; usage 2 ;;
esac
