# Test plan: DocScanner (Android)

Last run: 2026-11-19, commit after `c20fc71`. Environment: cloud Linux container, JDK 21, Android SDK 36, no KVM.

## Status words

| status | meaning |
| --- | --- |
| **PASS** | an automated test ran here and passed. The scope column says what it proves (unit, JVM imaging, rules emulator). |
| **FAIL** | ran and failed (none open) |
| **NOT RUN** | the test is written and compiles, but nothing here can run it (needs a phone/emulator, or Robolectric) |
| **MANUAL** | needs a person with a phone, a real Firebase project or Play Console; checklist below |
| **BLOCKED** | the feature is not built yet. Nothing to test. |

A journey is passed only when every case in it is PASS. **No journey is passed yet**, because nothing has run on a
phone. The imaging, sync and security logic underneath several journeys is PASS at unit level.

## What could and could not run here

| layer | tool | count | result |
| --- | --- | --- | --- |
| JVM unit + imaging | JUnit 4, desktop OpenCV 4.9 (org.openpnp) running the app's real `EdgeDetector` and `FilterEngine` | 35 | **35 pass** |
| Firestore security rules | Firestore emulator 1.19.8 + `@firebase/rules-unit-testing` | 11 (+1 negative control) | **11 pass** (rules unchanged since the run) |
| Cloud Functions | node test runner, tsc | 8 | **8 pass** |
| Lint (release) | Android lint | — | **0 errors**, 45 warnings |
| Data layer + Compose UI on JVM | Robolectric 4.16, Compose UI Test | 17 | **NOT RUN**: Robolectric's Android runtime jars redirect to `gcp.mirrors.maven.org`, which this network blocks. Kept in `src/testRobolectric`, run with `-Probolectric=true`. |
| Instrumented | AndroidJUnit4, UI Automator 2.3, Espresso, Room MigrationTestHelper | 7 | **NOT RUN**: compiles (`app-debug-androidTest.apk`); no emulator (no KVM) and no phone attached |
| Storage rules | Storage emulator | — | **NOT RUN**: emulator download from `storage.googleapis.com` is blocked |

To run what could not run here, on your computer with a phone plugged in (USB debugging on):

```bash
./gradlew :app:testDebugUnitTest -Probolectric=true      # +17 data-layer tests
adb shell pm clear com.docscanner.app
./gradlew :app:connectedDebugAndroidTest                  # 7 instrumented tests
```

## Journeys

Case IDs: `J<journey>-H` happy path, `-E` edge case, `-N` negative case.

### J1 First app launch
| case | test | tool | status |
| --- | --- | --- | --- |
| J1-H1 empty library shows "No documents yet" and a Scan button | `FirstLaunchAndPermissionTest.J1_H1` | UI Automator | NOT RUN |
| J1-E1 cold start under 2 s on a low-end phone | manual, `adb shell am start -W` | — | MANUAL |
| J1-E2 Bengali system language shows Bengali UI | manual | — | MANUAL (translation also needs a native-speaker review) |

### J2 Camera permission
| case | test | tool | status |
| --- | --- | --- | --- |
| J2-H1 allow → live preview with shutter | `J2_H1_allowingCameraShowsShutter` | UI Automator | NOT RUN |
| J2-N1 deny → explanation screen, no crash | `J2_N1_denyingCameraShowsExplanationNotACrash` | UI Automator + system dialog | NOT RUN |
| J2-N2 deny twice ("don't ask again") → "Open settings" button | manual | — | MANUAL |
| J2-E1 permission revoked in Settings while app in background | manual | — | MANUAL |

### J3 Document capture
| case | test | tool | status |
| --- | --- | --- | --- |
| J3-H1 shutter → crop screen | `CaptureAndInterruptionTest.J3_H1` | UI Automator | NOT RUN |
| J3-E1 camera interruption: Home, reopen, capture still works | `J3_E1_homeAndBackResumesPreview` | UI Automator | NOT RUN |
| J3-E2 rotation during preview keeps camera | `J3_E2_rotationDuringPreviewKeepsCamera` | UI Automator | NOT RUN |
| J3-E3 incoming call / another app takes the camera | manual | — | MANUAL |
| J3-E4 auto-capture fires once when the page is steady and sharp | `QuadTrackerTest` (3) | JUnit | **PASS** (unit) |
| J3-E5 blurry frames never auto-capture | `QuadTrackerTest.blurryFramesNeverLock`, `ImagingTest.blurLowersSharpnessScoreUsedByAutoCapture` | JUnit + OpenCV | **PASS** (JVM) |
| J3-E6 storage full while saving a capture | `DataLayerTest.storageFullFailsLoudlyAndLeavesNoPartialFile` | Robolectric | NOT RUN |
| J3-E7 import from gallery | manual | — | MANUAL |

