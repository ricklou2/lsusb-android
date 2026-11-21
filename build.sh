#!/bin/bash
# 不用 Gradle 的 APK 构建流水线：aapt2 -> javac(R.java) -> kotlinc -> d8 -> zip -> zipalign -> apksigner
set -e

SDK=${SDK:-/home/user/android-sdk}
BT=${BT:-$SDK/build-tools/36.0.0}
PLAT=${PLAT:-$SDK/platforms/android-36/android.jar}
KOTLINC=${KOTLINC:-/tmp/kotlinc/bin/kotlinc}
KOTLIN_STDLIB=${KOTLIN_STDLIB:-/tmp/kotlinc/lib/kotlin-stdlib.jar}
JAVAC=${JAVAC:-javac}
MIN_SDK=24
TARGET_SDK=36
NAME=lsusb-gui
VERSION_CODE=${VERSION_CODE:-7}
VERSION_NAME=${VERSION_NAME:-1.6}

cd "$(dirname "$0")"
rm -rf build
mkdir -p build/gen build/classes build/dex

echo "==> 1/7 编译资源"
"$BT/aapt2" compile --dir app/res -o build/res.zip

echo "==> 2/7 链接资源 + 导出符号表"
"$BT/aapt2" link -o build/base.apk -I "$PLAT" \
  --manifest app/AndroidManifest.xml \
  --output-text-symbols build/R.txt \
  --min-sdk-version $MIN_SDK --target-sdk-version $TARGET_SDK \
  --version-code $VERSION_CODE --version-name $VERSION_NAME \
  build/res.zip

echo "==> 3/7 生成 Kotlin 版 R"
python3 gen_r_kotlin.py build/R.txt build/gen/R.kt

echo "==> 4/7 编译 Kotlin"
"$KOTLINC" -no-reflect -jvm-target 1.8 \
  -classpath "$PLAT" \
  -d build/classes \
  build/gen/R.kt $(find app/src -name '*.kt')

echo "==> 5/7 转 dex"
"$BT/d8" --release --min-api $MIN_SDK --lib "$PLAT" --output build/dex \
  $(find build/classes -name '*.class') "$KOTLIN_STDLIB"

echo "==> 6/7 打包（dex 放在根，四个 ABI 的 lib 不压缩）"
cp build/base.apk build/$NAME.unsigned.apk
( cd build && zip -q -j $NAME.unsigned.apk dex/classes.dex )
( cd app && zip -0 -q -r ../build/$NAME.unsigned.apk lib )
"$BT/zipalign" -f -P 16 4 build/$NAME.unsigned.apk build/$NAME.aligned.apk

echo "==> 7/7 签名"
if [ ! -f keystore.jks ]; then
  keytool -genkeypair -keystore keystore.jks -storepass android -keypass android \
    -alias lsusb -dname "CN=lsusb GUI, O=Doubao, C=CN" \
    -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks keystore.jks --ks-pass pass:android --key-pass pass:android \
  --min-sdk-version $MIN_SDK --v1-signing-enabled true --v2-signing-enabled true \
  --out $NAME.apk build/$NAME.aligned.apk
"$BT/apksigner" verify --print-certs $NAME.apk | head -6

echo
ls -l $NAME.apk
"$BT/aapt2" dump badging $NAME.apk | head -6
