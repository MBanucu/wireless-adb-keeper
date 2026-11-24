#!/data/data/com.termux/files/usr/bin/bash
# Record a good pre-reboot state, then check the phone's boot-surviving
# diagnostics after the reboot. The session that started the reboot may die;
# the evidence is in the app's device-protected journal, not in this process.
#
#   tools/reboot-verdict.sh record
#   <reboot the phone and let it unlock>
#   tools/reboot-verdict.sh check
#
# The state file holds only status fields, never a passphrase or token:
#   ${XDG_STATE_HOME:-$HOME/.local/state}/wireless-adb-keeper/reboot-verdict.state
#
# Exit codes: 0 pass, 1 verdict failed, 2 usage, 3 blocked (no adb/device or no
# recorded state).
set -euo pipefail

ROOT=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd) || exit 1
KEEPER=${KEEPER:-$ROOT/keeper.sh}
ADB=${ADB:-adb}
STATE=${REBOOT_VERDICT_STATE:-${XDG_STATE_HOME:-$HOME/.local/state}/wireless-adb-keeper/reboot-verdict.state}
TMP=$(mktemp -d) || exit 1
trap 'rm -rf "$TMP"' EXIT

usage() {
    cat <<'USAGE'
Record and check a phone reboot with the app's boot-surviving diagnostics.

  tools/reboot-verdict.sh record [--state FILE]
      Record boot_id, mode, service, Wi-Fi/pin state and the journal size.

  tools/reboot-verdict.sh check [--state FILE] [--json] [--quiet]
      After the reboot, compare the diagnostics with the record and print a
      pass/fail verdict. --json prints one object for CI.

Environment:
  REBOOT_VERDICT_STATE  state file (default: ~/.local/state/wireless-adb-keeper/reboot-verdict.state)
  ADB, PHONE_SERIAL     forwarded to keeper.sh
  KEEPER                keeper.sh to use (tests point it at a fixture)

Exit codes: 0 pass, 1 verdict failed, 2 usage, 3 blocked (no adb/device/state).
USAGE
    exit "${1:-0}"
}

blocked() {
    printf 'reboot-verdict: %s\n' "$1" >&2
    exit 3
}

snapshot_value() {
    # snapshot_value <diag-file> <key>
    awk -v key="$2" '
        index($0, key "=") == 1 { print substr($0, length(key) + 2); exit }
    ' "$1"
}

journal_lines() {
    awk 'found { print } index($0, "-- journal (oldest first) --") == 1 { found = 1 }' "$1"
}

after_last_boot() {
    # The journal is oldest first. Start at the last boot.received so evidence
    # from the previous boot, and from the app's earlier life, is ignored.
    awk '
        / boot\.received / { last = NR }
        { line[NR] = $0 }
        END {
            if (last == 0) exit 1
            for (i = last; i <= NR; i++) print line[i]
        }
    ' "$1"
}

state_value() {
    sed -n "s/^$1=//p" "$STATE" | head -n 1
}

json_escape() {
    printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' -e 's/\t/\\t/g'
}

json_array() {
    local first=1 item
    printf '['
    for item in "$@"; do
        [ "$first" = 1 ] || printf ', '
        first=0
        printf '"%s"' "$(json_escape "$item")"
    done
    printf ']'
}

diag_to() {
    # diag_to <out> <err>
    if ! ADB="$ADB" bash "$KEEPER" diag >"$1" 2>"$2"; then
        blocked "the diagnostics call failed; is adb attached? $(head -n 1 "$2" 2>/dev/null || true)"
    fi
    [ -s "$1" ] || blocked "the diagnostics call returned nothing"
}

record() {
    local tmp err boot up events mode service wifi_mode pass held port
    tmp="$TMP/diag"
    err="$TMP/err"

    diag_to "$tmp" "$err"
    boot=$(snapshot_value "$tmp" boot_id)
    [ -n "$boot" ] || blocked "diagnostics has no boot_id; update the app or read the Diag page"
    up=$(snapshot_value "$tmp" uptime_s)
    events=$(snapshot_value "$tmp" journal_events)
    mode=$(snapshot_value "$tmp" mode)
    service=$(snapshot_value "$tmp" service)
    wifi_mode=$(snapshot_value "$tmp" wifi_mode)
    pass=$(snapshot_value "$tmp" wifi_passphrase)
    held=$(snapshot_value "$tmp" suggestion_held)
    port=$(snapshot_value "$tmp" port_5555)

    umask 077
    mkdir -p "$(dirname "$STATE")"
    tmp_state="$STATE.new.$$"
    {
        printf 'schema=1\n'
        printf 'recorded_at=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
        printf 'boot_id=%s\n' "$boot"
        printf 'uptime_s=%s\n' "$up"
        printf 'journal_events=%s\n' "$events"
        printf 'mode=%s\n' "$mode"
        printf 'service=%s\n' "$service"
        printf 'wifi_mode=%s\n' "$wifi_mode"
        printf 'wifi_passphrase=%s\n' "$pass"
        printf 'suggestion_held=%s\n' "$held"
        printf 'port_5555=%s\n' "$port"
    } >"$tmp_state"
    mv "$tmp_state" "$STATE"