### J4 Auto edge detection
Synthetic photos rendered with perspective, shadow, sensor noise and blur; pass = IoU ≥ 0.9 with the true page.

| case | test | status |
| --- | --- | --- |
| J4-H1 A4 page on a dark desk (10 scenes) | `a4PageOnDarkDesk` | **PASS** 10/10 |
| J4-E1 white page on a light desk | `a4PageOnLightDesk_whiteOnWhite` | **PASS** 10/10 |
| J4-E2 cluttered background | `pageOnClutteredBackground` | **PASS** 10/10 |
| J4-E3 receipt (long, narrow) | `longNarrowReceipt` | **PASS** (≥ 9/10 required) |
| J4-E4 identity card (small in frame) | `identityCardSmallInFrame` | **PASS** (≥ 9/10) |
| J4-E5 open book with gutter | `openBookTwoPagesWithGutter` | **PASS** (≥ 9/10) |
| J4-E6 low light (35% exposure, more noise) | `lowLight` | **PASS** (≥ 8/10) |
| J4-E7 blur (σ = 4 px) | `blurryPhoto` | **PASS** (≥ 8/10) |
| J4-N1 clutter with no page → nothing detected (20 scenes) | `emptyClutterIsNotADocument` | **PASS** 0 false positives, after BUG-002 fix |
| J4-N2 blank frame | `blankFrameFindsNothing` | **PASS** |
| J4-M1 real photos of real receipts, IDs, books, at night | manual on a phone | MANUAL. Synthetic scenes do not prove real-world accuracy. |

### J5 Manual cropping
| case | test | status |
| --- | --- | --- |
| J5-H1 corners stay ordered and valid while dragging | `QuadTest` (3) | **PASS** (unit) |
| J5-H2 drag a corner with the magnifier, apply | manual | MANUAL |
| J5-E1 no edges found → full-frame quad offered | manual | MANUAL |

### J6 Perspective correction
| case | test | status |
| --- | --- | --- |
| J6-H1 warp flattens a tilted page | Python port of the pipeline, `tools/imaging-validation` 36/36 | **PASS** (port, not the Kotlin code) |
| J6-H2 the same on the Kotlin `PageRenderer` | none yet | NOT RUN: add a JVM test like `ImagingTest` |

### J7 Image enhancement
| case | test | status |
| --- | --- | --- |
| J7-H1 Clean evens out shadow, keeps ink dark | `cleanFlattensShadowAndKeepsInkDark` | **PASS** |
| J7-H2 B&W output is pure black and white | `blackAndWhiteIsBinary` | **PASS** |
| J7-E1 Auto keeps a coloured stamp, drops colour on plain pages | `autoKeepsColourStampsAndDropsGrayPages` | **PASS** |
| J7-E2 every filter on a 20×30 and a 12 MP image | `everyFilterHandlesTinyAndHugeImages` | **PASS** |
| J7-M1 filter switch under 300 ms on a low-end phone | manual | MANUAL |

### J8 Multi-page scanning
| case | test | status |
| --- | --- | --- |
| J8-H1 reorder changes one row | `DataLayerTest.reorderChangesOneRowAndKeepsOrderUnique`, `OrderKeyTest` | OrderKeyTest **PASS**; DataLayerTest NOT RUN |
| J8-E1 page added after a delete goes last | `DataLayerTest` | NOT RUN |
| J8-E2 delete is soft and updates the count | `DataLayerTest` | NOT RUN |
| J8-E3 discarding a draft never deletes a saved document | `DataLayerTest` | NOT RUN |
| J8-M1 30-page batch on a 3 GB-RAM phone, no OOM | manual | MANUAL |

### J9 PDF generation
| case | test | status |
| --- | --- | --- |
| J9-H1 multi-page PDF opens in another reader | manual | MANUAL |
| J9-E1 large PDF: 100 pages, size and memory | manual | MANUAL. No automated PDF test yet: gap. |
| J9-E2 A4 / Letter / Auto page sizes | manual | MANUAL |

