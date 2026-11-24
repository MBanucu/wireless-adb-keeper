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
# `check` can also sample the phone over USB while it is still locked: at ~27,
# 45, 60 and 90 s after boot, and when sys.user.0.ce_available first turns true.
# Those samples say whether Wi-Fi was up and 5555 was open before the unlock,
# which the post-unlock snapshot cannot. Start `check` while the phone reboots
# (or during the boot) with --preunlock-wait to collect them.
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

MAX_START_DELAY=90
# Seconds after boot at which the pre-unlock state is sampled over USB.
SAMPLE_TARGETS=(27 45 60 90)
# How long check may wait for the USB device and, after the last mark, for the
# first unlock. 0 does not wait; a boot run passes --preunlock-wait 180.
PREUNLOCK_WAIT=0
REQUIRE_PREUNLOCK_PORT=0
SAMPLE_INTERVAL=${REBOOT_VERDICT_POLL_INTERVAL:-1}

if [ -n "${PHONE_SERIAL:-}" ]; then
    ADB_TARGET=(-s "$PHONE_SERIAL")
else
    ADB_TARGET=()
fi

usage() {
    cat <<'USAGE'
Record and check a phone reboot with the app's boot-surviving diagnostics.

  tools/reboot-verdict.sh record [--state FILE]
      Record boot_id, mode, service, Wi-Fi/pin state and the journal size.

  tools/reboot-verdict.sh check [--state FILE] [--json] [--quiet]
                                [--max-start-delay SECONDS]
                                [--preunlock-wait SECONDS]
                                [--require-preunlock-port]
      After the reboot, compare the diagnostics with the record and print a
      pass/fail verdict. --json prints one object for CI.

      --max-start-delay  how many seconds after boot the process and the first
                         boot marker may arrive (default: 90). A later start
                         means the app was not started by the boot broadcast.

      --preunlock-wait   how long to wait for the phone over USB before sampling
                         and, after the last sample, for the first unlock
                         (default: 0, sample only while the phone answers).
                         Start check while the phone reboots with a bound such
                         as 180 to collect the 27/45/60/90 s samples.

      --require-preunlock-port
                         fail when a pre-unlock sample saw 5555 closed while
                         Wi-Fi was up and ce_available was false. Off by
                         default until the boot pin lands.

  Pre-unlock samples (over USB) print as one line each, then
  preunlock.wifi=up|down, preunlock.tls=<port>|none,
  preunlock.port5555=open|closed, unlock_at_s and pin_at_s.

Environment:
  REBOOT_VERDICT_STATE  state file (default: ~/.local/state/wireless-adb-keeper/reboot-verdict.state)
  ADB, PHONE_SERIAL     used by the diagnostics call and the USB sampling
  KEEPER                keeper.sh to use (tests point it at a fixture)
  REBOOT_VERDICT_POLL_INTERVAL  seconds between pre-unlock samples (default: 1)

Exit codes: 0 pass, 1 verdict failed, 2 usage, 3 blocked (no adb/device/state).
USAGE
    exit "${1:-0}"
}

blocked() {
    printf 'reboot-verdict: %s\n' "$1" >&2
    exit 3
}

is_seconds() {
    # is_seconds <value>
    case "$1" in
        ''|*[!0-9]*) return 1 ;;
        *) return 0 ;;
    esac
}

iso_epoch() {
    # iso_epoch <2026-11-24T13:22:16Z>
    date -u -d "$1" +%s 2>/dev/null || true
}

marker_delay_of() {
    # marker_delay_of <post-boot journal lines>: the first boot.received ran
    # this many seconds after boot, or nothing when there is no usable marker.
    local line up
    line=$(printf '%s\n' "$1" | grep ' boot\.received ' | head -n 1 || true)
    [ -n "$line" ] || return 0
    up=$(printf '%s\n' "$line" | awk '{print $2}')
    up=${up%s}
    is_seconds "$up" && printf '%s' "$up"
}

snapshot_value() {
    # snapshot_value <diag-file> <key>
    awk -v key="$2" '
        index($0, key "=") == 1 { print substr($0, length(key) + 2); exit }
    ' "$1"
}

