#!/data/data/com.termux/files/usr/bin/bash
# build.sh -- the dependency-free bootstrap build.
#
# This is the on-phone path: javac, d8 and aapt only, no Gradle, no network and
# no src-pairing. It produces wifidebug.apk, which keeps the Termux-backed
# feature set and has no libadb-android and no pairing screen.
#
# Gradle is the canonical, feature-complete build (src-pairing + libadb):
#
#     tools/termux-build.sh
#     # -> build-gradle/outputs/apk/debug/wireless-adb-keeper-debug.apk
#
# The two APKs are signed with different keys, so one cannot update the other.
# docs/termux-build.md records the contract.
set -e
cd "$(dirname "$0")"

need() {
    if ! command -v "$1" >/dev/null 2>&1; then
        echo "missing $1; install the build tools with: pkg install openjdk-21 aapt d8 apksigner" >&2
        exit 1
    fi
}
need javac
need d8
need aapt
need apksigner
need keytool

echo "== cleaning =="
rm -rf build
mkdir -p build/classes build/dex

# Prefer the newest SDK platform the Termux side already has: a locally dropped
# android.jar can be older than the targetSdk in the manifest, which fails on
# constants the app legitimately uses. ANDROID_JAR overrides either source.
JAR=${ANDROID_JAR:-}
if [ -z "$JAR" ]; then
    JAR=$(ls -1 "$HOME"/android-sdk/platforms/*/android.jar 2>/dev/null | tail -1)
fi
if [ -z "${JAR:-}" ] && [ -f android.jar ]; then
    JAR=android.jar
fi
if [ -z "${JAR:-}" ] || [ ! -f "$JAR" ]; then
    echo "android.jar not found (put it next to build.sh, under ~/android-sdk/platforms/, or set ANDROID_JAR)" >&2
    exit 1
fi
echo "== using $JAR =="

# d8 and the manifest must agree on the minimum API. Reading it here means a
# future minSdk bump cannot leave the dex behind: the value comes from the one
# file aapt also packages. (Gradle reads the same attribute for its defaultConfig.)
MIN_SDK=$(sed -n 's/.*android:minSdkVersion="\([0-9][0-9]*\)".*/\1/p' AndroidManifest.xml | head -1)
if [ -z "$MIN_SDK" ]; then
    echo "could not read android:minSdkVersion from AndroidManifest.xml" >&2
    exit 1
fi
echo "== min API $MIN_SDK (AndroidManifest.xml) =="

echo "== compiling =="
javac -source 8 -target 8 -cp "$JAR" -d build/classes \
    $(find src -name '*.java')

echo "== dexing =="
d8 --min-api "$MIN_SDK" --lib "$JAR" --output build/dex \
    $(find build/classes -name '*.class')

echo "== packaging =="
aapt package -f -M AndroidManifest.xml -I "$JAR" -F build/app-unsigned.apk
(cd build/dex && aapt add ../app-unsigned.apk classes.dex)

if [ ! -f debug.keystore ]; then
    echo "== creating signing key =="
    keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US"
fi

echo "== signing =="
apksigner sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
    --out wifidebug.apk build/app-unsigned.apk

echo "== verifying =="
apksigner verify --verbose wifidebug.apk | head -10
ls -lh wifidebug.apk

echo "== bootstrap APK: $PWD/wifidebug.apk =="
echo "   feature set: Termux-backed, no pairing, no Gradle dependency"
echo "   canonical:   tools/termux-build.sh -> build-gradle/outputs/apk/debug/wireless-adb-keeper-debug.apk (adds pairing)"
