#!/data/data/com.termux/files/usr/bin/bash
# Keep mDNS discovery in adb-harness (#68).
#
# The forked adb-mdns.py was retired in favour of `adb-connect` from the pinned
# adb-harness[mdns]. This guard fails when a raw zeroconf browser (or the old
# helper) grows back anywhere outside the dependency, so the two copies cannot
# drift apart again. It needs no device and no network.
#
# Wired into `./gradlew probe` and the CI workflow.
set -u

ROOT=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd) || exit 1
fail=0

note() {
    echo "FAIL check-adb-harness: $*" >&2
    fail=1
}

# 1. The old helper itself must stay deleted.
if [ -e "$ROOT/adb-mdns.py" ]; then
    note "adb-mdns.py is back; mDNS discovery belongs to adb-harness"
fi

# 2. No Python file may implement discovery with zeroconf directly. The venv is
#    the dependency; build output and the git directory are not source.
while IFS= read -r file; do
    if grep -nE '(^|[[:space:]])(from|import)[[:space:]]+zeroconf|ServiceBrowser|Zeroconf\(' \
            "$file" >/dev/null 2>&1; then
        note "$file imports or uses zeroconf; call adb-connect instead"
    fi
done < <(find "$ROOT" \
    \( -name .git -o -name .venv -o -name venv -o -name __pycache__ \
       -o -path "$ROOT/tests/out" -o -path "$ROOT/build" \
       -o -path "$ROOT/build-gradle" \) -prune -o \
    -type f -name '*.py' -print)

# 3. No script may invoke the retired helper. Comments may still name it.
while IFS= read -r file; do
    if grep -nE '^[[:space:]]*[^#].*adb-mdns\.py' "$file" >/dev/null 2>&1; then
        note "$file still invokes adb-mdns.py"
    fi
done < <(find "$ROOT" \
    \( -name .git -o -name .venv -o -name venv -o -name __pycache__ \
       -o -path "$ROOT/tests/out" -o -path "$ROOT/build" \
       -o -path "$ROOT/build-gradle" \) -prune -o \
    -type f -name '*.sh' ! -name 'check-adb-harness.sh' -print)

# 4. discover.sh is the one caller, and it calls the dependency.
if ! grep -q 'adb-connect' "$ROOT/discover.sh" 2>/dev/null; then
    note "discover.sh does not call adb-connect"
fi

# 5. The dependency is pinned, not a floating range.
requirements="$ROOT/tools/requirements.txt"
if [ ! -f "$requirements" ]; then
    note "missing tools/requirements.txt (the pinned adb-harness[mdns])"
elif ! grep -qE '^adb-harness\[mdns\]==[0-9]+\.[0-9]+\.[0-9]+([[:space:]]|$)' \
        "$requirements"; then
    note "tools/requirements.txt must pin adb-harness[mdns]==X.Y.Z"
fi

if [ "$fail" -ne 0 ]; then
    exit 1
fi
echo "check-adb-harness: mDNS discovery is delegated to pinned adb-harness[mdns]"
