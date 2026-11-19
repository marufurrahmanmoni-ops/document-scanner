# Build log

App: DocScanner (`com.docscanner.app`), native Android, Kotlin + Compose.
Scope of this pass: the 12-item core loop requested on 2026-11-19, i.e. milestones M1 + M2 of `architecture.md`.

## Build status: NOT YET COMPILED WITH GRADLE

**Blocker:** this cloud workspace cannot reach the hosts an Android build needs. Every request to
`dl.google.com`, `maven.google.com`, `repo1.maven.org`, `repo.maven.apache.org` and `plugins.gradle.org`
is refused by the egress proxy (HTTP 403 on CONNECT, rechecked 6 times over the session). There is no
Android SDK in the image, and the SDK, the Android Gradle Plugin, AndroidX/Compose, Room, CameraX, Tink and
SQLCipher only exist on those hosts. `./gradlew assembleDebug` therefore cannot run here.

What WAS verified without those hosts:

| check | how | result |
| --- | --- | --- |
| Pure-Kotlin core compiles | official Kotlin 2.2.0 compiler (from GitHub releases) on `model/`, `util/`, `imaging/QuadTracker.kt` + `CoreLogicTest.kt` | compiles, **10/10 tests pass** (OrderKey, Quad, QuadTracker, UuidV7, file names) |
| Imaging code type-checks against the real OpenCV API | `EdgeDetector.kt`, `FilterEngine.kt`, `ImageOps.kt`, `QuadTracker.kt` compiled against the OpenCV **4.12.0** Android SDK Java sources (GitHub release) + small `android.graphics` stubs | compiles, 0 errors |
| Edge detection algorithm | line-for-line Python port (`tools/imaging-validation/`) on synthetic photos: page + perspective + shadow + noise + blur | **36/36** IoU ≥ 0.9 (dark desk 0.978, light desk 0.994, busy background 0.972); near-white desk + heavy blur **12/12**; **0/10** false detections on empty clutter; ~5 ms/frame at 640×480 (desktop) |
| Filters | same port | shadowed vs lit paper 181 / 234 → **254 / 254** after Clean; red stamp kept by Clean; see `clone-screens/imaging-pipeline.jpg` |
| Schema | `replica/schema.sql` in SQLite | loads; duplicate page order rejected; Bengali + English FTS match |
| String resources | script | every `R.string` used is defined in `values/` and `values-bn/` |

**To build** (on a machine with Android Studio, or here once the hosts are allowed):

```bash
./gradlew :app:assembleDebug        # APK in app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest    # JVM unit tests
```

The first build is expected to surface some compile errors in the Compose/Room/CameraX/Tink code that could not be
type-checked here. The next session with build access should run the build, fix them, and update this log.

## Screens

| ID | screen | date | status | missing | harder than expected |
| --- | --- | --- | --- | --- | --- |
| shell | App shell, theme, navigation | 2026-11-19 | code done | Inter/Noto fonts not bundled (system font for now) | adaptive rail vs bottom bar with a docked FAB |
| S03 | Home | 2026-11-19 | code done | search field (search is M3) | — |
| S04 | Files | 2026-11-19 | code done | folders, multi-select, list view (M3) | — |
| S05 | Tools | 2026-11-19 | code done | only 3 tools: the rest arrive with their features (no placeholders) | — |
| S07 | Camera | 2026-11-19 | code done | ID card / QR modes, volume-key shutter, grid toggle | **Bigger than it looked:** analysis/capture coordinate mapping. Solved by binding all three use cases at 4:3 and drawing the preview FIT_CENTER in a 3:4 box, so normalised quads map exactly. The page is re-detected on the full capture |
| S07a | Camera permission | 2026-11-19 | code done | — | permanently-denied detection needs the "asked once" flag |
| S08 | Gallery import | 2026-11-19 | code done | — | EXIF orientation must be applied and stripped before detection |
| S09 | Crop | 2026-11-19 | code done | edge-midpoint handles, keyboard/TalkBack corner nudging | loupe maths for sampled source |
| S10 | Enhance | 2026-11-19 | code done | brightness/contrast sliders (engine supports them) | — |
| S11 | Batch review | 2026-11-19 | code done | drag-to-reorder (Move earlier/later works) | — |
| S12 | Document view | 2026-11-19 | code done | pinch zoom, thumbnail rail on wide screens | — |
| S16 | Share / export | 2026-11-19 | code done | searchable PDF, password (M3/M4) | SAF tree writes for multi-JPG save |
| S19 | Settings | 2026-11-19 | code done | OCR, security, storage sections (arrive with features) | — |

## Deviations from architecture.md (deliberate, to revisit)

1. **Single `:app` module**, with packages that mirror the planned modules (`designsystem`, `data`, `imaging`, `security`,
   `storage`, `feature/*`). Split into Gradle modules at M3, once the build is green.
2. **Manual DI (`AppContainer`) instead of Hilt.** Fewer moving parts for the first compile. Swap at the module split.
3. **Renders run in an app-scoped coroutine, not WorkManager.** Interrupted renders resume at the next launch
   (`DocumentRepository.housekeeping`). WorkManager arrives with OCR (M3).
4. **Fonts:** platform sans instead of bundled Inter + Noto Sans Bengali, because fonts.google.com is unreachable here.
   Drop the `.ttf` files in `res/font` and change one line in `Type.kt`.
5. **Room schema v1 = documents, pages, sync_outbox.** folders, page_ocr, tags arrive with migrations, as planned.
   `pages.source_rotation` was added to the schema (the camera stores JPEGs un-rotated, the quad is in upright space).
6. **Camera locks to portrait** while open (screens.md: rotate controls, not layout). Rotated controls are a later polish.

## Imaging changes found by validation

- Brightness-based Canny thresholds found **0/12** pages on a light desk. They were replaced with thresholds taken
  from the 90th percentile of gradient magnitude plus a fixed low pair, which found **12/12**. The change is ported to Kotlin.
- The Auto filter threshold was moved 18 → 8. A page with a red stamp scored 11–12 and would have lost its colour.
- The perspective warp now uses `BORDER_REPLICATE`, which removes a 1 px dark edge in B&W output.

## Bengali

`values-bn/strings.xml` covers every string. It is a draft translation, so **a native speaker must review it before release.**

## Next

1. Run the Gradle build with host access, fix compile errors, and install on a real phone (one low-end, one mid-range).
2. Calibrate `QuadTracker.minSharpness` on real devices (25 is a guess).
3. Run `/replica-test` on F01–F03, then M3: OCR (Tesseract eng+ben), search, folders.
