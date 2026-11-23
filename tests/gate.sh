#!/usr/bin/env bash
# Reads the log of a tests/unit.sh run and decides whether the gate is green.
#
#   bash tests/unit.sh > tests/out/unit.log 2>&1
#   bash tests/gate.sh --log tests/out/unit.log --status $?
#
# This exists because `./gradlew test` with no JUnit sources is a no-op: exit 0
# says "nothing failed", not "something ran". The gate prints the number of
# checks, suites and timing lines, refuses a run with none of them, and fails
# only on a hard timing regression: the total of the `timing:` lines more than
# GATE_FACTOR times the median of the last GATE_HISTORY green runs.
#
# The median lives in tests/out/timings.tsv, one total-ms integer per line. CI
# restores that file from actions/cache; a miss, or fewer than GATE_MIN_HISTORY
# samples, skips the comparison instead of failing. A run below GATE_MIN_MS is
# never a regression, however small the median is. The log and the summary are
# artifacts; only the history file and the javac stamp are cached.
#
# Exit status is nonzero when unit.sh failed, when no checks/timing lines were
# found, when the timing-line count does not match the suite count, or when the
# timing regression check trips.
set -euo pipefail

log=tests/out/unit.log
status=
store=tests/out/timings.tsv
summary=tests/out/gate-summary.txt
factor=${GATE_FACTOR:-4}
history=${GATE_HISTORY:-10}
min_history=${GATE_MIN_HISTORY:-3}
minimum=${GATE_MIN_MS:-1000}

usage() {
    cat <<'USAGE'
usage: bash tests/gate.sh --log FILE [--status N] [--store FILE]

  --log FILE      unit.sh output to inspect (default tests/out/unit.log)
  --status N      unit.sh exit status; nonzero is reported and returned
  --store FILE    timing history (default tests/out/timings.tsv)

Environment: GATE_FACTOR (default 4), GATE_HISTORY (10),
GATE_MIN_HISTORY (3 samples before comparing), GATE_MIN_MS (1000).
USAGE
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --log)
            log=${2:?--log needs a value}
            shift 2
            ;;
        --status)
            status=${2:?--status needs a value}
            shift 2
            ;;
        --store)
            store=${2:?--store needs a value}
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "gate: unknown argument: $1" >&2
            usage >&2
            exit 2
            ;;
    esac
done

if [ -z "$status" ]; then
    status=0
fi
case "$status" in
    ''|*[!0-9]*)
        echo "gate: --status must be a number, got '$status'" >&2
        exit 2
        ;;
esac
for value in "$factor" "$history" "$min_history" "$minimum"; do
    case "$value" in
        ''|*[!0-9]*)
            echo "gate: GATE_* values must be numbers, got '$value'" >&2
            exit 2
            ;;
    esac
done
for value in "$factor" "$history" "$min_history"; do
    if [ "$value" -lt 1 ]; then
        echo "gate: GATE_* counts must be at least 1, got $value" >&2
        exit 2
    fi
done

if [ ! -f "$log" ]; then
    echo "gate: FAIL no log at $log - unit.sh did not run" >&2
    exit 1
fi

mkdir -p "$(dirname "$store")" "$(dirname "$summary")"
: > "$summary"
say() {
    printf 'gate: %s\n' "$*" | tee -a "$summary"
}

# Check lines are `ok   <what>` and `FAIL <what>: expected <...> got <...>`;
# the per-suite line is `PASS <n> <Suite>` / `FAIL <n> of <m> <Suite>`. Matching
# the ": expected " suffix keeps the suite summary out of the check count.
checks=$(awk '/^ok   / { n++ } /^FAIL .*: expected / { n++ } END { print n + 0 }' "$log")
failed_checks=$(awk '/^FAIL .*: expected / { n++ } END { print n + 0 }' "$log")
suites=$(awk '/^PASS [0-9]+ / { n++ } /^FAIL [0-9]+ of [0-9]+ / { n++ } END { print n + 0 }' "$log")
timings=$(awk '/^timing: / { n++ } END { print n + 0 }' "$log")
total_ms=$(awk '/^timing: / {
    for (i = 1; i <= NF; i++) {
        if ($i ~ /^[0-9]+ms$/) {
            ms = $i
            sub(/ms$/, "", ms)
            sum += ms
        }
    }
} END { print sum + 0 }' "$log")

failing_checks=tests/out/failing-checks.txt
if [ "$failed_checks" -eq 0 ]; then
    rm -f "$failing_checks"
else
    grep -E '^FAIL ' "$log" > "$failing_checks" || true
fi

say "checks: $checks ($failed_checks failed)"
say "suites: $suites"
say "timing lines: $timings (${total_ms}ms total)"

# An empty or truncated run is a failure, not a small green run. unit.sh already
# exits 1 when it finds no *Test.java; this covers a log that was never written
# or that lost its lines before it reached the gate.
if [ "$checks" -eq 0 ]; then
    say "FAIL no checks in $log - an empty suite is not a pass"
    exit 1
fi

# Report the failing checks before the completeness checks below: a suite that
# fails calls System.exit, so it never prints its timing line and the timing
# count is expected to be short on this path.
if [ "$status" -ne 0 ] || [ "$failed_checks" -ne 0 ]; then
    say "FAIL unit.sh exited $status with $failed_checks failing check(s)"
    if [ -f "$failing_checks" ]; then
        while IFS= read -r line; do
            say "  $line"
        done < "$failing_checks"
        say "failing lines: $failing_checks"
    fi
    exit 1
fi

if [ "$suites" -eq 0 ]; then
    say "FAIL no PASS/FAIL suite lines in $log - the run did not finish"
    exit 1
fi
if [ "$timings" -eq 0 ]; then
    say "FAIL no timing: lines in $log - cannot tell what ran"
    exit 1
fi
if [ "$timings" -ne "$suites" ]; then
    say "FAIL $timings timing lines for $suites suites - a suite did not finish"
    exit 1
fi

# Compare against the median of the green runs already on record, before this
# one is appended. Read only integers so a corrupt line cannot change the verdict.
recent=()
if [ -f "$store" ]; then
    while IFS= read -r line; do
        case "$line" in
            ''|*[!0-9]*) continue ;;
        esac
        recent+=("$line")
    done < <(tail -n "$history" "$store")
fi
count=${#recent[@]}

if [ "$count" -lt "$min_history" ]; then
    say "timing: no regression check yet ($count/$min_history samples); this run is the next sample"
else
    sorted=()
    while IFS= read -r line; do
        sorted+=("$line")
    done < <(printf '%s\n' "${recent[@]}" | sort -n)
    middle=$((count / 2))
    if [ $((count % 2)) -eq 1 ]; then
        median=${sorted[$middle]}
    else
        median=$(((sorted[middle - 1] + sorted[middle]) / 2))
    fi
    limit=$((median * factor))
    say "timing: median ${median}ms of last $count; limit ${limit}ms (${factor}x), floor ${minimum}ms"
    if [ "$median" -gt 0 ] && [ "$total_ms" -gt "$limit" ] && [ "$total_ms" -gt "$minimum" ]; then
        say "FAIL timing regression: ${total_ms}ms is more than ${factor}x the ${median}ms median"
        exit 1
    fi
fi

# Keep the tail bounded and write in one move: a killed run must not leave a
# half-written history for the next median.
{
    tail -n "$history" "$store" 2>/dev/null || true
    printf '%s\n' "$total_ms"
} > "$store.tmp"
mv "$store.tmp" "$store"

say "PASS $checks checks in $suites suites"