boot_section() {
    # boot_section <diag-file> <recorded-at>
    #
    # Print the current boot's journal lines. The diagnostics contain the live
    # journal, then a "-- previous ... --" section with older events; that
    # section is dropped so a stale failure cannot fail a fresh boot.
    #
    # A boot logs LOCKED_BOOT_COMPLETED, then svc.start/svc.mode, then
    # BOOT_COMPLETED. Anchor on the first boot.received at/after the record,
    # not the last one, so the service lines between the pair are included.
    # If the clock moved backwards, fall back to the last LOCKED_BOOT_COMPLETED
    # (or the last boot.received) so the section still contains svc.start.
    awk -v since="$2" '
        index($0, "-- journal (oldest first) --") == 1 { live = 1; next }
        live && /^-- previous / { exit }
        !live { next }
        {
            line[NR] = $0
            if ($0 ~ / boot\.received /) {
                last = NR
                if (index($0, "LOCKED_BOOT_COMPLETED")) last_locked = NR
                if (!started && substr($0, 1, 20) >= since) started = NR
            }
        }
        END {
            if (!started) started = last_locked ? last_locked : last
            if (!started) exit 1
            for (i = started; i <= NR; i++) if (i in line) print line[i]
        }
    ' "$1"
}

state_value() {
    sed -n "s/^$1=//p" "$STATE" | head -n 1
}

json_escape() {
    printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' -e 's/\t/\\t/g'
}

json_number() {
    # json_number <value>: an integer, or null when unknown.
    if is_seconds "$1"; then printf '%s' "$1"; else printf 'null'; fi
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

field_of() {
    # field_of <k=v k=v ...> <key>: the value, or empty when the key is absent.
    local pair
    for pair in $1; do
        case "$pair" in
            "$2="*) printf '%s' "${pair#"$2"=}"; return 0 ;;
        esac
    done
    printf ''
}

adb_shell() {
    # adb_shell <command>
    "$ADB" "${ADB_TARGET[@]}" shell "$1" 2>/dev/null
}

listening_on() {
    # listening_on <port> <space-separated ports>
    local port
    for port in $2; do
        [ "$port" = "$1" ] && return 0
    done
    return 1
}

read_preunlock_state() {
    # One USB round trip for everything the pre-unlock sample needs. The
    # markers keep the parsing independent of adb's banner and line endings.
    local out line
    out=$(adb_shell '
        echo "ce=$(getprop sys.user.0.ce_available)"
        echo "tcp_port=$(getprop service.adb.tcp.port)"
        echo "tls_port=$(getprop service.adb.tls.port)"
        echo "uptime=$(cut -d. -f1 /proc/uptime)"
        echo "kernel_boot_id=$(cat /proc/sys/kernel/random/boot_id)"
        if command -v ip >/dev/null 2>&1; then echo ip_ok=1; else echo ip_ok=0; fi
        ip -4 addr show wlan0 2>/dev/null |
            sed -n "s/^ *inet \([0-9][0-9.]*\)\/.*/wlan_ip=\1/p"
        if command -v ss >/dev/null 2>&1; then echo ss_ok=1; else echo ss_ok=0; fi
        ss -ltn 2>/dev/null |
            sed -n "s/.*:\([0-9][0-9]*\) .*/listen=\1/p"
    ') || return 1
    [ -n "$out" ] || return 1
    PS_CE=
    PS_TCP_PORT=
    PS_TLS_PROP=
    PS_UPTIME=
    PS_KERNEL=
    PS_IP=
    PS_IP_OK=0
    PS_SS_OK=0
    PS_LISTENS=
    while IFS= read -r line; do
        line=${line%$'\r'}
        case "$line" in
            ce=*) PS_CE=${line#ce=} ;;
            tcp_port=*) PS_TCP_PORT=${line#tcp_port=} ;;
            tls_port=*) PS_TLS_PROP=${line#tls_port=} ;;
            uptime=*) PS_UPTIME=${line#uptime=} ;;
            kernel_boot_id=*) PS_KERNEL=${line#kernel_boot_id=} ;;
            ip_ok=*) PS_IP_OK=${line#ip_ok=} ;;
            wlan_ip=*) PS_IP=${line#wlan_ip=} ;;
            ss_ok=*) PS_SS_OK=${line#ss_ok=} ;;
            listen=*) PS_LISTENS="${PS_LISTENS} ${line#listen=}" ;;
        esac
    done <<<"$out"
    is_seconds "$PS_UPTIME" || return 1
    return 0
}

