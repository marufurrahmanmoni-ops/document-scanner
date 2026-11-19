# Architecture: Scanner (an original Android document scanner)

A native, offline-first Android app. CamScanner is the functional reference only
(`recon.md`). Nothing here uses its code, binaries, endpoints or assets.

Inputs: `recon.md` (screens S01–S23, flows F01–F03), `features.csv` (75 rows),
`design/` (tokens, components, screens). Schema: `schema.sql`.

Status: plan only. No feature code is written.

---

## 1. Principles

1. **The device is the source of truth.** Every feature works in airplane mode, forever.
   Cloud sync is a later layer that reads the same tables. It does not replace them.
2. **Non-destructive pages.** The captured original is immutable. Crop, rotation, filter
   and adjustments are a stored *recipe*. Every rendered image is a rebuildable cache.
3. **Encrypted at rest from day one.** Retrofitting encryption onto existing files is
   a migration nobody wants to ship.
4. **One writer per table.** Only repositories write. ViewModels never touch DAOs or files.
5. **Native code stays behind Kotlin interfaces.** OpenCV and Tesseract sit inside
   `:core:imaging` and `:core:ocr`. Swapping either one (ML Kit, a segmentation model)
   changes one module.
6. **Boring and few dependencies.** Each library below earns its place, and all are open source.

## 2. Stack

| layer | choice | why |
| --- | --- | --- |
| language | Kotlin (latest stable K2), coroutines + Flow | your preference. Structured concurrency suits camera → process → save pipelines |
| UI | Jetpack Compose, Material 3, `material3-adaptive` (NavigationSuiteScaffold, ListDetailPaneScaffold) | your preference. Adaptive scaffolds give the bottom bar ⇄ rail switch and the two-pane library from `design/screens.md` almost for free |
| navigation | Navigation Compose, type-safe `@Serializable` routes | compile-checked arguments, deep links later |
| DI | Hilt | standard on Android. Works with WorkManager and ViewModels |
| camera | CameraX: Preview + ImageAnalysis + ImageCapture | your preference. It handles device quirks, and one lifecycle-bound use-case group covers the whole camera screen |
| imaging | OpenCV Android SDK **4.12+** (Maven `org.opencv:opencv`) | your preference. Contours, perspective warp and thresholds are mature. Pin 4.12+ for 16 KB page alignment (see risks) |
| OCR | Tesseract 5 via **Tesseract4Android** (Apache-2.0) + `eng` and `ben` traineddata (tessdata_best or _fast, picked in spike S3) | your preference. Fully offline, supports Bengali, and can emit word boxes for highlights and a text layer |
| database | Room + **SQLCipher for Android** (`SupportOpenHelperFactory`) | your preference (Room). SQLCipher encrypts the whole DB file, including the FTS index that holds the document text |
| files | own `BlobStore`: content-addressed files encrypted with **Tink** `StreamingAead` (AES-256-GCM-HKDF), keyset wrapped by Android Keystore | Jetpack Security's `EncryptedFile` is deprecated, so Tink + Keystore is the maintained path |
| settings | Proto DataStore (Tink-encrypted file for the app-lock secret) | typed, observable, transactional |
| PDF write | `android.graphics.pdf.PdfDocument` (image-only PDFs) behind a `PdfWriter` interface. **PdfBox-Android** (Apache-2.0) as the second implementation for the text layer and passwords | your preference (Android PDF generation) covers the must-have. PdfDocument cannot add an invisible OCR text layer or AES encryption, so PdfBox-Android is added at the milestone that needs those |
| PDF read | `android.graphics.pdf.PdfRenderer` | import PDF (could) and preview of exported files. Built in |
| images in UI | Coil 3 with a custom `Fetcher` that decrypts from BlobStore | memory and disk cache, downsampling, Compose integration |
| background | WorkManager (unique work, expedited where the user is waiting) | survives process death. Constraints for battery and storage |
| QR (could) | ZXing core (Apache-2.0) on ImageAnalysis frames | offline, no Google Play services dependency |
| gallery import | Android Photo Picker (`PickMultipleVisualMedia`) | no storage permission, backported by Play services |
| min / target SDK | minSdk **26**, targetSdk = the current Play requirement | 26 is Android 8.0: reliable Keystore AES-GCM and adaptive icons, and it covers nearly all active devices. Lower it to 24 only if Bangladesh market data says so |
| ABIs | arm64-v8a, armeabi-v7a (x86_64 for emulators in debug only) | the App Bundle splits per ABI, so users download one copy of OpenCV and Tesseract |
| build | Gradle version catalogs, convention plugins in `build-logic/`, Kotlin DSL | keeps 15+ modules consistent |
| quality | ktlint + detekt, Android Lint (fatal on release), Compose compiler metrics | |

No backend, no analytics SDK, and no ad SDK in v1. Crash reporting, if added, is opt-in.

## 3. Component structure

### 3.1 Gradle modules

