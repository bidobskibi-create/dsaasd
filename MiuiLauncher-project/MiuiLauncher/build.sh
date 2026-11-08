#!/bin/bash
# Usage: ./build.sh   (needs JDK 8+, Android SDK with platform android-19 + build-tools, zip)
set -e
SDK="${ANDROID_HOME:?set ANDROID_HOME}"
JAR=$(ls -d $SDK/platforms/android-*/android.jar | head -1)
BT=$(ls -d $SDK/build-tools/* | tail -1)
rm -rf out && mkdir -p out/cls
javac -source 8 -target 8 -bootclasspath "$JAR" -d out/cls src/com/archdroid/miuihome/*.java
$BT/d8 --min-api 19 --output out $(find out/cls -name '*.class')
$BT/aapt package -f -M AndroidManifest.xml -I "$JAR" -F out/unsigned.apk
(cd out && zip -q unsigned.apk classes.dex)
keytool -genkey -keystore out/k.jks -storepass miuihome -keypass miuihome -alias k -keyalg RSA -keysize 2048 -validity 10000 -dname CN=MiuiHome
$BT/apksigner sign --ks out/k.jks --ks-pass pass:miuihome --v1-signing-enabled true --v2-signing-enabled false --out out/MiuiHome.apk out/unsigned.apk
rm -rf out/fz && cp -r flashable out/fz && mkdir -p out/fz/system/app && cp out/MiuiHome.apk out/fz/system/app/
(cd out/fz && zip -qr ../MiuiHome-flash.zip .)
echo "Done: out/MiuiHome-flash.zip (flash in TWRP over the ROM)"
