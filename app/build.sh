#!/bin/bash
set -e
SDK=${BAAN_SDK:-/tmp/claude-0/sdk}
D2J=$SDK/d2j/dex-tools-v2.4/lib
B=build; rm -rf $B; mkdir -p $B/res $B/classes $B/gen
$SDK/aapt2 compile --dir res -o $B/res/
$SDK/aapt2 link -o $B/base.apk -I $SDK/android35.jar --manifest AndroidManifest.xml \
  --java $B/gen $B/res/*.flat --min-sdk-version 26 --target-sdk-version 33 -A assets
javac -nowarn -Xlint:-options -source 8 -target 8 -bootclasspath $SDK/android35.jar -classpath $SDK/android35.jar \
  -d $B/classes $(find src $B/gen -name '*.java')
# verify no invokedynamic (Android has no LambdaMetafactory)
if find $B/classes -name '*.class' -exec javap -c {} \; | grep -q invokedynamic; then
  echo "ERROR: invokedynamic present"; exit 1
fi
java -cp $D2J/dx-30.0.2.jar com.android.dx.command.Main --dex --min-sdk-version=26 --output=$B/classes.dex $B/classes
cp $B/base.apk $B/unsigned.apk
(cd $B && zip -q unsigned.apk classes.dex)
python3 zipalign.py $B/unsigned.apk $B/aligned.apk
node keys/sign.mjs $B/aligned.apk $B/Baan.apk
apksigtool verify $B/Baan.apk 2>&1 | grep -i verified || true