preunlock_tls() {
    # preunlock_tls <ip>: sets PU_TLS_PORT / PU_TLS_PROBE.
    #
    # adbd's TLS port comes from service.adb.tls.port when adbd set it. When it
    # did not, the ephemeral listeners from ss are candidates, but only a host
    # connect proves which one is adbd. The existing adb key is what makes the
    # connect succeed; a fresh transport is dropped again so the probe does not
    # change the host's device list.
    local ip=$1 candidate out seen= tried=0
    PU_TLS_PORT=
    PU_TLS_PROBE=skipped
    [ -n "$ip" ] || return 0

    candidate=
    case "$PS_TLS_PROP" in
        ''|*[!0-9]*) ;;
        *)
            if [ "$PS_TLS_PROP" != 0 ] && [ "$PS_TLS_PROP" != 5555 ]; then
                candidate=$PS_TLS_PROP
            fi
            ;;
    esac
    if [ -z "$candidate" ]; then
        local port
        for port in $PS_LISTENS; do
            case "$port" in
                5555|5037) continue ;;
            esac
            if [ "$port" -ge 32768 ] 2>/dev/null && [ "$port" -le 60999 ]; then
                case " $candidate " in
                    *" $port "*) ;;
                    *) candidate="${candidate} ${port}" ;;
                esac
            fi
        done
        [ -n "$candidate" ] &&
            candidate=$(printf '%s\n' $candidate | head -n 3 | tr '\n' ' ')
    fi
    [ -n "$candidate" ] || return 0

    for port in $candidate; do
        case " $seen " in *" $port "*) continue ;; esac
        seen="$seen $port"
        tried=1
        if command -v timeout >/dev/null 2>&1; then
            out=$(timeout 5 "$ADB" connect "$ip:$port" 2>&1) || true
        else
            out=$("$ADB" connect "$ip:$port" 2>&1) || true
        fi
        case "$out" in
            *"already connected"*)
                PU_TLS_PORT=$port
                PU_TLS_PROBE=ok
                return 0
                ;;
            *"connected to"*)
                PU_TLS_PORT=$port
                PU_TLS_PROBE=ok
                "$ADB" disconnect "$ip:$port" >/dev/null 2>&1 || true
                return 0
                ;;
        esac
    done
    [ "$tried" = 1 ] && PU_TLS_PROBE=fail
    return 0
}

take_preunlock_sample() {
    # take_preunlock_sample <<t>|unlock>: one line from the current PS_* state.
    local target=$1 at=$PS_UPTIME late=0 ce=0 wifi=down ip=- tls=- tls_probe=skipped
    local port5555=unknown
    is_seconds "$at" || at=0
    if [ "$target" != unlock ] && [ "$at" -ge "$target" ]; then
        late=$((at - target))
    fi
    case "$PS_CE" in true|1) ce=1 ;; esac
    case "$PS_IP_OK" in
        0) wifi=unknown ;;
        *) [ -n "$PS_IP" ] && wifi=up ;;
    esac
    [ -n "$PS_IP" ] && ip=$PS_IP
    if [ "$PS_SS_OK" = 1 ]; then
        if listening_on 5555 "$PS_LISTENS"; then
            port5555=open
        else
            port5555=closed
        fi
    fi
    if [ "$wifi" = up ]; then
        preunlock_tls "$PS_IP"
        [ -n "$PU_TLS_PORT" ] && tls=$PU_TLS_PORT
        tls_probe=$PU_TLS_PROBE
    fi
    PU_LINES+=("target=$target at=$at late=$late ce=$ce wifi=$wifi ip=$ip tcp_port=${PS_TCP_PORT:--} tls_port=$tls port5555=$port5555 tls_probe=$tls_probe")
    [ "$target" = unlock ] && PU_UNLOCK_AT=$at
    if [ "$port5555" = open ] && [ -z "$PU_PIN_AT" ]; then
        PU_PIN_AT=$at
    fi
    return 0
}

