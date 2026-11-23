#!/usr/bin/env bash
# Runs the JVM suites on the phone or in CI. The parser, the codecs and the
# other pure classes have no Android dependency, so this needs no device, no
# Gradle and no test framework - only a javac.
#
# The suites are found, not listed: a new `*Test.java` runs here without editing
# this script or build.gradle.kts. The app sources are compiled too, against the
# same android.jar build.sh uses when one is available, but the *run* classpath
# has only the test output on it. A suite that reaches an Android API by accident
# therefore fails with NoClassDefFoundError instead of passing quietly.
#
# Two things make this fast, borrowed from android-accessible-terminal's
# terminal/test.sh: javac runs again only when the file list or an mtime changed
# (a deleted suite must not leave a stale class behind), and every suite runs in
# one JVM through SuiteRunner.
#
#   bash tests/unit.sh
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/.." && pwd)
work=$here/out/unit
listing=$here/out/unit.sources
mkdir -p "$here/out"

# build.sh reads ANDROID_JAR, then the newest installed platform, then a jar
# beside the script. Keep the same order: a stale android.jar at the repository
# root must not win over an SDK platform that the app actually targets. CI sets
# ANDROID_HOME/ANDROID_SDK_ROOT instead of keeping the SDK under $HOME, so both
# are searched before the local Termux default.
android_jar=${ANDROID_JAR:-}
if [ -z "$android_jar" ]; then
    # `sort -V` matters: a plain lexicographic tail would put android-9 after
    # android-35, and an older jar then fails on constants the app uses.
    for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/android-sdk"; do
        if [ -n "$sdk" ]; then
            android_jar=$(ls -1 "$sdk"/platforms/*/android.jar 2>/dev/null \
                | sort -V | tail -1 || true)
        fi
        if [ -n "$android_jar" ]; then
            break
        fi
    done
fi
for candidate in "$here/android.jar" "$root/android.jar"; do
    if [ -z "$android_jar" ] && [ -f "$candidate" ]; then
        android_jar=$candidate
    fi
done
if [ -n "$android_jar" ] && [ ! -f "$android_jar" ]; then
    android_jar=
fi

# With a jar, compile the whole app: the suites may then reach any class and
# the run classpath still has no android.jar. Without one, keep only the sources
# that do not import android.*, which is enough for the pure suites.
if [ -n "$android_jar" ]; then
    mapfile -t sources < <(find "$root/src" -name '*.java' | sort)
    echo "android.jar: $android_jar"
else
    sources=()
    while IFS= read -r file; do
        if ! grep -q '^import android\.' "$file"; then
            sources+=("$file")
        fi
    done < <(find "$root/src" -name '*.java' | sort)
    echo "android.jar: not found; compiling Android-free sources only"
fi

# TestSupport and SuiteRunner are compiled but not discovered as suites.
mapfile -t test_sources < <(find "$here/unit" -name '*.java' | sort)
mapfile -t tests < <(find "$here/unit/java" -name '*Test.java' | sort)

if [ "${#tests[@]}" -eq 0 ]; then
    echo "FAIL no *Test.java under $here/unit/java" >&2
    exit 1
fi

# The cache key is the sorted file list plus each file's mtime and size. A
# deleted suite changes the list, so the stale class cannot survive; an edit
# changes the mtime. The jar is part of the key for the same reason.
current=$listing.new
{
    printf 'android.jar=%s' "${android_jar:-none}"
    if [ -n "$android_jar" ]; then
        printf ' %s' "$(stat -c '%Y:%s' "$android_jar")"
    fi
    printf '\n'
    for file in "${sources[@]}" "${test_sources[@]}"; do
        printf '%s %s\n' "$(stat -c '%Y:%s' "$file")" "$file"
    done
} > "$current"

if [ ! -f "$listing" ] || [ ! -d "$work" ] || ! cmp -s "$current" "$listing"; then
    # Drop the old key before javac: a failed compile must not leave a listing
    # that matches the partially filled output on the next run.
    rm -f "$listing"
    rm -rf "$work"
    mkdir -p "$work"
    javac_args=(-nowarn --release 17 -encoding UTF-8 -d "$work")
    if [ -n "$android_jar" ]; then
        javac_args+=(-cp "$android_jar")
    fi
    javac "${javac_args[@]}" "${sources[@]}" "${test_sources[@]}"
    mv "$current" "$listing"
    echo "javac: compiled ${#sources[@]} app and ${#test_sources[@]} test sources"
else
    rm -f "$current"
    echo "javac: skipped (file list and mtimes unchanged)"
fi

classes=()
for file in "${tests[@]}"; do
    class=${file#"$here/unit/java/"}
    class=${class%.java}
    classes+=("${class//\//.}")
done

# No android.jar here on purpose: see the comment at the top.
java -cp "$work" io.github.mbanucu.wirelessadbkeeper.harness.SuiteRunner "${classes[@]}"
