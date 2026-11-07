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
# Print reel details + small frame sheets into the log (readable where artifact downloads are blocked)
set +x
command -v ffmpeg >/dev/null || { sudo apt-get update -qq && sudo apt-get install -y -qq ffmpeg >/dev/null; }
for f in emu-out/reel_*.mp4; do
  echo "==INFO $f"; cat "${f%.mp4}.txt" 2>/dev/null
  ffprobe -v error -show_entries stream=codec_type,codec_name,width,height,avg_frame_rate:format=duration -of compact "$f"
  echo "==CUTS $f $(ffmpeg -i "$f" -vf "select='gt(scene,0.3)',showinfo" -f null - 2>&1 | grep -o 'pts_time:[0-9.]*' | cut -d: -f2 | head -40 | tr '\n' ' ')"
  ffmpeg -v error -y -i "$f" -vf "fps=2,scale=96:-1,tile=10x3" -frames:v 1 -q:v 6 "$f.jpg"
  echo "==B64 $f $(base64 -w0 "$f.jpg")"
done
ffmpeg -v error -y -i emu-out/home.png -vf "scale=360:-1" -q:v 6 emu-out/home.jpg && echo "==B64 home $(base64 -w0 emu-out/home.jpg)"
grep -q "OK (1 test)" emu-out/instrument.txt
