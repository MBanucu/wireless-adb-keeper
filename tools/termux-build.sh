#!/bin/sh
# shellcheck shell=bash
# /bin/sh exists on both Termux and Linux; re-exec bash because the script
# below uses bash arrays. Termux has no /usr/bin/env, so a portable shebang
# cannot be '#!/usr/bin/env bash'.
if [ -z "${BASH_VERSION:-}" ]; then
    exec bash "$0" "$@"
fi
#
# One-command Gradle build for Termux (and any other host).
#
# Resolves the Android SDK (ANDROID_HOME, ANDROID_SDK_ROOT, local.properties or
# the Termux default $HOME/android-sdk), exports it for this process, points
# AGP at Termux's bionic aapt2, and then runs the wrapper. Nothing here writes
# local.properties: a fresh checkout needs no manual edit.
#
# Gradle is the canonical, feature-complete build (src-pairing + libadb). The
# dependency-free bootstrap is ./build.sh, which needs no SDK configuration.
#
# Usage:
#   tools/termux-build.sh                     # checkBuildEnv + assembleDebug
#   tools/termux-build.sh lintDebug
#   tools/termux-build.sh --check             # print the resolved env and stop
#   OFFLINE=0 tools/termux-build.sh           # allow the first download
#   GRADLE=gradle tools/termux-build.sh       # use the packaged Gradle
#   AAPT2=$PREFIX/bin/aapt2 tools/termux-build.sh
#
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"

die() {
    printf 'error: %s\n' "$*" >&2
    exit 1
}

usage() {
    cat <<'USAGE'
One-command Gradle build for Termux (and any other host).

  tools/termux-build.sh                     # checkBuildEnv + assembleDebug
  tools/termux-build.sh lintDebug
  tools/termux-build.sh --check             # print the resolved env and stop
  OFFLINE=0 tools/termux-build.sh           # allow the first download
  GRADLE=gradle tools/termux-build.sh       # use the packaged Gradle
  AAPT2=$PREFIX/bin/aapt2 tools/termux-build.sh

Gradle is the canonical, feature-complete build (src-pairing + libadb). The
dependency-free bootstrap is ./build.sh, which needs no SDK configuration.
USAGE
}

check_only=0
args=()
for arg in "$@"; do
    case $arg in
        --check) check_only=1 ;;
        -h | --help)
            usage
            exit 0
            ;;
        *) args+=("$arg") ;;
    esac
done

# ---------------------------------------------------------------------------
# Android SDK
# ---------------------------------------------------------------------------
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [ -z "$sdk" ] && [ -f local.properties ]; then
    sdk=$(sed -n 's/^sdk\.dir=//p' local.properties | head -1)
fi
if [ -z "$sdk" ] && [ -d "${HOME:-}/android-sdk" ]; then
    sdk="$HOME/android-sdk"
fi

if [ -z "$sdk" ]; then
    die "no Android SDK found. Export ANDROID_HOME=/path/to/android-sdk, copy
  local.properties.example to local.properties and set sdk.dir, or install
  the SDK at \$HOME/android-sdk. The dependency-free build (./build.sh) needs
  no SDK configuration."
fi
[ -d "$sdk" ] || die "the Android SDK at '$sdk' is not a directory; set ANDROID_HOME or local.properties sdk.dir to a real SDK"
[ -d "$sdk/platforms" ] || die "'$sdk' has no platforms/ directory; install one with: sdkmanager 'platforms;android-35'"

compileSdk=$(sed -n 's/^compileSdk *= *"\([0-9][0-9]*\)".*/\1/p' gradle/libs.versions.toml | head -1)
[ -n "$compileSdk" ] || die "could not read compileSdk from gradle/libs.versions.toml"
[ -f "$sdk/platforms/android-$compileSdk/android.jar" ] \
    || die "Android platform $compileSdk is not installed in '$sdk'; run: sdkmanager 'platforms;android-$compileSdk'"

export ANDROID_HOME="$sdk"
export ANDROID_SDK_ROOT="$sdk"