sample_preunlock() {
    # Sample the boot window over USB. PU_LINES holds one k=v line per sample;
    # PU_REASON explains why there are none. The marks are sampled one per
    # poll, so a late start still records the real uptime of the next mark.
    PU_LINES=()
    PU_REASON=
    PU_UNLOCK_AT=
    PU_PIN_AT=
    PU_TLS_PORT=
    PU_TLS_PROBE=skipped
    local saw_locked=0 unlock_sample=0
    local start contact marks_done now budget=$PREUNLOCK_WAIT
    local target orig_kernel orig_uptime i
    local -a pending=("${SAMPLE_TARGETS[@]}")

    if ! command -v "$ADB" >/dev/null 2>&1; then
        PU_REASON="adb is not available"
        return 0
    fi
    orig_kernel=$(state_value kernel_boot_id)
    orig_uptime=$(state_value uptime_s)
    start=$(date +%s)
    contact=
    marks_done=

    while :; do
        if read_preunlock_state; then
            if [ -z "$contact" ]; then
                contact=$(date +%s)
                # A check run before the reboot must not present the old boot
                # as pre-unlock evidence. The kernel boot id is exact; the
                # recorded uptime is the fallback for a state file without it.
                if [ -n "$orig_kernel" ] && [ "$PS_KERNEL" = "$orig_kernel" ]; then
                    PU_REASON="the kernel boot id is unchanged (${orig_kernel}); the phone has not rebooted yet"
                    return 0
                fi
                if [ -z "$orig_kernel" ] && is_seconds "$orig_uptime" &&
                        [ "$PS_UPTIME" -ge "$orig_uptime" ] && [ "$PS_UPTIME" -gt 120 ]; then
                    PU_REASON="uptime did not drop (recorded ${orig_uptime}s, now ${PS_UPTIME}s); the phone has not rebooted yet"
                    return 0
                fi
            fi
            case "$PS_CE" in
                true|1)
                    if [ "$saw_locked" = 0 ]; then
                        PU_REASON="the first sample already saw ce_available=true; the pre-unlock window was not observed"
                        return 0
                    fi
                    if [ "$unlock_sample" = 0 ]; then
                        take_preunlock_sample unlock
                        unlock_sample=1
                    fi
                    PU_REASON=
                    return 0
                    ;;
            esac
            saw_locked=1
            for i in "${!pending[@]}"; do
                target=${pending[$i]}
                if [ "$PS_UPTIME" -ge "$target" ]; then
                    take_preunlock_sample "$target"
                    unset 'pending[i]'
                    break
                fi
            done
        else
            if [ -n "$contact" ]; then
                PU_REASON="the phone stopped answering over USB"
                return 0
            fi
            if [ $(( $(date +%s) - start )) -ge "$budget" ]; then
                PU_REASON="no USB answer within ${budget}s"
                return 0
            fi
        fi

        now=$(date +%s)
        if [ "${#pending[@]}" -eq 0 ]; then
            [ -n "$marks_done" ] || marks_done=$now
            # Without a wait budget the marks are the whole sample: an unlock
            # later than the last mark is left to the journal's svc.unlocked.
            if [ $((now - marks_done)) -ge "$budget" ]; then
                PU_REASON="the phone stayed locked for ${budget}s after the last sample"
                return 0
            fi
        fi
        if [ $((now - start)) -ge 300 ]; then
            PU_REASON="sampling timed out after 300s"
            return 0
        fi
        sleep "$SAMPLE_INTERVAL"
    done
}

json_string_or_null() {
    if [ -n "$1" ]; then
        printf '"%s"' "$(json_escape "$1")"
    else
        printf 'null'
    fi
}

