#!/bin/sh
# shellcheck shell=bash
if [ -z "${BASH_VERSION:-}" ]; then
    exec bash "$0" "$@"
fi
#
# Copies the JitPack-hosted libadb-android and its spake2 dependency from the
# Gradle cache into a project-controlled Maven repository at gradle/vendor/.
#
# settings.gradle.kts already looks in gradle/vendor first, so after this script
# runs the build resolves those two modules without JitPack. The script verifies
# every copied file against gradle/verification-metadata.xml before it writes
# anything; a JitPack re-publication with different bytes is refused.
#
# This is the fallback, not the default: JitPack stays the source until someone
# runs this and commits gradle/vendor/ (or points the same repository at a
# mirror). docs/termux-build.md records the policy.
#
# Usage:
#   tools/vendor-libadb.sh                 # populate gradle/vendor
#   tools/vendor-libadb.sh --check         # verify an existing gradle/vendor
#   tools/vendor-libadb.sh --dir /path     # somewhere else (a mirror checkout)
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"

die() {
    printf 'vendor: %s\n' "$*" >&2
    exit 1
}

check_only=0
dest=gradle/vendor
while [ $# -gt 0 ]; do
    case $1 in
        --check) check_only=1 ;;
        --dir)
            [ $# -ge 2 ] || die "--dir needs a path"
            dest=$2
            shift
            ;;
        -h | --help)
            sed -n '2,25p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *) die "unknown argument: $1" ;;
    esac
    shift
done

metadata=gradle/verification-metadata.xml
[ -f "$metadata" ] || die "missing $metadata; run ./gradlew --write-verification-metadata sha256 assembleDebug first"
[ -f gradle/libs.versions.toml ] || die "missing gradle/libs.versions.toml"
version=$(sed -n 's/^libadb *= *"\([^"]*\)".*/\1/p' gradle/libs.versions.toml | head -1)
[ -n "$version" ] || die "could not read libadb version from gradle/libs.versions.toml"

cache=${GRADLE_USER_HOME:-${HOME:-}/.gradle}/caches/modules-2/files-2.1
[ -d "$cache" ] || die "no Gradle module cache at $cache; run the Gradle build once first"

modules=(
    "com.github.MuntashirAkon:libadb-android:$version"
    "com.github.MuntashirAkon.spake2-java:spake2-android:2.2.1"
)

verify_file() {
    local file=$1
    local actual
    actual=$(sha256sum "$file" | awk '{print $1}')
    grep -q "value=\"$actual\"" "$metadata" \
        || die "$file has sha256 $actual, which is not in $metadata"
}

for module in "${modules[@]}"; do
    IFS=: read -r group artifact artifact_version <<<"$module"
    source_dir="$cache/$group/$artifact/$artifact_version"
    [ -d "$source_dir" ] || die "no cached $group:$artifact:$artifact_version under $source_dir; run the Gradle build once"

    target="$dest/${group//./\/}/$artifact/$artifact_version"
    if [ "$check_only" = 1 ]; then
        [ -d "$target" ] || die "no vendored copy at $target"
        for file in "$target"/*; do
            [ -f "$file" ] || continue
            verify_file "$file"
        done
        [ -f "$target/$artifact-$artifact_version.aar" ] \
            || die "expected $target/$artifact-$artifact_version.aar"
        printf 'vendor: %s:%s:%s verified\n' "$group" "$artifact" "$artifact_version"
        continue
    fi

    # Copy the AAR plus the metadata Gradle needs to resolve it offline.
    mapfile -t files < <(find "$source_dir" -type f \( -name '*.aar' -o -name '*.pom' -o -name '*.module' \) | sort)
    [ ${#files[@]} -gt 0 ] || die "no AAR/POM/module files for $group:$artifact:$artifact_version"
    mkdir -p "$target"
    for file in "${files[@]}"; do
        verify_file "$file"
        cp -f "$file" "$target/"
    done
    printf 'vendor: %s:%s:%s -> %s\n' "$group" "$artifact" "$artifact_version" "$target"
done

if [ "$check_only" = 1 ]; then
    exit 0
fi

cat <<EOF

Vendored into $dest. settings.gradle.kts already resolves that directory first.
Commit the files if the mirror is meant to be the repository's pin, then run:

    ./gradlew --offline assembleDebug
    tools/vendor-libadb.sh --check
EOF