# ---------------------------------------------------------------------------
# aapt2 (Termux only: AGP's download is a glibc build, Termux is bionic)
# ---------------------------------------------------------------------------
aapt2=${AAPT2:-}
if [ -z "$aapt2" ] && [ -n "${PREFIX:-}" ] && [ -x "$PREFIX/bin/aapt2" ]; then
    aapt2="$PREFIX/bin/aapt2"
fi
if [ -z "$aapt2" ] && command -v aapt2 >/dev/null 2>&1; then
    aapt2=$(command -v aapt2)
fi

termux=0
if [ -n "${PREFIX:-}" ] && [ "${PREFIX#*com.termux}" != "$PREFIX" ]; then
    termux=1
elif [ -n "${HOME:-}" ] && [ "${HOME#*com.termux}" != "$HOME" ]; then
    termux=1
fi
if [ "$termux" = 1 ] && [ -z "$aapt2" ]; then
    die "Termux's aapt2 was not found at \$PREFIX/bin/aapt2. Install it with
  'pkg install aapt' (Termux's aapt2 is bionic; AGP's downloaded one is glibc
  and cannot run here), or set AAPT2=/path/to/aapt2."
fi
if [ -n "$aapt2" ] && [ ! -x "$aapt2" ]; then
    die "the aapt2 override '$aapt2' is not executable; run: chmod +x '$aapt2'"
fi

if [ "$check_only" = 1 ]; then
    printf 'ANDROID_HOME=%s\n' "$sdk"
    printf 'compileSdk=%s\n' "$compileSdk"
    aapt2_label=$aapt2
    [ -n "$aapt2_label" ] || aapt2_label="AGP built-in aapt2"
    printf 'aapt2=%s\n' "$aapt2_label"
    if [ -x ./gradlew ]; then
        printf 'gradle=./gradlew (wrapper)\n'
    else
        printf 'gradle=%s\n' "$(command -v gradle 2>/dev/null || echo 'not found')"
    fi
    exit 0
fi

# ---------------------------------------------------------------------------
# Gradle invocation
# ---------------------------------------------------------------------------
if [ -n "${GRADLE:-}" ]; then
    gradle=$GRADLE
elif [ -x ./gradlew ]; then
    gradle=./gradlew
elif command -v gradle >/dev/null 2>&1; then
    gradle=gradle
else
    die "no Gradle found: ./gradlew is missing and there is no gradle on PATH; run 'pkg install gradle' or restore the wrapper"
fi

gradle_args=(--warning-mode all --configuration-cache --build-cache)
if [ "${OFFLINE:-1}" = 1 ]; then
    gradle_args+=(--offline)
fi

has_console=0
for arg in "${args[@]}"; do
    case $arg in
        --console | --console=*) has_console=1 ;;
    esac
done
[ "$has_console" = 1 ] || gradle_args=(--console=plain "${gradle_args[@]}")

# Pass the override on the command line so a fresh checkout needs no global
# setup. Respect an override the user already put in ~/.gradle/gradle.properties
# or on the command line.
if [ -n "$aapt2" ] \
        && ! grep -qs '^android\.aapt2FromMavenOverride=' "${HOME:-}/.gradle/gradle.properties" 2>/dev/null \
        && [[ " ${args[*]} " != *android.aapt2FromMavenOverride* ]]; then
    gradle_args+=("-Pandroid.aapt2FromMavenOverride=$aapt2")
fi

# Termux's Gradle daemon cannot adopt the client's environment, so a daemon
# started before ANDROID_HOME was exported would not see it. With
# local.properties the SDK is found without the environment; without it, use a
# one-shot daemon that inherits this shell's environment.
if [ ! -f local.properties ]; then
    gradle_args+=(--no-daemon)
fi

if [ ${#args[@]} -eq 0 ]; then
    args=(assembleDebug)
fi

printf 'Using Android SDK %s (platform %s)\n' "$sdk" "$compileSdk" >&2
[ -n "$aapt2" ] && printf 'Using aapt2 %s\n' "$aapt2" >&2

exec "$gradle" "${gradle_args[@]}" checkBuildEnv "${args[@]}"