    printf 'reboot verdict: recorded %s\n' "$STATE"
    printf '  boot_id=%s uptime_s=%s mode=%s service=%s\n' "$boot" "$up" "$mode" "$service"
    printf '  port_5555=%s\n' "$port"
    printf 'Reboot the phone and let it unlock, then run:\n  %s check\n' "$0"
}

check() {
    local json=$1 quiet=$2
    [ -f "$STATE" ] || blocked "no record at $STATE; run 'record' before rebooting"
    local schema
    schema=$(state_value schema)
    [ "$schema" = 1 ] || blocked "unknown state schema '$schema' at $STATE"

    local tmp err before_boot after_boot before_up after_up checks=0 failures=0 warnings=0
    local mode after_mode service unlocked wifi_mode pass missing unreadable
    local before_held after_held port after_boot_lines mode_line new_boot=0
    local -a failure_list=() warning_list=()

    tmp="$TMP/diag"
    err="$TMP/err"
    diag_to "$tmp" "$err"

    ok() {
        checks=$((checks + 1))
        [ "$quiet" = 1 ] || [ "$json" = 1 ] || printf 'ok   %s\n' "$1"
    }
    bad() {
        checks=$((checks + 1))
        failures=$((failures + 1))
        failure_list+=("$1: $2")
        [ "$json" = 1 ] || printf 'FAIL %s: %s\n' "$1" "$2"
    }
    warn() {
        warnings=$((warnings + 1))
        warning_list+=("$1: $2")
        [ "$json" = 1 ] || [ "$quiet" = 1 ] || printf 'warn %s: %s\n' "$1" "$2"
    }

    before_boot=$(state_value boot_id)
    after_boot=$(snapshot_value "$tmp" boot_id)
    if [ -z "$after_boot" ]; then
        bad "a new boot" "diagnostics has no boot_id"
    elif [ "$after_boot" = "$before_boot" ]; then
        bad "a new boot" "boot_id is still $after_boot; the phone did not reboot"
    else
        new_boot=1
        ok "a new boot (boot_id $before_boot -> $after_boot)"
    fi

    before_up=$(state_value uptime_s)
    after_up=$(snapshot_value "$tmp" uptime_s)
    case "${before_up}:${after_up}" in
        *[!0-9:]*|:*|*:) ;;
        *)
            if [ "$after_up" -lt "$before_up" ]; then
                ok "uptime restarted ($before_up -> ${after_up}s)"
            else
                warn "uptime did not drop" "went from ${before_up}s to ${after_up}s"
            fi
            ;;
    esac

    service=$(snapshot_value "$tmp" service)
    if [ "$service" = running ]; then
        ok "the monitor service is running"
    else
        bad "the monitor service is running" "status says '$service'"
    fi

    unlocked=$(snapshot_value "$tmp" unlocked)
    if [ "$unlocked" = true ]; then
        ok "credential storage is unlocked"
    else
        bad "credential storage is unlocked" "unlocked=$unlocked; unlock the phone and re-run"
    fi

    mode=$(state_value mode)
    after_mode=$(snapshot_value "$tmp" mode)
    if [ -n "$after_mode" ] && { [ -z "$mode" ] || [ "$after_mode" = "$mode" ]; }; then
        ok "the saved mode is still $after_mode"
    else
        bad "the saved mode survived" "recorded '$mode', now '$after_mode'"
    fi

    wifi_mode=$(snapshot_value "$tmp" wifi_mode)
    case "$wifi_mode" in
        applied*) ok "the Wi-Fi mode was applied ($wifi_mode)" ;;
        *) bad "the Wi-Fi mode was applied" "wifi_mode='$wifi_mode'" ;;
    esac

    pass=$(snapshot_value "$tmp" wifi_passphrase)
    missing=$(printf '%s\n' "$pass" | sed -n 's/.*missing=\([0-9][0-9]*\).*/\1/p')
    unreadable=$(printf '%s\n' "$pass" | sed -n 's/.*unreadable=\([0-9][0-9]*\).*/\1/p')
    if [ -z "$pass" ]; then
        warn "the passphrase state is present" "diagnostics has no wifi_passphrase"
    elif [ "${missing:-0}" -gt 0 ] || [ "${unreadable:-0}" -gt 0 ]; then
        bad "the passphrase is readable" "$pass; save the network again"
    else
        ok "the passphrase is readable ($pass)"
    fi

    before_held=$(state_value suggestion_held)
    after_held=$(snapshot_value "$tmp" suggestion_held)
    if [ "$after_mode" = auto ]; then
        if [ -z "$after_held" ]; then
            warn "a suggestion is held" "diagnostics has no suggestion_held"
        elif [ "${before_held:-0}" -gt 0 ] && [ "$after_held" -eq 0 ]; then
            bad "a suggestion is held" "was ${before_held}, now 0 in auto-connect mode"
        else
            ok "a suggestion is held ($after_held)"
        fi
    fi

    port=$(snapshot_value "$tmp" port_5555)
    case "$port" in
        listening*) ok "the pinned port is listening ($port)" ;;
        *) bad "the pinned port is listening" "port_5555='$port'" ;;
    esac

    if [ "$new_boot" != 1 ]; then
        warn "the post-boot journal was checked" "skipped: no new boot"
    elif after_boot_lines=$(after_last_boot "$tmp"); then
        ok "the journal has a post-boot section"
    else
        bad "the journal has a post-boot section" "no boot.received line"
        after_boot_lines=
    fi

    if [ "$new_boot" = 1 ] && [ -n "$after_boot_lines" ]; then
        if printf '%s\n' "$after_boot_lines" | grep -q ' svc\.start '; then
            ok "the boot path journaled svc.start"
        else
            bad "the boot path journaled svc.start" "no svc.start after boot.received"
        fi

        mode_line=$(printf '%s\n' "$after_boot_lines" | grep ' svc\.mode ' | tail -n 1 || true)
        if [ -z "$mode_line" ]; then
            bad "the boot path journaled svc.mode" "no svc.mode after boot.received"
        else
            case "${after_mode}:${mode_line}" in
                auto:*auto-connect*) ok "svc.mode applied auto-connect" ;;
                local:*local-only*) ok "svc.mode applied local-only" ;;
                *) bad "svc.mode matches the saved mode" "expected '$after_mode', line: $mode_line" ;;
            esac
        fi

        if printf '%s\n' "$after_boot_lines" | grep -q ' pin\.state listening'; then
            ok "the boot path journaled a listening 5555 probe"
        else
            bad "the boot path journaled a listening 5555 probe" "no 'pin.state listening' after boot"
        fi

        if [ "$after_mode" = auto ]; then
            if printf '%s\n' "$after_boot_lines" |
                    grep -Eq 'suggestion .*active \(status 0\)|wifi\.suggest\.(added|job)'; then
                ok "auto-connect re-registered the suggestion"
            else
                bad "auto-connect re-registered the suggestion" "no active suggestion after boot"
            fi
        fi

        critical='svc\.start_failed|svc\.start_refused|pin\.script_failed|pin\.run_refused|pin\.port_closed|wifi\.suggest\.decrypt_failed|wifi\.suggest\.no_password|wifi\.suggest\.build_failed|wifi\.suggest\.refused|wifi\.secret\.undecryptable'
        failures_after_boot=$(printf '%s\n' "$after_boot_lines" |
            grep -E " (error|warn) ($critical) " | head -n 3 || true)
        if [ -n "$failures_after_boot" ]; then
            bad "no boot-critical failure in the journal" "$(printf '%s' "$failures_after_boot" | head -n 1)"
        else
            ok "no boot-critical failure in the journal"
        fi

        if printf '%s\n' "$after_boot_lines" | grep -q ' error boot\.start_failed '; then
            warn "boot.start_failed was logged" "the service still recovered; check the journal for the retry"
        fi
    fi

    if [ "$json" = 1 ]; then
        printf '{"schema":1,"ok":%s,"boot_id_before":"%s","boot_id_after":"%s","checks":%d,"failed":%d,"warnings":%d,"failures":%s,"warning_messages":%s}\n' \
            "$([ "$failures" -eq 0 ] && echo true || echo false)" \
            "$(json_escape "$before_boot")" "$(json_escape "$after_boot")" \
            "$checks" "$failures" "$warnings" \
            "$(json_array "${failure_list[@]}")" \
            "$(json_array "${warning_list[@]}")"
    else
        if [ "$failures" -gt 0 ]; then
            printf 'reboot verdict: FAIL (%d checks, %d failed, %d warnings)\n' \
                "$checks" "$failures" "$warnings"
            if [ "$quiet" = 1 ]; then
                printf 'FAIL %s\n' "${failure_list[@]}"
            fi
        else
            printf 'reboot verdict: PASS (%d checks, %d warnings)\n' \
                "$checks" "$warnings"
        fi
    fi

    [ "$failures" -eq 0 ]
}

json=0
quiet=0
command=${1:-}
[ "$#" -gt 0 ] && shift
while [ "$#" -gt 0 ]; do
    case "$1" in
        --state)
            [ "$#" -ge 2 ] && [ -n "$2" ] || { usage 2; }
            STATE=$2
            shift 2
            ;;
        --state=*)
            STATE=${1#*=}
            [ -n "$STATE" ] || usage 2
            shift
            ;;
        --json) json=1; shift ;;
        --quiet) quiet=1; shift ;;
        -h|--help|help) usage 0 ;;
        *) printf 'unknown option: %s\n' "$1" >&2; usage 2 ;;
    esac
done

case "$command" in
    record) record ;;
    check) check "$json" "$quiet" ;;
    ''|help|--help|-h) usage 0 ;;
    *) printf 'unknown command: %s\n' "$command" >&2; usage 2 ;;
esac
