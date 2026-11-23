#!/data/data/com.termux/files/usr/bin/bash
set -e
cd "$(dirname "$0")"

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

echo "== compiling =="
javac -source 8 -target 8 -cp "$JAR" -d build/classes \
    $(find src -name '*.java')

echo "== dexing =="
d8 --min-api 24 --lib "$JAR" --output build/dex \
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
