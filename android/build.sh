#!/usr/bin/env bash
# 不用 Gradle，直接用 Android SDK 的工具建置 APK。
# 需要：JDK 17（JAVA_HOME）與 Android SDK（ANDROID_SDK），內含 platforms;android-34 與 build-tools;34.0.0
#   JAVA_HOME=/path/to/jdk ANDROID_SDK=/path/to/sdk ./android/build.sh
# 產出：android/build/DhilosPower-debug.apk
set -euo pipefail

: "${JAVA_HOME:?請設定 JAVA_HOME（JDK 17）}"
: "${ANDROID_SDK:?請設定 ANDROID_SDK（Android SDK 根目錄）}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
A="$ROOT/android"
B="$A/build"
BT="$ANDROID_SDK/build-tools/34.0.0"
PLAT="$ANDROID_SDK/platforms/android-34/android.jar"
JAVA="$JAVA_HOME/bin/java"
VERSION_CODE="${VERSION_CODE:-1}"
VERSION_NAME="${VERSION_NAME:-0.1.0}"

# debug 簽章金鑰放在使用者目錄，重新建置後仍是同一把，才能直接覆蓋安裝
KEYSTORE="${KEYSTORE:-$HOME/.android/dhilos-debug.keystore}"

# Windows 的 Git Bash：@清單檔裡的路徑要轉成 C:/... 格式，原生的 javac / d8 才讀得懂
topath() { if command -v cygpath >/dev/null 2>&1; then cygpath -m -f -; else cat; fi; }

rm -rf "$B"
mkdir -p "$B/gen" "$B/classes" "$B/dex" "$B/root/assets/www"

echo "== 複製網頁"
cp "$ROOT/web/index.html" "$ROOT/web/icon.svg" "$ROOT/web/manifest.webmanifest" "$B/root/assets/www/"

echo "== 編譯資源"
"$BT/aapt2" compile --dir "$A/res" -o "$B/res.zip"
"$BT/aapt2" link -I "$PLAT" --manifest "$A/AndroidManifest.xml" "$B/res.zip" \
  --java "$B/gen" -o "$B/base.apk" \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME"

echo "== 編譯 Java"
find "$A/src" "$B/gen" -name '*.java' | topath > "$B/sources.txt"
# 對 android.jar 編譯要用 -bootclasspath，JDK 17 只允許搭配 target 8；lambda 由 d8 轉換（需要 core-lambda-stubs）
if command -v cygpath >/dev/null 2>&1; then
  BOOTCP="$(cygpath -m "$PLAT");$(cygpath -m "$BT/core-lambda-stubs.jar")"
else
  BOOTCP="$PLAT:$BT/core-lambda-stubs.jar"
fi
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 8 -target 8 -bootclasspath "$BOOTCP" \
  -Xlint:-options -d "$B/classes" @"$B/sources.txt"

echo "== 轉成 dex"
find "$B/classes" -name '*.class' | topath > "$B/classes.txt"
"$JAVA" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --release --min-api 26 --lib "$PLAT" \
  --output "$B/dex" @"$B/classes.txt"

echo "== 打包"
cp "$B/base.apk" "$B/unsigned.apk"
(cd "$B/dex" && "$JAVA_HOME/bin/jar" uf "$B/unsigned.apk" classes.dex)
# assets 用 jar 加入：Windows 版 aapt2 -A 會寫出反斜線路徑，App 會讀不到
(cd "$B/root" && "$JAVA_HOME/bin/jar" uf "$B/unsigned.apk" assets)
"$BT/zipalign" -f -p 4 "$B/unsigned.apk" "$B/aligned.apk"

echo "== 簽章"
if [ ! -f "$KEYSTORE" ]; then
  mkdir -p "$(dirname "$KEYSTORE")"
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KEYSTORE" -alias dhilos -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass android -keypass android -dname "CN=DhilosPower Debug" >/dev/null 2>&1
fi
"$JAVA" -jar "$BT/lib/apksigner.jar" sign --ks "$KEYSTORE" --ks-key-alias dhilos \
  --ks-pass pass:android --key-pass pass:android --out "$B/DhilosPower-debug.apk" "$B/aligned.apk"
"$JAVA" -jar "$BT/lib/apksigner.jar" verify "$B/DhilosPower-debug.apk"

echo "== 完成：$B/DhilosPower-debug.apk"
