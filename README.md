# DocScanner

An original, offline-first Android document scanner. Kotlin, Jetpack Compose, CameraX, OpenCV, Room + SQLCipher, Tink.

Planning documents live in `replica/`: recon, architecture, schema, design system, feature matrix and build log.

## What works (code complete, first Gradle build pending; see replica/build-log.md)

- Camera with a live page outline, auto-capture when steady, flash and torch, single and batch modes
- Camera permission flow, including "permanently denied → open settings" and "import photos instead"
- Gallery import through the Photo Picker (no storage permission)
- Corner adjustment with a magnifier, re-detect, full photo
- Perspective correction and filters: Original, Auto, Clean, Gray, B&W, B&W soft
- Multi-page review: retake, re-crop, reorder, delete, name
- PDF (Fit, A4 or Letter) and JPG export, share sheet or save to a folder you pick. No watermark
- Encrypted storage: SQLCipher database, Tink-encrypted images, keys in Android Keystore, no INTERNET permission
- English and Bengali UI

## Build

Requirements: JDK 17+, Android SDK 36.

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Imaging validation (no Android needed)

```bash
pip install opencv-python-headless numpy
python3 tools/imaging-validation/run.py     # edge detection on 36 synthetic photos
python3 tools/imaging-validation/run2.py    # near-white desk, false positives, speed
python3 tools/imaging-validation/filt.py    # filter pipeline image
```
