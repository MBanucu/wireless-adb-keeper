#!/bin/sh
# shellcheck shell=bash
if [ -z "${BASH_VERSION:-}" ]; then
    exec bash "$0" "$@"
fi
#
# Checks the Gradle wrapper a checkout actually ships.
#
#   tools/check-wrapper.sh            # offline: URL, hash format, wrapper JAR
#   tools/check-wrapper.sh --online   # also compare the hash with Gradle's .sha256
#
# The wrapper refuses a distribution whose SHA-256 does not match
# distributionSha256Sum. CI additionally runs gradle/actions/wrapper-validation
# to check the JAR against Gradle's published checksums; this script covers the
# distribution half and works on the phone, where the online check may not.
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
props=$root/gradle/wrapper/gradle-wrapper.properties
jar=$root/gradle/wrapper/gradle-wrapper.jar
online=0
if [ "${1:-}" = "--online" ]; then
    online=1
fi

die() {
    printf 'wrapper check: %s\n' "$*" >&2
    exit 1
}

[ -f "$props" ] || die "missing $props"
[ -f "$jar" ] || die "missing $jar; restore it with 'gradle wrapper --gradle-version 9.9.2'"

url=$(sed -n 's/^distributionUrl=//p' "$props" | head -1)
hash=$(sed -n 's/^distributionSha256Sum=//p' "$props" | head -1)
validate=$(sed -n 's/^validateDistributionUrl=//p' "$props" | head -1)

[ -n "$url" ] || die "distributionUrl is missing from $props"
url=${url//\\:/:}
[[ $url == https://* ]] || die "distributionUrl is not https: $url"
[[ $url == *.zip ]] || die "distributionUrl does not name a .zip: $url"
[[ $validate == true ]] || die "validateDistributionUrl must be true (found: ${validate:-unset})"
[ -n "$hash" ] || die "distributionSha256Sum is missing; add the SHA-256 published beside the distribution"
[[ $hash =~ ^[0-9a-f]{64}$ ]] || die "distributionSha256Sum is not 64 lowercase hex characters: $hash"

# The JAR must at least be a readable zip with the wrapper's own entry. The
# checksum against Gradle's published JAR checksums is CI's job. `jar` comes
# with the JDK the build already needs.
command -v jar >/dev/null 2>&1 || die "jar is not on PATH; install a JDK or run the CI wrapper validation instead"
jar tf "$jar" 2>/dev/null | grep -q 'org/gradle/wrapper/GradleWrapperMain.class' \
    || die "$jar is not a Gradle wrapper JAR"

if [ "$online" = 1 ]; then
    command -v curl >/dev/null 2>&1 || die "curl is not on PATH; the online check needs it"
    published=$(curl -fsSL --max-time 30 "$url.sha256" | tr -d '[:space:]') \
        || die "could not download $url.sha256"
    [[ $published == "$hash" ]] \
        || die "distributionSha256Sum is $hash but Gradle publishes $published for $url"
    printf 'wrapper check: %s matches Gradle (sha256 %s)\n' "$url" "$hash"
else
    printf 'wrapper check: %s (sha256 %s, offline)\n' "$url" "$hash"
fi
