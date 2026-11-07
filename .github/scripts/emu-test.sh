#!/bin/bash
# Runs inside the Android emulator job: installs the app, makes test reels, pulls them out, takes a screenshot.
set -x
PKG=com.srisomesh.reelcut
mkdir -p emu-out
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb logcat -c
adb shell am instrument -w -e class $PKG.RenderTest $PKG.test/androidx.test.runner.AndroidJUnitRunner | tee emu-out/instrument.txt
for f in $(adb shell run-as $PKG ls files/test-out | tr -d '\r'); do
  adb exec-out run-as $PKG cat "files/test-out/$f" > "emu-out/$f"
done
adb logcat -d > emu-out/logcat.txt
adb shell am start -n $PKG/.MainActivity
sleep 8
adb exec-out screencap -p > emu-out/home.png
grep -q "OK (1 test)" emu-out/instrument.txt
