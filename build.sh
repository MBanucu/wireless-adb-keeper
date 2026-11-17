#!/data/data/com.termux/files/usr/bin/bash
set -e
cd "$(dirname "$0")"

echo "== cleaning =="
rm -rf build
mkdir -p build/classes build/dex

echo "== compiling =="
javac -source 8 -target 8 -cp android.jar -d build/classes \
    $(find src -name '*.java')

echo "== dexing =="
d8 --min-api 24 --lib android.jar --output build/dex \
    $(find build/classes -name '*.class')

echo "== packaging =="
aapt package -f -M AndroidManifest.xml -I android.jar -F build/app-unsigned.apk
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