json_preunlock_samples() {
    local line first=1 target at ce wifi ip tcp tls port5555 probe
    printf '['
    for line in "${PU_LINES[@]}"; do
        [ "$first" = 1 ] || printf ','
        first=0
        target=$(field_of "$line" target)
        at=$(field_of "$line" at)
        ce=$(field_of "$line" ce)
        wifi=$(field_of "$line" wifi)
        ip=$(field_of "$line" ip)
        [ "$ip" = - ] && ip=
        tcp=$(field_of "$line" tcp_port)
        [ "$tcp" = - ] && tcp=
        tls=$(field_of "$line" tls_port)
        [ "$tls" = - ] && tls=
        port5555=$(field_of "$line" port5555)
        probe=$(field_of "$line" tls_probe)
        printf '{"target":"%s","at_s":%s,"ce_available":%s,"wifi":"%s","ip":%s,"tcp_port":%s,"tls_port":%s,"port5555":"%s","tls_probe":"%s"}' \
            "$(json_escape "$target")" \
            "$(json_number "$at")" \
            "$([ "$ce" = 1 ] && echo true || echo false)" \
            "$(json_escape "$wifi")" \
            "$(json_string_or_null "$ip")" \
            "$(json_number "$tcp")" \
            "$(json_number "$tls")" \
            "$(json_escape "$port5555")" \
            "$(json_escape "$probe")"
    done
    printf ']'
}

record() {
    local tmp err boot up events mode service wifi_mode pass held port kernel
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

    # The kernel boot id proves a later sample is from a new boot even before
    # the app's diagnostics are reachable. It is never a secret.
    kernel=
    if command -v "$ADB" >/dev/null 2>&1; then
        kernel=$(adb_shell 'cat /proc/sys/kernel/random/boot_id' | tr -d '\r' | head -n 1) || kernel=
    fi
    case "$kernel" in
        *[!0-9a-fA-F-]*) kernel= ;;
        ?*-?*) ;;
        *) kernel= ;;
    esac

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
        printf 'kernel_boot_id=%s\n' "$kernel"
    } >"$tmp_state"
    mv "$tmp_state" "$STATE"

    printf 'reboot verdict: recorded %s\n' "$STATE"
    printf '  boot_id=%s uptime_s=%s mode=%s service=%s\n' "$boot" "$up" "$mode" "$service"
    printf '  port_5555=%s\n' "$port"
    printf 'Reboot the phone and let it unlock, then run:\n  %s check\n' "$0"
    printf 'Or sample the pre-unlock window while it boots:\n  %s check --preunlock-wait 180\n' "$0"
}