```
:app                              Application, MainActivity, NavHost, Hilt root, WorkManager config
:build-logic                      convention plugins (android-library, compose, hilt, room, test)

:core:model                       pure Kotlin. Document, Page, Folder, Quad, Filter, Recipe, OcrResult, ids (UuidV7), OrderKey
:core:common                      Result/Outcome, dispatchers (@IoDispatcher @DefaultDispatcher @ImagingDispatcher), clock, logging
:core:designsystem                ScannerTheme (tokens from design/), primitives from design/components.md, DesignCatalog previews
:core:ui                          shared composables that know the domain (DocumentTile, PageThumbnail, EmptyState wiring)
:core:security                    KeyManager (Keystore + Tink keysets), DbPassphraseProvider, AppLockManager, Crypto-shred
:core:database                    Room DB, entities, DAOs, migrations, FTS, SQLCipher factory, OutboxWriter
:core:storage                     BlobStore (encrypted, content-addressed, atomic), ExportCache, StorageMonitor (StatFs)
:core:imaging                     OpenCV wrapper: EdgeDetector, QuadTracker, PerspectiveWarper, FilterEngine, ImageCodec, ExifSanitizer
:core:camera                      CameraController (CameraX binding), FrameAnalyzer → EdgeDetector, CaptureService, Torch/Flash
:core:ocr                         OcrEngine interface, TesseractOcrEngine, TessDataInstaller, ScriptDetector, TextNormalizer
:core:pdf                         PdfWriter interface, PlatformPdfWriter (PdfDocument), PdfBoxWriter (text layer, password), PdfImporter
:core:data                        repositories (single writers), use cases, mappers. Depends on database/storage/imaging/ocr/pdf
:core:work                        Workers (Render, Ocr, Export, TrashPurge, BlobGc, ExportCleanup, RecipeMigration, Sync stub)
:core:sync                        SyncEngine interface + NoOpSyncEngine, SyncState models (real impl later)
:core:testing                     fakes (FakeBlobStore, FakeOcrEngine, FakeClock), test image corpus loader

:feature:onboarding    S01
:feature:home          S03
:feature:library       S04  (+ folder picker sheet, move, trash)
:feature:tools         S05
:feature:camera        S07, S07a, S22 mode
:feature:import        S08
:feature:editor        S09 crop, S10 enhance, S11 batch review, S13 page editor, S14 manage pages
:feature:document      S12 viewer, S21 print
:feature:ocr           S15
:feature:export        S16 share/export sheet
:feature:search        S18
:feature:settings      S19
:feature:lock          S20
```

Dependency rules (checked by a convention-plugin task):
- `:feature:*` may depend on `:core:*` but **never on another feature**. Cross-feature navigation goes through
  route objects defined in `:app` (or a tiny `:core:navigation` if it grows).
- Only `:core:data` depends on `:core:database`, `:core:storage`, `:core:imaging`, `:core:ocr`, `:core:pdf`.
  Features talk to use cases and repositories, plus `:core:camera` (camera is UI-bound by nature).
- `:core:model` has no Android dependencies.

### 3.2 Layers inside a feature (MVVM + unidirectional data flow)

```
Composable screen  ──events──▶  ViewModel  ──calls──▶  UseCase  ──▶  Repository  ──▶  DAO / BlobStore / engines
      ▲                            │
      └──── StateFlow<UiState> ────┘   one-off effects: Channel<Effect> (navigate, snackbar, share intent)
```

- `UiState` is an immutable data class per screen (sealed for loading/content/empty/error).
- ViewModels hold no Android `Context`. They survive configuration changes, and `SavedStateHandle` keeps the ids.
- Long-running work the user can leave (OCR, big exports, re-renders) goes to WorkManager.
  The ViewModel observes progress through Room flows or `WorkInfo`. It does not own the work.

### 3.3 Package layout of a feature (example `:feature:editor`)

```
editor/
  crop/      CropScreen.kt, CropViewModel.kt, CropUiState.kt, CropCanvas.kt (uses designsystem handles/loupe)
  enhance/   EnhanceScreen.kt, EnhanceViewModel.kt, FilterPreviewRenderer.kt
  review/    BatchReviewScreen.kt, BatchReviewViewModel.kt
  navigation/EditorRoutes.kt, EditorNavGraph.kt
```

## 4. The core pipeline: capture → detect → warp → enhance → save

```
 CameraX Preview ───────────────────────────────▶ PreviewView (S07)
 CameraX ImageAnalysis 640×480 YUV_420_888 (KEEP_ONLY_LATEST)
     └▶ FrameAnalyzer (ImagingDispatcher, 1 thread)
          Y-plane → gray Mat (zero-copy wrap) → EdgeDetector.detect() → Quad?
          → QuadTracker (smoothing, stability, sharpness) → StateFlow<EdgeState> → EdgeOverlay
          → when Locked ≥ 600 ms && autoCapture → CaptureService.capture()
 CameraX ImageCapture (MAXIMIZE_QUALITY, highest 4:3 resolution, JPEG)
     └▶ CaptureService
          JPEG bytes → ExifSanitizer (keep orientation, drop GPS/maker notes)
          → BlobStore.put(original)  (encrypt + atomic rename, returns sha256)
          → quad from the analysis frame, mapped to capture space, then refined:
            EdgeDetector.detect() again on a 1024 px downscale of the full image
          → PageRepository.addToSession(docId, original, quad)   (DB txn + outbox)
 Render (on-demand for the visible page, RenderPageWorker for the rest)
     original → decode (inSampleSize if > target) → PerspectiveWarper → rotate
     → FilterEngine.apply(filter, adjustments) → JPEG encode → BlobStore.put(processed)
     → thumbnail 512 px WebP → BlobStore.put(thumb) → PageRepository.markRendered(pageId, recipeVersion)
```