### J10 PDF sharing
| case | test | status |
| --- | --- | --- |
| J10-H1 share sheet receives a PDF through FileProvider | manual | MANUAL |
| J10-E1 file name with Bengali title | `UtilTest.safeFileNames` | **PASS** (unit) |

### J11 Bengali OCR · J12 English OCR · J13 Document search · J14 Folder management · J15 PDF editing
**BLOCKED**: not built (features.csv rows are `no`). Planned cases, written for when they are:
- J11/J12: Tesseract `ben` and `eng` character accuracy on 20 printed pages each (target ≥ 95% eng, ≥ 90% ben); mixed-script page; low light; receipt fonts; OCR off by default (users complained about forced OCR).
- J13: title search, OCR-text search, Bengali query, empty result, 1,000 documents under 200 ms.
- J14: create, rename, nest, move (no file loss: a known complaint about the original), delete non-empty folder.
- J15: reorder/rotate/delete pages in a saved PDF, add pages, undo.

### J16 Cloud backup
| case | test | status |
| --- | --- | --- |
| J16-H1 encryption is authenticated and bound to the record id | `VaultCryptoTest` (4) | **PASS** |
| J16-N1 another user cannot read or write my records | Firestore rules tests | **PASS** (emulator) |
| J16-N2 Storage rules | storage.rules | NOT RUN (emulator blocked) |
| J16-E1 nothing uploads before the user turns backup on | manual (watch network in Android Studio profiler) | MANUAL |
| J16-E2 network failure mid-upload → retried with backoff | manual (airplane mode) | MANUAL |
| J16-E3 every local change is queued for upload | `DataLayerTest.everyChangeIsQueuedForSync` | NOT RUN |

### J17 Document restoration
| case | test | status |
| --- | --- | --- |
| J17-H1 right passphrase unlocks, wrong one does not | `VaultCryptoTest.unlockWithRightPassphraseOnly` | **PASS** |
| J17-H2 conflicts merge field by field, edit beats delete | `SyncMergeTest` (6) | **PASS** |
| J17-E1 rename on phone B keeps pages on phone A (BUG-001) | `DataLayerTest.applyingARemoteDocumentUpdateKeepsItsPages` + `tools/repro/bug001_replace_cascade.py` | repro script **PASS**; Robolectric test NOT RUN |
| J17-M1 restore on a second phone against a real Firebase project | manual | MANUAL |

### J18 Subscription
| case | test | status |
| --- | --- | --- |
| J18-H1 purchase token verified on the server, entitlement written | functions tests (8) | **PASS** (mocked Play API) |
| J18-M1 license-tester purchase, cancel, restore | Play Console internal track | MANUAL (needs your Play account) |

### J19 Account deletion
| case | test | status |
| --- | --- | --- |
| J19-H1 user can delete only their own data (rules) | Firestore rules tests | **PASS** |
| J19-H2 server cleanup function | functions tests | **PASS** |
| J19-M1 delete in app → Auth user, Firestore and Storage gone | manual against real project | MANUAL |

## Conditions matrix

| condition | where it is covered | status |
| --- | --- | --- |
| Low light | J4-E6 | PASS (synthetic) · real phone MANUAL |
| Blurry documents | J4-E7, J3-E5 | PASS (synthetic) |
| Receipts | J4-E3 | PASS (synthetic) |
| Identity cards | J4-E4 | PASS (synthetic) |
| Books | J4-E5 | PASS (synthetic) |
| Multi-page documents | J8 | unit PASS (ordering) · rest NOT RUN |
| Large PDF files | J9-E1 | MANUAL, no automated test (gap) |
| Camera interruptions | J3-E1, J3-E2, J3-E3 | NOT RUN / MANUAL |
| Storage limitations | J3-E6 | NOT RUN |
| Network failures | J16-E2 | MANUAL |
| Permission denial | J2-N1, J2-N2 | NOT RUN / MANUAL |

## Manual checklist for the first phone session
1. Install `app-debug.apk`. J1, J2 (allow and deny twice), J3 with Home and a phone call in the middle.
2. Scan a receipt, an ID card, a book spread and a page in a dim room. Note any wrong outline.
3. 30-page batch, save, share the PDF to another app, open it.
4. Fill the storage almost full (`adb shell fallocate` a big file) and save a scan: expect a clear error.
5. With a real Firebase project: sign in, turn backup on, airplane mode mid-upload, back online. Restore on a second phone.
