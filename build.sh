#!/bin/sh
# Builds out/quiettv.apk with the Android SDK directly (no Gradle, no libraries).
# Needs: Java 17+ and the Android SDK with platforms;android-34 and build-tools;34.0.0 (or newer).
#   macOS: brew install openjdk@17 && brew install --cask android-commandlinetools
#          sdkmanager "platforms;android-34" "build-tools;34.0.0"
set -e
cd "$(dirname "$0")"

if [ -z "$JAVA_HOME" ]; then
  for j in /opt/homebrew/opt/openjdk@17 /usr/local/opt/openjdk@17 /usr/lib/jvm/java-17-openjdk-amd64 /usr/lib/jvm/java-17-openjdk; do
    [ -d "$j" ] && JAVA_HOME=$j && break
  done
fi
[ -n "$JAVA_HOME" ] && export PATH="$JAVA_HOME/bin:$PATH"
java -version 2>&1 | grep -qE 'version "(1[7-9]|[2-9][0-9])' || { echo "Java 17 or newer is needed (set JAVA_HOME)." >&2; exit 1; }

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
[ -n "$SDK" ] || for d in "$HOME/Library/Android/sdk" /opt/homebrew/share/android-commandlinetools "$HOME/Android/Sdk"; do
  [ -d "$d/platforms/android-34" ] && SDK=$d && break
done
JAR=$SDK/platforms/android-34/android.jar
BT=$(ls -d "$SDK"/build-tools/3[4-9]* 2>/dev/null | sort -V | tail -1)
[ -f "$JAR" ] && [ -n "$BT" ] || { echo "Android SDK not found: set ANDROID_HOME and install platforms;android-34 and build-tools;34.0.0." >&2; exit 1; }

rm -rf out
mkdir -p out/classes out/dex out/res


"$BT/aapt2" compile --dir app/res -o out/res/
mkdir -p out/gen
"$BT/aapt2" link -o out/base.apk -I "$JAR" --manifest app/AndroidManifest.xml --java out/gen \
  --min-sdk-version 26 --target-sdk-version 34 out/res/*.flat

javac -nowarn -Xlint:-options -encoding UTF-8 -source 8 -target 8 \
  -bootclasspath "$JAR" -cp "$BT/core-lambda-stubs.jar" -d out/classes $(find app/src out/gen -name '*.java')
"$BT/d8" --min-api 26 --release --lib "$JAR" --output out/dex $(find out/classes -name '*.class')

cp out/base.apk out/unsigned.apk
(cd out/dex && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 out/unsigned.apk out/aligned.apk

# The signing key is made on the first build. Keep it: updates must be signed with the same key.
KS=${KEYSTORE:-quiettv.jks}
PASS=${KEYSTORE_PASS:-quiettv-local}
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass "$PASS" -keypass "$PASS" -alias quiettv \
  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=QuietTV" >/dev/null 2>&1
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$PASS" --out out/quiettv.apk out/aligned.apk
echo "Built out/quiettv.apk ($(wc -c < out/quiettv.apk | tr -d ' ') bytes)"