### 4.1 Edge detection (OpenCV, classical, in `:core:imaging`)

1. Downscale to a 480 px long edge. Gaussian blur 5×5.
2. Two candidate maps, merged: Canny (auto thresholds from the median) **and** a morphological gradient
   on the saturation channel (this catches white paper on a light desk, the classic failure).
3. Dilate 3×3, `findContours(RETR_LIST)`, `approxPolyDP` at 2% of the perimeter, keep convex 4-gons.
4. Score: area (≥ 15% of frame), rectangularity, angle sanity (each corner 45°–135°),
   distance from the previous quad (temporal coherence). The best scorer wins.
5. If no quad is found, return `null`. The UI shows "searching" and the crop screen falls back to the full frame.

`QuadTracker`: exponential smoothing of the corners (α 0.5), plus **locked** when, over 600 ms, every corner moves
< 1.5% of the diagonal **and** the variance of the Laplacian (sharpness) is above a device-calibrated threshold
**and** the camera is not in AF scanning. In batch mode, after a capture it requires the quad to move
> 10% or disappear before re-arming, so the same page is not captured twice.

Upgrade path (not v1): an on-device segmentation model behind the same `EdgeDetector` interface,
if the corpus test (§10) shows classical detection under 90% IoU ≥ 0.9.

### 4.2 Perspective warp and resolution policy

- Output size from the quad: width = max(top, bottom) edge, height = max(left, right) edge, in source pixels.
- The output long edge is clamped by the **quality profile** (set in Settings and per export):
  | profile | long edge | JPEG q | intent |
  | --- | --- | --- | --- |
  | Small | 2000 px | 75 | email, chat |
  | Balanced (default) | 3508 px (A4 @ 300 dpi) | 85 | everyday |
  | Original | min(source, 6000) px | 92 | archival |
- `warpPerspective` with `INTER_CUBIC` when downscaling < 2×, `INTER_AREA` otherwise.
- The **stored processed image is Balanced**. Exports at other profiles re-render from the original.

### 4.3 Filters (`FilterEngine`)

| filter id | UI name | method (summary) |
| --- | --- | --- |
| `original` | Original | warp only |
| `auto` | Auto | measure colourfulness: below a threshold use `gray`, otherwise `clean` |
| `clean` | Clean | illumination flattening (estimate the background with a large median/close on a downscaled L channel, upsample, divide), percentile contrast stretch (1–99%), unsharp mask, mild chroma boost |
| `gray` | Gray | flatten + stretch on L only |
| `bw` | B&W | flatten, then Sauvola-style local threshold (integral images, window ≈ 1/30 of the width), despeckle |
| `bw_soft` | B&W Soft | larger window, gentler k, no despeckle (pencil, handwriting) |

