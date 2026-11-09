#!/bin/bash
# Haalt de losse build-gereedschappen op die Baan nodig heeft (geen Gradle / Android SDK).
# Doel: een map die je aan build.sh meegeeft via BAAN_SDK.
#
#   ./setup-toolchain.sh ~/baan-sdk
#   BAAN_SDK=~/baan-sdk app/build.sh     # vanuit de repo-root, met keys/ ernaast
#
# Vereist: node/npm, java (JDK 17), python3, curl, unzip, zip.
set -e
SDK="${1:-$HOME/baan-sdk}"
mkdir -p "$SDK"; cd "$SDK"

echo ">> aapt2"
npm pack aaptjs3 >/dev/null
tar xzf aaptjs3*.tgz
cp package/bin/x64/linux/aapt2 ./aapt2 && chmod +x ./aapt2

echo ">> android.jar (API 35)"
curl -sSL -o android35.jar "https://raw.githubusercontent.com/Sable/android-platforms/master/android-35/android.jar"

echo ">> dx (dexer, uit dex2jar)"
mkdir -p d2j && cd d2j
curl -sSL -o dx.zip "https://github.com/pxb1988/dex2jar/releases/download/v2.4/dex-tools-v2.4.zip"
unzip -oq dx.zip
cd "$SDK"

echo ">> apk_sign_ts (ondertekenen)"
mkdir -p signer && cd signer
npm init -y >/dev/null
npm install apk_sign_ts >/dev/null
cd "$SDK"

echo ">> controlegereedschap (optioneel)"
pip install --break-system-packages -q apksigtool androguard || true

echo "Klaar. Gebruik:  BAAN_SDK=$SDK  bij het bouwen."
