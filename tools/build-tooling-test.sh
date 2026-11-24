#!/bin/sh
# shellcheck shell=bash
if [ -z "${BASH_VERSION:-}" ]; then
    exec bash "$0" "$@"
fi
#
# Device-free checks for the build tooling: the wrapper hash guard and the
# Termux wrapper's environment handling. No SDK, no Gradle, no network.
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
tmp=$(mktemp -d "${TMPDIR:-/tmp}/keeper-build-tooling.XXXXXX")
trap 'rm -rf "$tmp"' EXIT

checks=0
failed=0
ok() {
    checks=$((checks + 1))
    printf 'ok %s\n' "$*"
}
fail() {
    checks=$((checks + 1))
    failed=$((failed + 1))
    printf 'FAIL %s\n' "$*" >&2
}
expect_failure() {
    local what=$1
    shift
    local output
    if output=$("$@" 2>&1); then
        fail "$what should have failed"
    else
        ok "$what"
    fi
}

# ---------------------------------------------------------------------------
# tools/check-wrapper.sh
# ---------------------------------------------------------------------------
wrapper_tree=$tmp/wrapper
mkdir -p "$wrapper_tree/tools" "$wrapper_tree/gradle/wrapper"
cp "$root/tools/check-wrapper.sh" "$wrapper_tree/tools/"
cp "$root/gradle/wrapper/gradle-wrapper.jar" "$wrapper_tree/gradle/wrapper/"
cp "$root/gradle/wrapper/gradle-wrapper.properties" "$wrapper_tree/gradle/wrapper/"

if bash "$wrapper_tree/tools/check-wrapper.sh" >/dev/null; then
    ok "check-wrapper accepts the checked-in wrapper"
else
    fail "check-wrapper rejected the checked-in wrapper"
fi

bad=$wrapper_tree/bad
mkdir -p "$bad/tools" "$bad/gradle/wrapper"
cp "$root/tools/check-wrapper.sh" "$bad/tools/"
cp "$root/gradle/wrapper/gradle-wrapper.jar" "$bad/gradle/wrapper/"
sed 's/^distributionSha256Sum=.*/distributionSha256Sum=not-a-hash/' \
    "$root/gradle/wrapper/gradle-wrapper.properties" >"$bad/gradle/wrapper/gradle-wrapper.properties"
expect_failure "check-wrapper rejects a malformed hash" bash "$bad/tools/check-wrapper.sh"

missing=$wrapper_tree/missing
mkdir -p "$missing/tools" "$missing/gradle/wrapper"
cp "$root/tools/check-wrapper.sh" "$missing/tools/"
cp "$root/gradle/wrapper/gradle-wrapper.jar" "$missing/gradle/wrapper/"
grep -v '^distributionSha256Sum=' "$root/gradle/wrapper/gradle-wrapper.properties" \
    >"$missing/gradle/wrapper/gradle-wrapper.properties"
expect_failure "check-wrapper rejects a missing hash" bash "$missing/tools/check-wrapper.sh"

plain=$wrapper_tree/plain
mkdir -p "$plain/tools" "$plain/gradle/wrapper"
cp "$root/tools/check-wrapper.sh" "$plain/tools/"
cp "$root/gradle/wrapper/gradle-wrapper.jar" "$plain/gradle/wrapper/"
sed 's#^distributionUrl=https\\://#distributionUrl=http\\://#' \
    "$root/gradle/wrapper/gradle-wrapper.properties" >"$plain/gradle/wrapper/gradle-wrapper.properties"
expect_failure "check-wrapper rejects a non-https distribution" bash "$plain/tools/check-wrapper.sh"

# ---------------------------------------------------------------------------
# tools/termux-build.sh
# ---------------------------------------------------------------------------
build=$tmp/build
mkdir -p "$build/tools" "$build/gradle" "$build/sdk/platforms/android-35" "$build/home"
touch "$build/sdk/platforms/android-35/android.jar"
cp "$root/tools/termux-build.sh" "$build/tools/"
cp "$root/gradle/libs.versions.toml" "$build/gradle/"

run_build() {
    (cd "$build" && "$@")
}

