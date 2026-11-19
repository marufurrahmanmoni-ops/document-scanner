# Build log

App: DocScanner (`com.docscanner.app`), native Android, Kotlin + Compose.
Scope of this pass: the 12-item core loop requested on 2026-11-19, i.e. milestones M1 + M2 of `architecture.md`.

## Build status: DEBUG BUILD SUCCEEDS (verified 2026-11-19 19:30)

First real Gradle build, run after the build hosts were allowed (all six returned HTTP 200).

| step | result |
| --- | --- |
| Android SDK | installed: cmdline-tools, platforms;android-36, build-tools 35.0.0 + 36.1.0, platform-tools 37.0.1, at `/opt/android-sdk` |
| Dependency versions | every version in `gradle/libs.versions.toml` exists on Google Maven / Maven Central / the plugin portal. **Kept as pinned, no upgrades** |
| Gradle 8.14.3 (wrapper) + AGP 8.11.1 + Kotlin 2.2.0 + KSP | configuration + sync OK |
| `:app:assembleDebug` | **BUILD SUCCESSFUL**. `kspDebugKotlin` (Room) and `compileDebugKotlin` ran with **0 errors**, 0 Kotlin warnings after fixes |
| `:app:testDebugUnitTest` | **10/10 pass**, 0 failures (OrderKey, Quad, QuadTracker, UuidV7, file names) |
| `:app:lintDebug` | **0 errors**. 24 warnings: 23 are "newer version available", 1 is a deliberate `mipmap-anydpi-v26` folder |
| 16 KB page size (Play requirement) | all 14 arm64-v8a + x86_64 `.so` files have LOAD alignment 16384, incl. OpenCV 4.12 and SQLCipher 4.9 |
| Imaging validation tools | 36/36, near-white 12/12, 0/10 false positives, filters 181/234 → 254/254 |

**APK:** `app/build/outputs/apk/debug/app-debug.apk` (136 MB: 3 ABIs, full OpenCV, no shrinking. The release build with
R8 + per-ABI App Bundle will be far smaller).

**Not verified:** the app has **not been run on a device or emulator**. This workspace has no KVM, so no emulator can run.
Camera, crop, filters, PDF export and encryption are compiled but untested at runtime. Every feature therefore stays
`partial` in features.csv until it is exercised on a real phone.

### Fixes made in this build session (real errors only)

1. `jvmToolchain(17)` demanded a JDK 17 that is not installed (only JDK 21), so the build failed configuring `compileDebugJavaWithJavac`.
   → Replaced with `compilerOptions.jvmTarget = JVM_17` (same bytecode, any JDK ≥ 17 builds).
2. Lint error `ImpliedQuantity`: in Bengali the plural "one" covers 0 and 1, so the message must include the number.
   → `values-bn` `discard_body` now shows `%1$d`.
3. Warnings cleaned: deprecated `RotateRight` icon → AutoMirrored; Tink `getPrimitive(Class)` →
   `getPrimitive(RegistryConfiguration.get(), Class)`; KTX `createBitmap`, `scale`, `SharedPreferences.edit(commit = true)`;
   two "%d pages" strings reworded; deliberate portrait lock annotated.
4. Build environment, not code: the proxy throttles parallel downloads (HTTP 429), so builds run with `--max-workers=2`.

### Earlier status (before host access)


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

**To build:**

```bash
./gradlew :app:assembleDebug        # APK in app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest    # JVM unit tests
```

(The first build did surface errors, but only build configuration, not code. See the fixes above.)

## Backend build (2026-11-19, /replica-backend): built module by module, each one compiled

| module | result |
| --- | --- |
| B1 Firebase + auth | builds. Fixes: Firebase pulls the empty `listenablefuture` stub, so CameraX lost `ListenableFuture` → explicit Guava; Gradle OOM-killed (cgroup) → Kotlin compiles in-process, one 3 GB JVM |
| B2 E2E crypto + merge | **10 new unit tests pass** (20 total) |
| B3 sync engine | builds. Room v1→v2 migration SQL identical to Room's exported schema. Self-review fixed transaction-retry double-counting and orphan-page cursor loss |
| B4 security rules | **11 Firestore rule tests pass on the emulator**. Negative control fails 2/11 as expected. Firestore emulator from gcloud via dl.google.com (firebase-tools' own host is blocked). Storage rules **not executed** (runtime host blocked) |
| B5 billing + functions | app builds; functions **tsc clean, 8 tests pass**, module loads and exports 3 functions. Fix: Pub/Sub topic must be a deploy-time literal |
| B6 deletion + docs | builds; lint 0 errors; `npm audit` 8 moderate → upgraded firebase-admin 14 / functions 7 → 0 |

**Not verified:** nothing has run against a real Firebase project or Google Play (needs the owner's accounts). See
`replica/backend.md` → "What you must create". Device testing is still pending for the whole app.

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

1. Install `app-debug.apk` on a real phone (one low-end, one mid-range) and run flows F01–F03. Record crashes here.
2. Calibrate `QuadTracker.minSharpness` on real devices (25 is a guess).
3. Run `/replica-test` on F01–F03, then M3: OCR (Tesseract eng+ben), search, folders.

## 2026-11-19: /replica-test, /replica-diff, /replica-deploy preflight

- Tests: JVM 35/35 (15 new imaging tests on the real Kotlin detector and filters, run with desktop OpenCV), Firestore rules 11/11, Functions 8/8, release lint 0 errors / 45 warnings.
- Written, compile, NOT run here: 17 Robolectric data-layer tests (`src/testRobolectric`, `-Probolectric=true`), 7 instrumented tests (`src/androidTest`).
- BUG-001 (S1) fixed: `@Insert(REPLACE)` + `ON DELETE CASCADE` wiped pages on remote rename → `@Upsert`.
- BUG-002 (S3) fixed: detector accepted ragged clutter contours → each side must lie on edge pixels.
- Release: signing from git-ignored `keystore.properties` / `SIGNING_*` env; verified with a throwaway key outside the repo. AAB 59 MB, release APK 16 KB-aligned.
- `firebase-iid` excluded from firebase-functions (drops the unused push RECEIVE permission). R8 passes; runtime check pending on a device.
- Settings → About shows the developer: Md. Marufur Rahman Moni, with photo.
- Reports: `test-plan.md`, `bug-report.md`, `parity.md`, `deploy.md`, `launch/privacy-policy.md` (draft).
