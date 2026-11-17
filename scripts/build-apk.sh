#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/tmp/android-sdk}}"
BT="$(echo "$SDK"/build-tools/* | awk '{print $NF}')"
JAR="$SDK/platforms/android-34/android.jar"
if [ ! -x "$BT/aapt2" ] || [ ! -f "$JAR" ]; then
  echo "Android SDK platform 34 and build-tools are required." >&2
  exit 1
fi
BUILD="$ROOT/build"
rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$ROOT/dist" "$ROOT/signing"
KS="$ROOT/signing/debug.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass silk-debug-key -keypass silk-debug-key \
    -alias silk -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Silk USB Print, O=Silk, C=SA"
fi
"$BT/aapt2" compile --dir "$ROOT/res" -o "$BUILD/compiled.zip"
"$BT/aapt2" link -o "$BUILD/unsigned.apk" \
  --manifest "$ROOT/AndroidManifest.xml" \
  -I "$JAR" \
  --java "$BUILD/gen" \
  --min-sdk-version 24 \
  --target-sdk-version 34 \
  --version-code 2 \
  --version-name 1.1.0 \
  "$BUILD/compiled.zip"
javac --release 8 -classpath "$JAR" -d "$BUILD/classes" \
  $(find "$ROOT/src" "$BUILD/gen" -name '*.java')
"$BT/d8" --min-api 24 --lib "$JAR" --output "$BUILD/dex" \
  $(find "$BUILD/classes" -name '*.class')
python3 - <<PY
import zipfile
apk = "$BUILD/unsigned.apk"
with zipfile.ZipFile(apk, "a") as z:
    z.write("$BUILD/dex/classes.dex", "classes.dex")
PY
"$BT/zipalign" -p -f 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"
"$BT/apksigner" sign \
  --ks "$KS" --ks-pass pass:silk-debug-key --key-pass pass:silk-debug-key \
  --ks-key-alias silk \
  --v1-signing-enabled true \
  --v2-signing-enabled true \
  --out "$ROOT/dist/silk-usb-print.apk" \
  "$BUILD/aligned.apk"
"$BT/apksigner" verify --verbose "$ROOT/dist/silk-usb-print.apk"
"$BT/aapt" dump badging "$ROOT/dist/silk-usb-print.apk" | head -n 5
echo "APK $ROOT/dist/silk-usb-print.apk"
