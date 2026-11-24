#!/data/data/com.termux/files/usr/bin/bash
# Create the pinned adb-harness environment discover.sh looks for.
#
#   bash tools/setup-adb-harness.sh
#   ADB_HARNESS_VENV=~/wireless-adb-keeper/tools/.venv \
#       bash tools/setup-adb-harness.sh   # for the app's runtime copy
#
# discover.sh resolves adb-connect in this order: $ADB_CONNECT, the venv beside
# the scripts (tools/.venv), then PATH. Nothing else is installed globally.
set -euo pipefail

ROOT=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd) || exit 1
REQUIREMENTS="$ROOT/tools/requirements.txt"
VENV=${ADB_HARNESS_VENV:-$ROOT/tools/.venv}

[ -f "$REQUIREMENTS" ] || {
    echo "setup-adb-harness: missing $REQUIREMENTS" >&2
    exit 1
}
command -v python3 >/dev/null 2>&1 || {
    echo "setup-adb-harness: python3 is not on PATH" >&2
    exit 1
}

python3 -m venv "$VENV"
"$VENV/bin/python" -m pip install --disable-pip-version-check -r "$REQUIREMENTS"
"$VENV/bin/adb-connect" --help >/dev/null

echo "adb-harness installed in $VENV"
echo "discover.sh will use $VENV/bin/adb-connect automatically."
echo "Elsewhere, put adb-connect on PATH or set ADB_CONNECT to its path."