# Missing SDK: no env, no local.properties, no $HOME/android-sdk.
out=$(env -u ANDROID_HOME -u ANDROID_SDK_ROOT -u PREFIX HOME="$tmp/nohome" \
    bash "$build/tools/termux-build.sh" 2>&1) && rc=0 || rc=$?
if [ "$rc" -ne 0 ] && [[ $out == *"no Android SDK found"* ]]; then
    ok "termux-build fails with one message when there is no SDK"
else
    fail "termux-build did not report a missing SDK (rc=$rc): $out"
fi

# Missing aapt2 on Termux: a restricted PATH hides the host's aapt2.
shim=$tmp/shims
mkdir -p "$shim"
for tool in sed head grep cat dirname; do
    target=$(command -v "$tool" 2>/dev/null) || continue
    ln -s "$target" "$shim/$tool"
done
termux_prefix=$tmp/com.termux/usr
mkdir -p "$termux_prefix/bin"
out=$(cd "$build" && env -u AAPT2 HOME="$tmp/nohome" PREFIX="$termux_prefix" \
    ANDROID_HOME="$build/sdk" PATH="$shim" \
    "$(command -v bash)" "$build/tools/termux-build.sh" 2>&1) && rc=0 || rc=$?
if [ "$rc" -ne 0 ] && [[ $out == *"aapt2 was not found"* ]]; then
    ok "termux-build fails with one message when Termux's aapt2 is missing"
else
    fail "termux-build did not report a missing aapt2 (rc=$rc): $out"
fi

# The happy path: fake SDK, fake aapt2, a fake Gradle that records its argv.
gradle_log=$tmp/gradle-args
cat >"$tmp/fake-gradle" <<'FAKE'
#!/bin/sh
printf '%s\n' "$@" >"$GRADLE_LOG"
FAKE
chmod +x "$tmp/fake-gradle"
mkdir -p "$termux_prefix/bin"
printf '#!/bin/sh\nexit 0\n' >"$termux_prefix/bin/aapt2"
chmod +x "$termux_prefix/bin/aapt2"

if (cd "$build" && env -u AAPT2 HOME="$tmp/nohome" PREFIX="$termux_prefix" \
    ANDROID_HOME="$build/sdk" GRADLE="$tmp/fake-gradle" GRADLE_LOG="$gradle_log" \
    bash "$build/tools/termux-build.sh" assembleDebug) >/dev/null 2>&1; then
    ok "termux-build runs the build with ANDROID_HOME exported"
else
    fail "termux-build could not run the fake Gradle"
fi

for expected in "--offline" "--configuration-cache" "checkBuildEnv" "assembleDebug" \
    "-Pandroid.aapt2FromMavenOverride=$termux_prefix/bin/aapt2"; do
    if grep -qx -- "$expected" "$gradle_log"; then
        ok "termux-build passes $expected"
    else
        fail "termux-build did not pass $expected (args: $(tr '\n' ' ' <"$gradle_log"))"
    fi
done

# ---------------------------------------------------------------------------
# tools/vendor-libadb.sh and the committed vendor tree
# ---------------------------------------------------------------------------
# The vendored tree keeps the build resolvable without JitPack. Its check must
# work on a fresh checkout, so run it with an empty Gradle home: verifying bytes
# that are already committed needs no module cache.
if (cd "$root" && env -u GRADLE_USER_HOME HOME="$tmp/nohome" \
        bash tools/vendor-libadb.sh --check) >/dev/null 2>&1; then
    ok "vendor-libadb verifies the committed tree without a Gradle cache"
else
    fail "vendor-libadb --check needs the Gradle cache or the committed tree is bad"
fi

# A changed byte must be refused, not served as if it were the pinned artifact.
tampered=$tmp/vendor
cp -R "$root/gradle/vendor" "$tampered"
printf 'x' >>"$tampered/com/github/MuntashirAkon/libadb-android/3.1.1/libadb-android-3.1.1.aar"
expect_failure "vendor-libadb rejects a vendored file that no longer matches its checksum" \
    env -u GRADLE_USER_HOME HOME="$tmp/nohome" \
    bash "$root/tools/vendor-libadb.sh" --dir "$tampered" --check

printf 'build tooling: %d checks, %d failed\n' "$checks" "$failed"
[ "$failed" -eq 0 ]