check() {
    local json=$1 quiet=$2
    [ -f "$STATE" ] || blocked "no record at $STATE; run 'record' before rebooting"
    local schema
    schema=$(state_value schema)
    [ "$schema" = 1 ] || blocked "unknown state schema '$schema' at $STATE"

    local tmp err before_boot after_boot before_up after_up checks=0 failures=0 warnings=0
    local mode after_mode service unlocked wifi_mode pass missing unreadable
    local before_held after_held port after_boot_lines= mode_line new_boot=0
    local start_delay marker_line marker_ts marker_up marker_epoch marker_delay= boot_epoch=
    local marker_ahead
    local max_start_delay=$MAX_START_DELAY verdict=pass
    local pre_count=0 pre_wifi= pre_tls= pre_port= unlock_at= pin_at= pre_info=
    local -a failure_list=() warning_list=()

    tmp="$TMP/diag"
    err="$TMP/err"

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

    # Sample before the diagnostics call: the pre-unlock window is gone by the
    # time the app can answer, and with --preunlock-wait this waits for the
    # first unlock, so the post-unlock checks below run on an unlocked phone.
    sample_preunlock
    diag_to "$tmp" "$err"

    if [ "${#PU_LINES[@]}" -eq 0 ] && [ -n "$PU_REASON" ]; then
        case "$PU_REASON" in
            "the phone has not rebooted yet"*)
                pre_info="no pre-unlock sample: $PU_REASON; start check after the reboot"
                ;;
            *)
                pre_info="no pre-unlock sample: $PU_REASON"
                ;;
        esac
        if [ "$REQUIRE_PREUNLOCK_PORT" = 1 ] || [ "$PREUNLOCK_WAIT" -gt 0 ]; then
            warn "the pre-unlock samples are available" "$pre_info"
        fi
    fi

    # Summarize the pre-unlock samples once; the unlock sample is kept in the
    # list but is not evidence about the locked window.
    if [ "${#PU_LINES[@]}" -gt 0 ]; then
        local summary_line summary_tls
        for summary_line in "${PU_LINES[@]}"; do
            [ "$(field_of "$summary_line" target)" = unlock ] && continue
            pre_count=$((pre_count + 1))
            case "$(field_of "$summary_line" wifi)" in
                up) pre_wifi=up ;;
                down) [ -n "$pre_wifi" ] || pre_wifi=down ;;
            esac
            summary_tls=$(field_of "$summary_line" tls_port)
            [ "$summary_tls" = - ] || pre_tls=$summary_tls
            case "$(field_of "$summary_line" port5555)" in
                open) pre_port=open ;;
                closed) [ "$pre_port" = open ] || pre_port=closed ;;
            esac
        done
    fi

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

    # The service can still be running because the user opened the app, so
    # boot_id, service and journal content cannot tell a boot start from a
    # manual one. The process start delay is the missing fact.
    start_delay=$(snapshot_value "$tmp" process_started_after_boot_s)
    # A late process is only evidence of a blocked boot when the journal does
    # not already show the broadcast arriving at boot: after an on-time marker
    # the app did start at boot, and a late process is a later restart (a
    # force-stop, a crash, a reinstall or Doze), not a refused boot gate.
    marker_ahead=$(marker_delay_of \
        "$(boot_section "$tmp" "$(state_value recorded_at)" 2>/dev/null || true)")
    if [ "$new_boot" != 1 ]; then
        warn "the process started near boot" "skipped: no new boot"
    elif ! is_seconds "$start_delay"; then
        warn "the process start delay is available" \
            "process_started_after_boot_s='$start_delay'"
    elif [ "$start_delay" -le "$max_start_delay" ]; then
        ok "the process started ${start_delay}s after boot (limit ${max_start_delay}s)"
    elif [ -n "$marker_ahead" ] && [ "$marker_ahead" -le "$max_start_delay" ]; then
        warn "the process was restarted after boot" \
            "the first boot marker arrived ${marker_ahead}s after boot, so the boot broadcast did start the app; the process now running started ${start_delay}s after boot (a force-stop, a crash, a reinstall or Doze). Not an autostart-gate failure."
    else
        bad "the process started near boot" \
            "process_started_after_boot_s=${start_delay}s is over the ${max_start_delay}s limit (unlocked=$unlocked). The system boot broadcast starts the app; a start this late was probably the user opening it. Allow the app in DokeOS Auto Start Management and exempt it from Doze/battery optimization."
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

    # The post-unlock snapshot cannot tell a pre-unlock pin from one that only
    # arrived after the unlock. This opt-in check uses the USB samples for it.
    if [ "$REQUIRE_PREUNLOCK_PORT" = 1 ]; then
        local sample_line first_bad= samples_bad=0
        for sample_line in "${PU_LINES[@]}"; do
            [ "$(field_of "$sample_line" target)" = unlock ] && continue
            [ "$(field_of "$sample_line" ce)" = 1 ] && continue
            [ "$(field_of "$sample_line" wifi)" = up ] || continue
            if [ "$(field_of "$sample_line" port5555)" = closed ]; then
                [ -n "$first_bad" ] || first_bad=$sample_line
                samples_bad=$((samples_bad + 1))
            fi
        done
        if [ "$samples_bad" -gt 0 ]; then
            bad "port 5555 is open before unlock" \
                "closed at $(field_of "$first_bad" at)s while Wi-Fi was up and ce_available was false ($samples_bad sample(s)); the boot pin has not landed"
        elif [ "$pre_count" -eq 0 ]; then
            warn "port 5555 is open before unlock" \
                "not measured: ${PU_REASON:-the pre-unlock window was not observed}"
        else
            ok "no pre-unlock sample saw 5555 closed while Wi-Fi was up"
        fi
    fi

    if [ "$new_boot" != 1 ]; then
        warn "the post-boot journal was checked" "skipped: no new boot"
    elif after_boot_lines=$(boot_section "$tmp" "$(state_value recorded_at)"); then
        ok "the journal has a post-boot section"
    else
        bad "the journal has a post-boot section" "no boot.received line"
        after_boot_lines=
    fi

    if [ "$new_boot" = 1 ] && [ -n "$after_boot_lines" ]; then
        marker_line=$(printf '%s\n' "$after_boot_lines" |
            grep ' boot\.received ' | head -n 1 || true)
        marker_delay=
        boot_epoch=
        if [ -z "$marker_line" ]; then
            warn "the first boot marker is near the boot instant" "no boot.received line"
        else
            marker_ts=$(printf '%s\n' "$marker_line" | awk '{print $1}')
            marker_up=$(printf '%s\n' "$marker_line" | awk '{print $2}')
            marker_up=${marker_up%s}
            marker_epoch=$(iso_epoch "$marker_ts")
            if ! is_seconds "$marker_up" || [ -z "$marker_epoch" ]; then
                warn "the first boot marker is near the boot instant" \
                    "cannot read the timestamp/uptime from: $marker_line"
            else
                # The marker's own wall clock minus its uptime is the boot
                # instant; the distance from it is how late the marker ran.
                boot_epoch=$((marker_epoch - marker_up))
                marker_delay=$marker_up
                if [ "$marker_delay" -le "$max_start_delay" ]; then
                    ok "the first boot marker is ${marker_delay}s after boot (limit ${max_start_delay}s)"
                else
                    bad "the first boot marker is near the boot instant" \
                        "it arrived ${marker_delay}s after boot (limit ${max_start_delay}s; boot at $(date -u -d "@$boot_epoch" +%Y-%m-%dT%H:%M:%SZ)). A boot marker this late looks like the app was launched by hand, not started by the boot broadcast."
                fi
            fi
        fi

        # LOCKED_BOOT_COMPLETED normally arrives before the first unlock. A
        # phone that stays locked past the broadcast delays the marker, and
        # that alone must not fail the boot; a late marker plus a late process
        # is the manual-launch shape.
        case "$marker_line" in
            *'LOCKED_BOOT_COMPLETED before unlock'*)
                ok "the boot marker arrived before unlock"
                ;;
            *'after unlock'*)
                if is_seconds "$start_delay" && [ "$start_delay" -gt "$max_start_delay" ]; then
                    bad "the boot marker arrived before unlock" \
                        "the first boot marker is 'after unlock' and the process started ${start_delay}s after boot. The marker waited for the unlock, so the app was started by the unlock/launch, not by the boot broadcast."
                else
                    warn "the boot marker arrived after unlock" \
                        "the first boot marker is 'after unlock'; the phone stayed locked past the broadcast. Not a failure by itself."
                fi
                ;;
            *LOCKED_BOOT_COMPLETED*)
                warn "the boot marker unlock state" \
                    "boot.received has no before/after unlock detail: $marker_line"
                ;;
        esac

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

    # Timing for the two stages that matter: the first unlock and the first
    # listening 5555. The journal is more precise than the sampling grid and
    # also covers a run that started after the unlock.
    unlock_at=$PU_UNLOCK_AT
    pin_at=$PU_PIN_AT
    if [ -n "$after_boot_lines" ]; then
        local journal_at
        journal_at=$(printf '%s\n' "$after_boot_lines" |
            grep ' svc\.unlocked ' | head -n 1 | awk '{print $2}' || true)
        journal_at=${journal_at%s}
        is_seconds "$journal_at" && unlock_at=$journal_at
        journal_at=$(printf '%s\n' "$after_boot_lines" |
            grep ' pin\.state listening' | head -n 1 | awk '{print $2}' || true)
        journal_at=${journal_at%s}
        is_seconds "$journal_at" && pin_at=$journal_at
    fi

    if [ "$json" = 1 ]; then
        [ "$failures" -eq 0 ] || verdict=fail
        printf '{"schema":1,"ok":%s,"verdict":"%s","boot_id_before":"%s","boot_id_after":"%s","checks":%d,"failed":%d,"warnings":%d,"max_start_delay_s":%s,"start_delay_s":%s,"boot_marker_delay_s":%s,"boot_epoch":%s,"preunlock_wifi":%s,"preunlock_tls":%s,"preunlock_port5555":%s,"unlock_at_s":%s,"pin_at_s":%s,"preunlock_samples":%s,"failures":%s,"warning_messages":%s}\n' \
            "$([ "$failures" -eq 0 ] && echo true || echo false)" \
            "$verdict" \
            "$(json_escape "$before_boot")" "$(json_escape "$after_boot")" \
            "$checks" "$failures" "$warnings" \
            "$(json_number "$max_start_delay")" \
            "$(json_number "$start_delay")" \
            "$(json_number "$marker_delay")" \
            "$(json_number "$boot_epoch")" \
            "$(json_string_or_null "${pre_wifi:-}")" \
            "$(json_number "${pre_tls:-}")" \
            "$(json_string_or_null "${pre_port:-}")" \
            "$(json_number "$unlock_at")" \
            "$(json_number "$pin_at")" \
            "$(json_preunlock_samples)" \
            "$(json_array "${failure_list[@]}")" \
            "$(json_array "${warning_list[@]}")"
    else
        if [ "$json" != 1 ] && [ "$quiet" != 1 ]; then
            if [ "${#PU_LINES[@]}" -gt 0 ]; then
                printf 'pre-unlock samples (USB):\n'
                local sample_line
                for sample_line in "${PU_LINES[@]}"; do
                    printf '  %s\n' "$sample_line"
                done
                if [ "$pre_count" -gt 0 ]; then
                    printf 'preunlock.wifi=%s\n' "${pre_wifi:-unknown}"
                    printf 'preunlock.tls=%s\n' "${pre_tls:-none}"
                    printf 'preunlock.port5555=%s\n' "${pre_port:-unknown}"
                fi
            elif [ -n "$pre_info" ]; then
                printf '%s\n' "$pre_info"
            fi
            [ -n "$unlock_at" ] && printf 'unlock_at_s=%s\n' "$unlock_at"
            [ -n "$pin_at" ] && printf 'pin_at_s=%s\n' "$pin_at"
        fi
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
        --max-start-delay)
            [ "$#" -ge 2 ] && [ -n "$2" ] || { usage 2; }
            MAX_START_DELAY=$2
            shift 2
            ;;
        --max-start-delay=*)
            MAX_START_DELAY=${1#*=}
            shift
            ;;
        --preunlock-wait)
            [ "$#" -ge 2 ] && [ -n "$2" ] || { usage 2; }
            PREUNLOCK_WAIT=$2
            shift 2
            ;;
        --preunlock-wait=*)
            PREUNLOCK_WAIT=${1#*=}
            shift
            ;;
        --require-preunlock-port) REQUIRE_PREUNLOCK_PORT=1; shift ;;
        -h|--help|help) usage 0 ;;
        *) printf 'unknown option: %s\n' "$1" >&2; usage 2 ;;
    esac
done

if ! is_seconds "$MAX_START_DELAY"; then
    printf 'invalid --max-start-delay: %s\n' "$MAX_START_DELAY" >&2
    usage 2
fi
if ! is_seconds "$PREUNLOCK_WAIT"; then
    printf 'invalid --preunlock-wait: %s\n' "$PREUNLOCK_WAIT" >&2
    usage 2
fi
if ! is_seconds "$SAMPLE_INTERVAL"; then
    printf 'invalid REBOOT_VERDICT_POLL_INTERVAL: %s\n' "$SAMPLE_INTERVAL" >&2
    usage 2
fi

case "$command" in
    record) record ;;
    check) check "$json" "$quiet" ;;
    ''|help|--help|-h) usage 0 ;;
    *) printf 'unknown command: %s\n' "$command" >&2; usage 2 ;;
esac
