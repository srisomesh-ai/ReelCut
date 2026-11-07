# ReelCut - rules
- Owner reviews on a phone; keep replies simple.
- Show a sample (screens) before building UI changes and wait for "go ahead"; build exactly what was approved.
- Change only what was asked. No side refactors.
- Every push to main must keep the **Build ReelCut** workflow green: the signed APK job and the emulator render test.
  Check the emulator reels (artifact `emulator-output`) after engine changes.
- The signing key in `keystore/` must never change, or installed apps cannot update.
