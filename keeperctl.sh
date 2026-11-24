#!/data/data/com.termux/files/usr/bin/bash
# Drive the keeper from Termux without adb.
#
#   ./keeperctl.sh status
#   ./keeperctl.sh enable
#   ./keeperctl.sh wifi-on
#   ./keeperctl.sh connect | stop
#   ./keeperctl.sh mode auto | mode local
#   ./keeperctl.sh profiles
#   ./keeperctl.sh save SSID [PASSWORD]
#   ./keeperctl.sh save --profile ID SSID [PASSWORD]
#   ./keeperctl.sh profile-rm ID
#   ./keeperctl.sh priority ID N
#   ./keeperctl.sh schedule
#   ./keeperctl.sh diag
#
# The one-time token lives in ~/.config/wireless-adb-keeper/token (mode 600).
# `content call` cannot be used here: the `content` command requires
# ACCESS_CONTENT_PROVIDERS_EXTERNALLY, which only shell and root hold, so it
# fails before the app is reached. An explicit ordered broadcast is allowed;
# ControlReceiver applies the token gate and the same dispatch as
# ControlProvider. The token is never printed.
set -euo pipefail

PKG=io.github.mbanucu.wirelessadbkeeper
AUTH="content://$PKG.control"
ACTION="$PKG.CONTROL"
TOKEN_FILE=${KEEPER_TOKEN_FILE:-$HOME/.config/wireless-adb-keeper/token}
AM=${AM:-/system/bin/am}

usage() {
    sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'
    exit "${1:-0}"
}

read_token() {
    if [ ! -f "$TOKEN_FILE" ]; then
        cat >&2 <<EOF
no control token at $TOKEN_FILE
mint one once, from adb (see "Control from Termux" in README.md):
  mkdir -p "$(dirname "$TOKEN_FILE")" && chmod 700 "$(dirname "$TOKEN_FILE")"
  umask 077
  adb shell content call --uri $AUTH --method token \\
    | sed -n 's/.*data=token=\([0-9a-f]\{64\}\).*/\1/p' > "$TOKEN_FILE"
  chmod 600 "$TOKEN_FILE"
EOF
        exit 1
    fi
    local mode owner
    mode=$(stat -c %a "$TOKEN_FILE" 2>/dev/null || true)
    owner=$(stat -c %u "$TOKEN_FILE" 2>/dev/null || true)
    if [ "$owner" != "$(id -u)" ] || { [ "$mode" != 600 ] && [ "$mode" != 0600 ]; }; then
        echo "refusing $TOKEN_FILE: owner ${owner:-?}, mode ${mode:-?}; expected $(id -u) and 600" >&2
        echo "fix: chmod 600 '$TOKEN_FILE'" >&2
        exit 1
    fi
    local value
    value=$(tr -d '[:space:]' < "$TOKEN_FILE")
    if [ "${#value}" -ne 64 ] || [[ "$value" == *[!0-9a-f]* ]]; then
        echo "refusing $TOKEN_FILE: not 64 lowercase hex characters" >&2
        exit 1
    fi
    printf '%s' "$value"
}

call() {
    local method=$1
    shift
    local token
    token=$(read_token)
    local raw
    # `am` prints the ordered-broadcast result. It is a shell script that runs
    # `cmd activity`, so /system/bin has to be on PATH even when Termux's own
    # PATH does not include it.
    if ! raw=$(PATH="/system/bin:$PATH" "$AM" broadcast --user 0 \
            -n "$PKG/.ControlReceiver" -a "$ACTION" \
            --es method "$method" --es token "$token" "$@" 2>&1); then
        printf '%s\n' "$raw" >&2
        exit 1
    fi
    # A shell pattern rather than `grep -q`: with `set -o pipefail`, a large
    # result can make the writer see SIGPIPE when grep exits on the match, and
    # the wrapper would wrongly call that "no reply".
    case "$raw" in
        *'Broadcast completed: result='*'data="'*) ;;
        *)
            printf '%s\n' "$raw" >&2
            echo "error=no reply from the app; open it or run ./keeper.sh status once (adb)" >&2
            exit 1
            ;;
    esac
    local code data
    code=$(sed -n 's/^Broadcast completed: result=\([-0-9][0-9]*\).*/\1/p' <<< "$raw")
    # The result starts on the "Broadcast completed" line and ends at the final
    # quote. If this platform escaped the newlines instead of printing them,
    # turn the literal \n sequences back into lines.
    data=$(awk '
        /^Broadcast completed: result=/ {
            sub(/^Broadcast completed: result=[-0-9]*, data="/, "")
            found = 1
        }
        found { line[++n] = $0 }
        END {
            if (n == 0) exit
            sub(/"$/, "", line[n])
            for (i = 1; i <= n; i++) print line[i]
        }' <<< "$raw")
    case "$data" in
        *'\n'*) data=$(printf '%s' "$data" | sed 's/\\n/\n/g') ;;
    esac
    if [ -n "$data" ]; then
        printf '%s\n' "$data"
    fi
    [ "$code" = 0 ] || exit 1
}

command=${1:-status}
[ $# -gt 0 ] && shift

case "$command" in
    status)   call status ;;
    enable)   call enable ;;
    wifi-on)  call wifi_on ;;
    connect)  call connect ;;
    stop)     call service_stop ;;
    schedule) call schedule ;;
    diag)     call diag ;;
    mode)
        case "${1:-}" in
            auto|local) call set_mode --es mode "$1" ;;
            *) echo "usage: $0 mode auto|local" >&2; exit 2 ;;
        esac
        ;;
    profiles) call profiles ;;
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
                call save_wifi --es arg "$1" --es id "$profile" \
                    --es passphrase "$2"
            else
                call save_wifi --es arg "$1" --es id "$profile"
            fi
        elif [ $# -ge 2 ]; then
            call save_wifi --es arg "$1" --es passphrase "$2"
        else
            call save_wifi --es arg "$1"
        fi
        ;;
    profile-rm)
        if [ $# -lt 1 ] || [ -z "${1:-}" ]; then
            echo "usage: $0 profile-rm ID" >&2
            exit 2
        fi
        call remove_profile --es arg "$1"
        ;;
    priority)
        if [ $# -lt 2 ] || [ -z "${1:-}" ] || [ -z "${2:-}" ]; then
            echo "usage: $0 priority ID N" >&2
            exit 2
        fi
        call set_priority --es arg "$1" --es priority "$2"
        ;;
    start|pin|pubkey|pair|pair-verify|token)
        # These need adb for something an app uid cannot do: start another
        # app's foreground service, run `adb tcpip`, or handle identity
        # material (the key, the token, or a pairing code).
        echo "$command needs the adb shell; use ./keeper.sh $command" >&2
        exit 2
        ;;
    -h|--help|help) usage 0 ;;
    *) echo "unknown command: $command" >&2; usage 2 ;;
esac