Brightness and contrast are applied last as a linear transform. The **preview** renders the
recipe on a 1600 px proxy (target < 150 ms on a mid-range phone). Full resolution renders only at save
or export. Filter names are our own ("Clean", not the original's term).

### 4.4 Memory and threading

- `@ImagingDispatcher` = `Dispatchers.Default.limitedParallelism(n)`, with n = 1 on low-RAM devices
  (`ActivityManager.isLowRamDevice` or `memoryClass < 256`) and 2 otherwise.
- Every `Mat` is used inside `use { }` (a `Closeable` wrapper that calls `release()`). Lint rule: no bare `Mat()` outside `:core:imaging`.
- Never decode the original at full resolution if the target is smaller (`inSampleSize` first, then exact warp).
- Batch saves render pages **sequentially**. A 50-page scan never holds more than 2 full-resolution images.
- Before capture or import, `StorageMonitor` checks free space (≥ 200 MB). Otherwise a warning banner shows and capture is blocked at 50 MB.

## 5. OCR: English + Bengali (Tesseract)

- **Engine:** `TesseractOcrEngine` wraps Tesseract4Android's `TessBaseAPI`. Instances are **not thread-safe**,
  so there is one instance per worker run, `init(tessdataDir, langs)` → `setImage` → `getUTF8Text` / result iterator → `recycle()`.
- **Languages:** `eng`, `ben`, or `eng+ben` (mixed documents are common in Bangladesh: Bengali body,
  English numbers and names). Default = `eng+ben` when the app locale is `bn`, otherwise `eng`. Per page, S15 can re-run in another mode.
- **Models:** `eng.traineddata` and `ben.traineddata` ship **inside the app** (install-time asset pack),
  so OCR works offline from the first launch. They are copied once to `filesDir/tessdata` by `TessDataInstaller`
  (checksum-verified, versioned). The choice of `tessdata_best` (accuracy) vs `tessdata_fast` (speed, size)
  is made in spike S3 by measuring CER and seconds per page on the test corpus.
- **Input:** the processed page, converted to gray at ~300 dpi equivalent. **Not** the B&W filter output
  (Tesseract does its own binarisation better). Page segmentation mode `AUTO` (3). A single-block mode is offered for receipts later.
- **Output:** NFC-normalised UTF-8 text, mean confidence, and word boxes (`ResultIterator` at `RIL_WORD`)
  saved as an encrypted JSON blob (`page_ocr.words_path`) for search highlights and the PDF text layer.
- **Script detection:** count Bengali (U+0980–U+09FF) vs Latin letters in the result → `detected_script`.
  This drives the line height in S15 (`design/compose-theme.md`).
- **Scheduling:** `OcrWorker`, unique work per `pageId` (REPLACE when the recipe changes), constraints
  `storageNotLow`, plus `batteryNotLow` for automatic OCR. User-initiated OCR is **expedited**.
  One page at a time. Progress goes to `documents.ocr_state` and `page_ocr.state`.
- **Auto-OCR is off by default** (a setting). The recon found users annoyed by forced text recognition.
- **Search:** FTS4 `unicode61` with `remove_diacritics=0`, so Bengali vowel signs survive tokenisation.
  Queries are NFC-normalised with ZWJ/ZWNJ (U+200D/U+200C) stripped on both the index and the query side.
  Verified: `schema.sql` loads, and Bengali and English terms both match.

## 6. PDF and JPG export

```
ExportRequest(docId, format = PDF|JPG, pages = all|range, profile, pageSize = Auto|A4|Letter,
              textLayer: Boolean, password: CharArray?)
   └▶ ExportWorker (expedited + foreground notification when > 10 pages)
        for page in pages (sequential):
            render(page, profile)         // from original + recipe, never upscaled
            writer.addPage(bitmap|jpeg, pageSize, words?)
        writer.finish() → cacheDir/exports/<title>.pdf | <title>-01.jpg, -02.jpg… (multi-page JPG is shared as multiple URIs, not a ZIP)
   → FileProvider URI → ACTION_SEND / ACTION_SEND_MULTIPLE, or SAF CreateDocument copy
```

- **PlatformPdfWriter** (`PdfDocument`): one `PdfDocument.Page` per scan page. The page box is the paper size
  (A4 595×842 pt, Letter 612×792 pt) or the image aspect for Auto. The bitmap is drawn fitted, with no margins by default.
  Used for every PDF without a text layer or password.
- **PdfBoxWriter** (PdfBox-Android): embeds the JPEG stream as-is (`JPEGFactory`, so no re-encode and smaller files),
  adds the OCR words as **invisible text** (render mode 3) positioned from the word boxes, and applies
  AES-256 standard security when a password is set. Its font for the text layer is Noto Sans Bengali / Inter
  subset, so Bengali text is extractable and copyable from the PDF.
- **Spike S2** measures file size and speed of both writers on a 20-page scan. If PdfDocument files are
  much larger (bitmaps re-encoded with lossless compression), PdfBoxWriter becomes the default writer
  and PdfDocument stays as a fallback.
- **JPG export:** the processed JPEG at the chosen profile, EXIF stripped, named `Title-01.jpg`, and so on.
- **No watermark, ever.** No branding is added to user documents.
- **Print (S21):** `PrintManager` + `PrintDocumentAdapter` that streams the PDF from the export cache.
- Export files are plaintext by necessity. They live in `cacheDir/exports`, are deleted after the share
  intent returns (best effort) and by `ExportCleanupWorker` after 1 h and on every cold start.

## 7. Storage and security

```
filesDir/
  blobs/ab/abcdef…sha256.bin      originals, processed, thumbnails, word-box JSON. Tink StreamingAead,
                                   associated data = sha256 (a blob can't be swapped for another)
  tessdata/eng.traineddata, ben.traineddata   (public models, not encrypted)
  datastore/settings.pb            Proto DataStore
  datastore/lock.pb.enc            app-lock PIN hash + salt (Tink Aead)
databases/scanner.db               SQLCipher (whole file encrypted, including FTS)
cacheDir/exports/                  plaintext exports, short-lived
cacheDir/coil/                     Coil disk cache: thumbnails stored **encrypted** (custom DiskCache wrapper) or disabled (decide in M1)
```

- **Keys:** a Keystore AES-256-GCM master key (`android-keystore://scanner_master`) wraps two Tink keysets:
  `blob_keyset` (StreamingAead) and `meta_keyset` (Aead, for the DB passphrase and the lock file).
  The keys are **not** user-auth-bound, so background OCR and exports work while the app is locked.
  The app lock is a UI gate, which is the right trade-off for this product. "Strong lock" (auth-bound keys) is a later option.
- **DB passphrase:** 32 random bytes, generated on first run, stored encrypted with `meta_keyset`. Passed as bytes to
  SQLCipher. Never a String in memory longer than needed.
- **Atomic writes:** blob → `tmp/` → fsync → rename into place → **then** the DB transaction that references it.
  A crash leaves at most an orphan blob, which `BlobGcWorker` collects (unreferenced and older than 1 h).
- **App lock (S20):** a 6-digit PIN hashed with PBKDF2-HMAC-SHA256 (310k iterations, random salt) + BiometricPrompt
  (BIOMETRIC_STRONG or DEVICE_CREDENTIAL). Re-lock on background after a timeout. 5 failures = 30 s cooldown.
- **Screen privacy:** setting "Hide previews in recents" → `FLAG_SECURE` on locked docs, and
  `setRecentsScreenshotEnabled(false)` on API 33+.
- **Backups:** `android:allowBackup="false"` plus `dataExtractionRules` excluding everything. Encrypted blobs are useless
  without the Keystore key, which never leaves the device. So the app offers **"Export all"** (a ZIP of PDFs) as the
  user-controlled backup until cloud sync ships. This is said plainly in Settings → Storage.
- **Delete all data (GDPR-style):** delete the Keystore key and keysets first (crypto-shredding: every blob and the DB
  become unreadable instantly), then delete the files.
- **Permissions:** CAMERA only. No storage permission (Photo Picker + SAF), no location, no internet in v1
  (the manifest omits `INTERNET` until sync, which is a strong, checkable privacy claim for the store listing).

## 8. Offline-first and future cloud sync

Everything sync needs is in the v1 schema, so sync ships later **without a data migration**:

| need | v1 provision |
| --- | --- |
| stable global ids | UUID v7 generated on device. The server uses the same ids |
| ownership | `owner_id = 'local'`, rewritten to the account id at first sign-in (one transaction) |
| change tracking | `sync_outbox` written in the same transaction as every change (transactional outbox) |
| deletes | soft delete (`deleted_at`) tombstones, kept ≥ 30 days |
| concurrency | `version` (local counter) + `server_rev` (last seen). Conflicts are marked `sync_state = 'conflict'` |
| ordering | fractional `order_key` for pages, so concurrent reorders merge without renumbering |
| files | content-addressed blobs (sha256): upload once, dedupe, and verify integrity for free. Originals are immutable |
| derived data | processed images, thumbnails and OCR are rebuildable, so they sync optionally (save bandwidth: sync original + recipe) |
| privacy | blobs are already encrypted client-side. An E2E-encrypted sync (server stores ciphertext) is possible by sharing `blob_keyset` through a user passphrase |

Planned sync design (not built): `SyncEngine` (in `:core:sync`) with push (drain the outbox in `seq` order,
idempotent on `(entity, entity_id, entity_version)`), pull (changes since a cursor), and a merge policy of
last-writer-wins **per field** for metadata, union for tags, fractional keys for order, and immutable blobs.
`SyncWorker` runs periodically (network constraint) and is kicked on change. v1 binds `NoOpSyncEngine`.
The backend choice (Supabase/Postgres + object storage, or Firebase) is deferred. The schema maps 1:1 onto Postgres.

## 9. Schema

Full DDL: [`schema.sql`](schema.sql). 7 tables + 1 FTS index:

| table | purpose | key constraints |
| --- | --- | --- |
| `folders` | folder tree | unique live name per parent (NOCASE), no self-parent, cycles blocked in the repository |
| `documents` | a scanned document (or an unsaved draft) | status `draft/ready`, `ocr_state` enum, denormalised `page_count`, `total_bytes` |
| `pages` | immutable original + edit recipe + derived renders | unique live `(document_id, order_key)`, enums for filter/rotation/source, `recipe_version` |
| `page_ocr` | OCR text, confidence, word-box blob, engine version | stale if `recipe_version` ≠ the page's |
| `page_ocr_fts` | FTS4 over OCR text | unicode61, `remove_diacritics=0` |
| `tags`, `document_tags` | tagging (could, included now) | unique live tag name |
| `sync_outbox` | ordered change log | written in the same transaction as each change |

Settings, keys and the lock secret are outside the DB on purpose (see the end of `schema.sql`).
Access rule: a single local user, so there is no row-level security. Repositories are the only writers, and every read
filters `deleted_at IS NULL` (except trash and sync queries). When accounts exist, every DAO query gains `owner_id = :owner`.

Room specifics: `exportSchema = true` (JSON under `core/database/schemas/` and committed), a migration test
for every version bump, `@TypeConverter`s for `Quad` (JSON) and enums (stable string ids, never ordinals).

## 10. Internal API (use cases)

There is no HTTP API in v1. This is the contract between the UI and the data layer: one use case per action,
grouped by recon flow. "Caller" = screen.

**F01 Scan a single page / F02 Batch scan / F03 Add pages**

| use case | does | caller | input | output |
| --- | --- | --- | --- | --- |
| `StartScanSession` | creates a `draft` document (or reopens the unsaved draft) | S07 | mode, targetDocId? | docId |
| `ObserveEdgeState` | live detection state from the analyzer | S07 | — | Flow<EdgeState(searching/detected/locked, quad)> |
| `CapturePage` | stores the original, refines the quad, appends the page to the draft | S07 | docId, ImageProxy/JPEG, analysisQuad | PageId |
| `ImportImages` | Photo Picker URIs → originals + auto-detected quads | S08 | docId?, List<Uri> | List<PageId> |
| `AdjustQuad` | saves a manual quad (or full frame / re-detect) | S09 | pageId, Quad? , source | Unit |
| `RotatePage` | rotation ±90 | S09 S13 | pageId, delta | Unit |
| `PreviewFilter` | renders a proxy preview (no DB write) | S10 | pageId, filter, adjustments | ImageBitmap |
| `ApplyFilter` | sets the filter/adjustments on one or all pages | S10 | pageIds, filter, adj | Unit |
| `ReorderPage` | moves a page (one row: a new `order_key` between neighbours) | S11 S14 | pageId, beforeId?, afterId? | Unit |
| `DeletePage` / `RetakePage` | soft-deletes a page, or replaces its original and keeps its position | S11 S13 | pageId | Unit |
| `SaveScanSession` | draft → ready, auto-title, renders the remaining pages, optional auto-OCR | S10 S11 | docId, title? | docId |
| `DiscardScanSession` | deletes the draft and its blobs | S07 S11 | docId | Unit |
| `AddPagesToDocument` | opens a session targeting an existing doc | S12 | docId | sessionId |

**Library, document, export**

| use case | does | caller | input | output |
| --- | --- | --- | --- | --- |
| `ObserveRecentDocuments` | last N opened/edited | S03 | limit | Flow<List<DocumentSummary>> |
| `ObserveFolderContents` | folders + docs, sorted | S04 | folderId?, sort, filter | Flow<FolderContents> |
| `ObserveDocument` | doc + pages + render/OCR state | S12 S14 | docId | Flow<DocumentDetail> |
| `RenameDocument` | validated rename | S12 S11 | docId, title | Unit |
| `CreateFolder` / `RenameFolder` | folder tree ops | S04 | parentId?, name | FolderId |
| `MoveItems` | atomic move of docs/folders, with cycle check | S04 | ids, targetFolderId? | Unit |
| `TrashItems` / `RestoreItems` | soft delete / undo | S04 S12 | ids | Unit |
| `EmptyTrash` | hard purge now | S19 | — | Unit |
| `ExportDocument` | enqueues ExportWorker | S16 | ExportRequest | Flow<ExportProgress → ExportResult(uri)> |
| `PrintDocument` | export + PrintManager | S21 | docId | Unit |
| `SetDocumentLocked` | lock/unlock flag (requires auth) | S12 S20 | docId, locked | Unit |

**OCR, search, settings, security**

| use case | does | caller | input | output |
| --- | --- | --- | --- | --- |
| `RecognizeText` | enqueues OCR for pages (expedited) | S10 S12 S15 | pageIds, langs | Unit |
| `ObservePageText` | OCR result per page | S15 | docId | Flow<List<PageText>> |
| `SearchDocuments` | title prefix + FTS with snippets and page numbers | S18 | query | Flow<SearchResults> |
| `ObserveSettings` / `UpdateSettings` | typed settings | S19, all | partial update | Flow<Settings> |
| `SetAppLock` / `Unlock` | set/verify PIN, biometrics | S19 S20 | pin / BiometricResult | Outcome |
| `ExportAllData` | ZIP of all docs as PDF (backup) | S19 | target Uri | progress |
| `DeleteAllData` | crypto-shred + wipe | S19 | confirmation | Unit |

**Navigation routes** (type-safe, in `:app`):
`Home`, `Files(folderId?)`, `Tools`, `Settings`, `Camera(mode, targetDocId?)`, `Crop(docId, pageId)`,
`Enhance(docId, pageIds)`, `BatchReview(docId)`, `Document(docId, page?)`, `PageEditor(docId, pageId)`,
`ManagePages(docId)`, `OcrText(docId, page?)`, `Export(docId)` (bottom sheet), `Search(query?)`, `Lock(next)`,
`Onboarding`. That is 16 routes.

**Background jobs (WorkManager)**

| worker | trigger / schedule | constraints | does |
| --- | --- | --- | --- |
| `RenderPageWorker` | after save, on recipe change | — | renders processed + thumb for pages with `render_state <> 'done'`. Idempotent by `(pageId, recipe_version)` |
| `OcrWorker` | user action (expedited) or auto-OCR | storageNotLow (+ batteryNotLow if auto) | one page per run, writes `page_ocr` + FTS. Skips if stale |
| `ExportWorker` | user action | expedited, foreground notification for > 10 pages (`mediaProcessing` type on API 35+, `dataSync` below) | builds PDF/JPG into the export cache |
| `TrashPurgeWorker` | daily | idle | hard-deletes items with `deleted_at` older than 30 days (cascade), then enqueues BlobGc |
| `BlobGcWorker` | daily + after purge | idle, charging | deletes blobs not referenced by any row and older than 1 h |
| `ExportCleanupWorker` | cold start + 6-hourly | — | clears `cacheDir/exports` older than 1 h |
| `RecipeMigrationWorker` | app update when `RECIPE_VERSION` bumps | idle, charging | re-renders stale pages lazily (visible docs first) |
| `SyncWorker` | (future) periodic 1 h + on change | network | drains the outbox / pulls. Bound to NoOp in v1 |

There are no webhooks (no server). The only official external APIs are the Android platform APIs. Cloud uploads in v1
go through the system share sheet, so no third-party SDKs or keys are needed.

## 11. The parts that bite

- **Time zones:** stored as UTC epoch ms. Auto-titles ("Scan 19 Nov 2026 18:05") and the "Today/Mon" labels format in the
  device zone at display time. Titles are generated once, so a zone change doesn't rename docs.
- **Process death mid-scan:** the draft document and pages are committed per capture. On restart, Home shows
  "Continue your unsaved scan?". Drafts older than 7 days are offered for discard, never silently deleted.
- **Races:** double-tap shutter (CaptureService is single-flight: a second call while busy is ignored).
  The user edits the recipe while a render runs (the render writes only if `recipe_version` and `version` still match, otherwise it re-queues).
  OCR finishes after a crop change (stale → discarded, re-queued).
- **Idempotency:** every Worker is unique work keyed by entity id, and writes are upserts keyed by
  `(pageId, recipe_version)`, so retries are harmless. Future sync pushes are deduped server-side on entity version.
- **Memory / OOM:** 50 MP sensors produce 8160×6144 images. Never decode full size unless the profile needs it. Release Mats.
  Sequential batch rendering. Leak checks with LeakCanary in debug.
- **Large documents:** PDF writers stream page by page. Coil decodes thumbnails only. The viewer (S12) uses a lazy
  list with region decoding for zoom > 2×.
- **Storage full:** checked before capture, import and export, with a banner instead of a crash. Failed blob writes roll back the DB txn.
- **16 KB memory pages:** Play requires apps that target Android 15+ to support 16 KB page sizes on 64-bit devices.
  OpenCV fixed this in its 4.12.0 release. **Tesseract4Android's prebuilt `.so` alignment is unverified**, so spike S1 checks
  it (`zipalign -c -P 16` / the APK Analyzer) and falls back to building it from source with NDK r28+ (16 KB by default).
- **APK size:** the full OpenCV AAR is large. Measure in M0. If the per-ABI download is > 25 MB, build a slim OpenCV
  (core, imgproc, imgcodecs only). The Bengali + English models add 5–30 MB depending on best vs fast (spike S3).
- **Camera fragmentation:** some devices can't bind Preview + Analysis + max-res Capture together, so query
  `CameraInfo` and step the capture resolution down. Test on a matrix: one low-end (2–3 GB RAM), one mid, one Samsung, one Xiaomi.
- **Bengali text:** NFC normalisation, ZWJ/ZWNJ handling, and taller line heights (design tokens). Tesseract's Bengali accuracy on
  low-quality prints is the main quality risk, so measure CER, do not assume it.
- **Search:** FTS4 is fine to roughly 100k pages. Title search is a prefix `LIKE` on an indexed NOCASE column.
- **Key loss:** if the Keystore key is invalidated (rare OEM bugs, factory reset), the data is gone. That is why "Export all" exists
  and why the app says so. Detect `KeyPermanentlyInvalidatedException` and show a recovery screen, never a crash loop.
- **Privacy:** the GPS EXIF is stripped at capture. No INTERNET permission in v1.
- **Accessibility:** the camera hints are announced, the crop handles are keyboard and TalkBack adjustable (design spec), and font scale goes up to 200%.
- **Realtime, rate limits, email, multi-tenancy:** not applicable in v1.

## 12. Testing strategy

| level | what | where |
| --- | --- | --- |
| unit (JVM) | use cases with fakes, OrderKey, Quad math, title generation, search query normalisation, settings | `:core:data`, `:core:model` |
| imaging corpus (instrumented) | ~60 of your own photos (receipts, A4, books, Bengali prints, low light, white-on-white) with hand-labelled quads. **Gate: IoU ≥ 0.9 on ≥ 90% of images**, and the filter outputs keep golden-image SSIM ≥ 0.98 | `:core:imaging` androidTest |
| OCR accuracy | 20 English + 20 Bengali pages with ground truth. Track CER per release. Gate: no regression > 1 pt | `:core:ocr` androidTest |
| database | Room migration tests for every version, FTS Bengali matching, constraint tests (duplicate order, folder names) | `:core:database` |
| crypto | BlobStore round-trip, tamper detection (flip a byte → failure), crypto-shred | `:core:storage`, `:core:security` |
| UI | Compose tests for F01/F02/F03 with a fake camera source feeding corpus images | features |
| performance | Macrobenchmark: cold start → camera preview **≤ 800 ms**, FAB → preview **≤ 300 ms** warm, save 10 pages **≤ 6 s** mid-range. Baseline Profiles shipped | `:benchmark` |

## 13. Implementation plan

Estimates are for one experienced Android developer. Each milestone ends with a working, installable build.

### M0 Foundation and spikes (week 1)
Project skeleton (modules, convention plugins, Hilt, version catalog, CI with lint + unit tests),
ScannerTheme from `design/tokens.json`, DesignCatalog previews of the primitives.
**Spikes (each ends in a written decision in `replica/decisions/`):**
- **S1 Native libs:** OpenCV 4.12+ and Tesseract4Android load on a 16 KB emulator. APK size per ABI.
- **S2 PDF writer:** PdfDocument vs PdfBox-Android, size and time for 20 pages, Bengali text layer copyable.
- **S3 OCR models:** tessdata_best vs fast for eng/ben, CER and seconds per page on 10 pages each.
- **S4 Detection:** the §4.1 algorithm on the corpus, IoU table. Decide whether ML segmentation is needed later.
- **S5 Encrypted Room:** SQLCipher + Room + FTS4 unicode61 on a device, and the open-time cost.
Exit: the decisions are recorded and the risks re-rated.

### M1 Vertical slice (weeks 2–3): the core loop, ugly but real
Screens **S03** (bare list + FAB), **S07** (preview + manual shutter + edge overlay), **S09** (auto quad + handles),
**S10** (Clean filter only), **S12** (page list), **S16** (PDF share only).
Tables: `documents`, `pages`, `sync_outbox`. BlobStore with encryption, SQLCipher, KeyManager.
Use cases: StartScanSession, CapturePage, AdjustQuad, ApplyFilter, SaveScanSession, ObserveRecentDocuments,
ObserveDocument, ExportDocument (PlatformPdfWriter).
Exit: on a real phone, FAB → capture → crop → Save → a PDF shared to Gmail opens correctly, and the data
directory contains no readable images.

### M2 Must-haves (weeks 4–5): `features.csv` priority = must (24 rows)
Capture: auto-capture + QuadTracker, flash, permission state S07a, single/batch ModeStrip, page counter,
gallery import **S08**. Detection: full-frame fallback, rotate. Enhance: Original/Clean/Gray/B&W. Multi-page:
**S11** review, retake/delete. Library: tiles with thumbnails, page count and date, auto-naming and rename. Drafts survive process death.
Workers: RenderPageWorker, ExportCleanupWorker. Coil encrypted fetcher.
Exit: every `must` row is `clone = yes`, run `/replica-test` on F01–F03, and corpus IoU passes the gate.

### M3 Should-haves (weeks 6–8): library, OCR, search, settings (22 rows)
Reorder S14 (fractional keys), add pages, apply-to-all, brightness/contrast, loupe, JPG export, quality profiles,
page size. **OCR**: TesseractOcrEngine, TessDataInstaller (eng + ben bundled), OcrWorker, **S15**, copy/share/TXT,
searchable PDF (PdfBoxWriter), auto-OCR setting (default off). **Search S18** (FTS, Bengali-safe). **Folders S04**
(tree, atomic moves, breadcrumb, two-pane on expanded), trash + undo, TrashPurge + BlobGc workers. **Settings S19**.
Print S21. No watermark (by construction).
Tables: + `page_ocr`, `page_ocr_fts`, `folders`.
Exit: every `should` row is done, OCR CER baseline recorded for eng and ben, and search finds Bengali words.

### M4 Could-haves (weeks 9–10, pick by value)
In suggested order: app lock **S20** + locked docs, tags (`tags`, `document_tags`), sort/grid-list, multi-select (move/delete/merge/share),
ID card mode **S22**, QR mode (ZXing), password-protected PDF, import PDF (PdfRenderer → pages), merge/split,
onboarding **S01**, Export all (backup ZIP), e-signature S17 and annotation (these need an `annotations` table, migration v2),
Play Billing paywall **S02** (once pricing is decided). Word export is deferred (no lightweight offline DOCX writer that is worth it).
Exit: chosen rows done, and `/replica-diff` parity score recorded.

### M5 Hardening and release (week 11)
Device matrix test, Baseline Profiles, Macrobenchmark gates, accessibility pass (TalkBack through F01–F03, font 200%),
Bengali localisation review (`values-bn`), LeakCanary clean, R8 rules for OpenCV/Tesseract/PdfBox JNI, 16 KB check in CI,
crash-free soak test (100-page document, low storage, airplane mode). Then `/replica-brand` (name, icon), `/replica-launch`.

### Later: cloud sync (L)
Pick a backend → implement `SyncEngine` + `SyncWorker` → sign-in (rewrite `owner_id`) → blob upload by sha256 →
conflict UI for `sync_state = 'conflict'`. The schema needs no changes (§8).

## 14. Open decisions

| # | decision | decided by |
| --- | --- | --- |
| D1 | tessdata_best vs fast for eng/ben | spike S3 |
| D2 | default PDF writer (PdfDocument vs PdfBox) | spike S2 |
| D3 | slim OpenCV build needed? | spike S1 (APK size) |
| D4 | minSdk 26 vs 24 | Bangladesh device data before M5 |
| D5 | Coil disk cache: encrypted or memory-only | M1 performance check |
| D6 | classical detection good enough or add a segmentation model | spike S4 + M2 corpus gate |
| D7 | backend for sync | after launch, from user demand |
