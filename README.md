# ReelCut

Internal Android video editor (Sri Bharat GPS Tracker Pvt Ltd). Not for the Play Store yet.

**Phase 1 - Auto reel:** pick 5-15 clips/photos and a song, pick 10/15/30 s. The phone analyses the song
(beats, tempo, energy, bass, brightness), picks a look and cutting rhythm from it, finds the best moments in
each clip, and renders a beat-synced 9:16 reel with the phone's hardware encoder (Media3 Transformer).
Preview at 720p, export at 1080p to Movies/ReelCut, share to WhatsApp.

Planned: phase 2 timeline editor, phase 3 pro extras.

## Install
Every push to `main` builds a signed APK: GitHub -> Releases -> latest -> `ReelCut-1.0.N.apk`.
New versions install over the old one (same key, `keystore/`).

## Code
- `engine/SongAnalyzer.kt` - beat tracking + song profile (port of the BeatReel web engine)
- `engine/Styles.kt` - 6 looks, song -> style, shot plan
- `engine/ClipScanner.kt` - best moments per clip, shot -> clip assignment
- `engine/LookEffect.kt` - one GL shader per shot: grade, vignette, zoom punch, bass shake, transitions
- `engine/Renderer.kt` - Media3 composition (video sequence + song sequence) -> MP4
- `RenderTest` (androidTest) - CI renders 3 real reels on an emulator and uploads them (`emulator-output`)
