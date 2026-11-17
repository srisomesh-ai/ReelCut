# ReelCut - rules
- Owner reviews on a phone; keep replies simple.
- Show a sample (screens) before building UI changes and wait for "go ahead"; build exactly what was approved.
- Change only what was asked. No side refactors.
- Every push to main must keep the **Build ReelCut** workflow green: the signed APK job and the emulator render test.
  Check the emulator reels (artifact `emulator-output`) after engine changes.
- The signing key in `keystore/` must never change, or installed apps cannot update.

## ReelCut web page (`web/index.html`, moved from scanplay.in/beatreel on 17 Nov 2026)
The browser version (was "BeatReel" in the ScanPlay repo; owner moved it here and does not want it in ScanPlay).
It is not hosted anywhere yet. Notes carried over from ScanPlay (paths there were `beatreel/index.html`):

- **Web page (owner, 5 Nov)**: `web/index.html`: pick clips / photos
  and a song, the phone makes a beat-synced 10 / 15 / 30 s reel in the browser (nothing uploaded, no credits, no account) -
  Save / Share / Try another edit. **↺ Start again** (owner, 5 Nov; `resetAll`, under Make and next to Save) clears clips, song,
  length, title and the reel; hidden while a reel records (`busy`). `[hidden]{display:none!important}` - the result area,
  its buttons and the canvas set their own display, so before that they never hid.
  **Nothing is kept (owner, 5 Nov)**: no storage, cookies or uploads (only the page and its Google font load); a refresh
  always starts empty (`autocomplete="off"`, the boxes are emptied at load) and a page brought back by Back is reloaded.
  **Standalone (owner, 5 Nov)**: never connect it to ScanPlay (no links to / from the ScanPlay Studio, no ScanPlay account,
  credits, chat, analytics or api.php).
  **Shapes, looks, built-in music (owner, 5 Nov)**: shapes 9:16 / 4:5 / 1:1 / 16:9 (`SHAPES`, about the same pixels each);
  look picker Auto + 13 looks (`LOOKS`; new: Vintage Film, Retro VHS, Pop Colour, Cool Blue, Sunset Glow, Matte Film,
  Bollywood Gold - grain, light leak, TV lines, PLAY stamp, glitch cuts, faded blacks, colour wash, glowing title).
  8 built-in beats (`BEATS`: Party Dance, Desi Dhol, Hip-Hop, Trap, EDM Drop, Happy Pop, Lo-fi Chill, Cinematic) are MADE on
  the phone (`renderBeat`: Web Audio, one bar per small offline render, 4 at a time, ~1.5 s; intro / build / drop / break /
  drop, about -14 LUFS) - no music files, nobody else's music, so free to use. "Another version" = new key, chords and hook;
  tapping a beat plays 12 s around the drop; a built-in beat cuts on its exact tempo (no guessing). ScanPlay's own
  `assets/music` tracks are NOT used (their licence is not recorded, and ReelCut web stays standalone).
  **Cut speed (owner, 5 Nov: "soft songs do not need an immediate photo change - smooth song, smooth transitions, very
  important")**: Smooth = a photo ~3-4 s (4 / 8 / 12 / 16 beats nearest the reel shared by the clips, 2.6-4.5 s), ~1 s
  dissolves and gentle zooms, no flashes / shakes / pulses / glitch, never a short last shot; Normal ~1.4-2.6 s (faster in
  loud parts, never under 0.9 s); Fast = on the beat as before. `songPace`: a built-in beat has its own (`pace` in `BEATS`:
  Lo-fi smooth; Hip-Hop, Happy Pop, Cinematic normal; the rest fast); a song: `feelBpm` halves a tempo over 140 when the
  song is not bright (a calm guitar track was heard as 172), then smooth if dark and soft-hitting (brightness < 0.09 and
  punch < 14) or slow (< 90 BPM, brightness < 0.12), fast if >= 118 BPM and bright (>= 0.15) or punchy (>= 16), else normal
  - tuned on ScanPlay's 7 music tracks of known mood (calm / cinematic -> smooth, corporate -> normal, upbeat / festive /
  kids -> fast). The Cut speed buttons (Auto / Smooth / Normal / Fast) override it; the profile says which one was used.
  **Windows Chrome crash (owner, 5 Nov: "Aw, Snap! STATUS_BREAKPOINT" while making a reel)**: not reproducible here (this
  Chromium has no H.264 / AAC, so tests record VP9 + Opus in MP4; Windows Chrome records H.264, often on the video chip).
  Made safer: `pickMime` asks for H.264 + AAC without naming a level (`avc1,mp4a.40.2`; then levels 4.0 / 3.1) - it used
  to ask for level 3.0 (`42E01E`, for videos up to 720x576) for 720x1280 frames; all sizes are multiples of 16 and at most
  3600 blocks of 16x16 (4:5 is 832x1040, was 864x1080); each reel closes its canvas and sound feeds when done (they were
  left open and piled up), a recording error ends with a message (`rec.onerror`), and the beat maker keeps only 4 bar
  renders at a time. Test H.264 here with `window.pickMime = () => "video/mp4;codecs=avc1"`.
