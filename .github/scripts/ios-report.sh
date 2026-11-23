#!/bin/bash
# Prints the iPhone simulator test results into the log (reel details, frame sheets, screenshots as base64),
# readable where artifact downloads are blocked. Run from ios/.
command -v ffmpeg >/dev/null || brew install ffmpeg >/dev/null
echo "==REPORT"; cat out/report.txt out/timeline.txt 2>/dev/null
echo "==YAVG reel_2 $(ffmpeg -v error -i out/reel_2.mp4 -vf "signalstats,metadata=print:key=lavfi.signalstats.YAVG:file=-" -f null - 2>/dev/null | grep -o 'YAVG=[0-9.]*' | cut -d= -f2 | awk '{printf "%d ", $1}')"
for f in out/reel_*.mp4 out/timeline.mp4; do
  [ -f "$f" ] || continue
  echo "==INFO $f"
  ffprobe -v error -show_entries stream=codec_type,codec_name,width,height,avg_frame_rate:stream_side_data=rotation:format=duration -of compact "$f"
  echo "==CUTS $f $(ffmpeg -i "$f" -vf "select='gt(scene,0.3)',showinfo" -f null - 2>&1 | grep -o 'pts_time:[0-9.]*' | cut -d: -f2 | head -40 | tr '\n' ' ')"
  d=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$f")
  fps=$(python3 -c "print(max(0.25, min(2, 30/float('$d'))))")
  ffmpeg -v error -y -i "$f" -vf "fps=$fps,scale=96:-1,tile=10x3" -frames:v 1 -q:v 6 "$f.jpg"
  echo "==B64 $f $(base64 -i "$f.jpg" | tr -d '\n')"
done
for s in home setup finetune sim_home editor; do
  [ -f "out/$s.png" ] && ffmpeg -v error -y -i "out/$s.png" -vf "scale=360:-1" -q:v 6 "out/$s.jpg" && echo "==B64 $s $(base64 -i "out/$s.jpg" | tr -d '\n')"
done
true
