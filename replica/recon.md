# Recon map: CamScanner (Android)

Target: CamScanner: scanner, PDF maker, `com.intsig.camscanner` (INTSIG)
Scope: the core scanning loop (capture, detect and crop, enhance, multi-page, PDF, export or share), plus the library, OCR and settings around it
For: an original Android document-scanner app, sold publicly
Date: 2026-11-19

> Clean-room recon. Everything here comes from public pages: the Play listing,
> App Store listing text, CamScanner's own blog and marketing pages, and
> third-party reviews and walkthroughs. No account was used, and no APK, code,
> network traffic or private API was examined. `replica/screens/` is empty
> because no screenshots have been captured yet (see "Next evidence to gather").
> Before using your own CamScanner account for recon, read its Terms of Use
> (camscanner.com/app/service) for any clause against building a competing product.

## Evidence key

Each item is tagged with one of these:

- **[V]**: Verified. A public source states it (source # in brackets, e.g. `[V:1]`).
- **[I]**: Inferred. Standard scanner-app UX or implied by a verified feature, but no source read here confirms it for CamScanner Android.
- **[G]**: Guess. A structural assumption made for planning. Confirm before building.

## Sources

| # | source | URL | notes |
| --- | --- | --- | --- |
| 1 | Google Play listing | https://play.google.com/store/apps/details?id=com.intsig.camscanner | Feature list, 500M+ installs, 4.6★, ads + IAP, subscriptions billed weekly/monthly/quarterly/annually, updated Oct 22 2026. What's new: Word editing, account switching |
| 2 | App Store listing (US, current) | https://apps.apple.com/app/388627783 | Lists ID Card & Passport mode, QR code, multi-page, OCR, export to PDF/JPG/TXT, cloud upload, print. Also the free-tier watermark (from reviews) |
| 3 | App Store listing (older, regional) | https://apps.apple.com/ve/app/camscanner-pdf-scanner-app/id388627783?l=en-GB | Premium list: cloud auto-upload, PDF import, ID scan. Shows past pricing of 4.99/mo and 49.99/yr |
| 4 | App Store, v7.0 notes | https://apps.apple.com/app/camscanner-free-pdf-document/id388627783 | "CS AI" section in v7.0. Reviews mention the watermark at the bottom right |
| 5 | CamScanner blog: ID scanning | https://blog.camscanner.com/?p=561 | ID Card Mode captures front and back. Tags, search, sync, protection |
| 6 | CamScanner marketing (v3) | https://v3.camscanner.com | Auto edge cropping, background removal, high-res JPEG/PDF |
| 7 | CamScanner developer API page | https://dev.camscanner.com/ | Edge detection + auto crop. "Five image enhance modes" |
| 8 | MakeUseOf walkthrough | https://www.makeuseof.com/tag/scanr/ | Names 7 filters (older version). OCR started from the filter screen |
| 9 | PDNob how-to | https://www.pdnob.com/pdf-tips/how-to-make-pdf-file-in-camscanner.html | Single vs Batch, crop-corner handles then checkmark, Magic Color/Grayscale/B&W, watermark on free tier |
| 10 | UPDF how-to | https://updf.com/create-pdf/how-to-make-pdf-file-in-camscanner/ | Camera icon at bottom, "Scan" mode, Single/Batch, "Complete" builds multi-page PDF, ID Cards option |
| 11 | CamScanner iPhone manual v3.5 (old) | https://www.slideshare.net/slideshow/camscanner-iphone-manual-english/65076446 | Swipe between single and batch, flash, zoom slider, Shared with Me, Settings (dated but structural) |
| 12 | screensdesign walkthrough index | https://screensdesign.com/apps/camscanner-pdf-scanner-app/?av=4256&id=580&vt=1 | Captions only: onboarding screens, paywall, gallery import with Recents dropdown, editor with filter carousel and "Magic" selected, AI chat, AI Erase |
| 13 | Play/App Store reviews (via 1, 2, Capterra, GetApp) | https://www.getapp.com/all-software/a/camscanner/ | Intrusive ads, watermark, 3-day trial converting to annual. Your clone's opening |
| 14 | Bottom-nav references | Play reply text ("CS app → Me → Feedback"), App Store reply ("bottom ME icon → more settings") | Confirms a bottom nav with a **Me** tab |
| 15 | Third-party usage blog | https://bestappsforandroid.com/boost-productivity-with-these-android-cam-scanner-hacks/ | Auto-capture fires when the page is square in frame. Default mode can be set. Corner handles drag to fix |
| 16 | UPDF CamScanner Android comparison | https://updf.com/comparison/camscanner-android/ | Fixes tilted pages, filters, basic PDF tools |
| 17 | AndroidAyuda review | https://en.androidayuda.com/applications/recommended/camscanner/ | Output quality is adjustable, PDF or JPG |

## Core loop

Point the camera at paper. The app finds the page, flattens and cleans it, and you get a crisp multi-page PDF to share in a few taps, with no watermark and no ads. That last part is what CamScanner charges for [V:1,2,9,13].

---

## 1. Screen inventory

| ID | screen | how to reach | purpose | key components | states seen / expected | evidence |
| --- | --- | --- | --- | --- | --- | --- |
| S01 | Onboarding carousel | first launch | pitch, social proof, sample doc | pager, Continue/Skip, sample doc card | first-run only | [V:12] |
| S02 | Paywall / trial offer | after onboarding, gated features | sell premium | plan cards (week/month/quarter/year), trial toggle, close (X) | default, purchasing, error, restore | [V:1,12,13] |
| S03 | Home (recent docs + shortcuts) | app launch, nav tab | recent docs, tool shortcuts, scan FAB | top search, tool shortcut grid, doc list, centre camera FAB | empty, filled, syncing, ad slot | [V:12 "home screen offers shortcuts"] [I: layout] |
| S04 | Files / folder browser | nav tab | browse all docs and folders | breadcrumb, sort, grid/list toggle, folder rows, multi-select | empty, filled, selection mode | [V:1 tagging, 5 organise] [I: separate tab] |
| S05 | Tools / "All tools" hub | nav tab or Home "All" | catalogue of tools (PDF tools, convert, AI) | categorised tool grid | default | [V:12 "All" section with many tools] |
| S06 | Me / account | nav tab "Me" | account, premium status, settings entry, feedback | avatar, plan badge, storage meter, settings rows | signed out, signed in, premium | [V:14] |
| S07 | Camera capture | camera FAB | capture pages | viewfinder, live edge overlay, mode strip (Single/Batch/ID Card/QR/...), shutter, flash, gallery import, auto-capture toggle, page-count thumbnail | permission denied, detecting, locked, capturing, low light | [V:2,9,10,11,15] |
| S08 | Gallery import picker | S07 gallery button, or Home "Import images" | turn photos into a scan | album dropdown ("Recents"), multi-select grid, Import button | no permission, empty album, selected n | [V:12] |
| S09 | Crop / adjust borders | after capture (single) or per page | fix the detected quad | image, 4 corner handles + edge midpoints, magnifier loupe, "Auto/All" reset, rotate, next/✓ | detected, no edge found (full frame), manual | [V:9,15 handles] [I: loupe, midpoints] |
| S10 | Enhance / filter | after crop | pick enhancement | preview, filter carousel (Original, Magic Color, Grayscale, B&W, Lighten...), brightness/contrast sliders, apply-to-all, OCR shortcut, ✓ | processing, applied | [V:8,9,12] |
| S11 | Batch review | after batch capture | review all captured pages before saving | thumbnail strip/grid, reorder, retake, delete, crop/filter per page, Complete | 1..n pages | [V:10,11] [I: review grid] |
| S12 | Document view (pages) | open a doc | see and act on a document | page grid/pager, title (rename), bottom bar: Share, Add page, Edit, OCR/To text, More | 1 page, many pages, OCR running | [V:1,2] [I: layout] |
| S13 | Page editor | tap page in S12 | edit one page | re-crop, filter, rotate, annotate, watermark, signature, AI erase | editing, saved | [V:1 annotate+watermark, 12 AI Erase] |
| S14 | Reorder / manage pages | S12 → Edit | arrange pages | drag grid, multi-select, delete, move | selection | [I] |
| S15 | OCR result | S10/S12 "Recognise text" | read, edit, copy extracted text | text editor, copy, export TXT/Word, language picker | running, result, no text, failed | [V:1,2,8] [I: lang picker] |
| S16 | Share / export sheet | S12 Share | choose format and target | format (PDF / JPG / Word / TXT), size/quality, password, Android share intent, link | free (watermark notice), premium | [V:1,2,9] |
| S17 | Signature pad + placement | S13 or Tools | create and place an e-signature | draw pad, saved signatures, drag/resize on page | empty, saved | [V:1 meta "e-signature"] [I: UI] |
| S18 | Search | Home/Files search bar | find docs by title, tag or OCR text | query field, results with snippets | empty, no results | [V:1 "tagging and search", "find images by content"] |
| S19 | Settings | Me → Settings | preferences | scan defaults, PDF defaults, storage/sync, security, about | default | [V:14 "more settings"] [I: contents] |
| S20 | Document password / lock | S16 or doc menu | protect docs and links | PIN/password entry | set, prompt, wrong | [V:1 "passwords for viewing a document and for its download link"] |
| S21 | Print | S12 → More | print via Android print service | system print dialog | — | [V:1 "wireless printing"] |
| S22 | ID card result | S07 ID mode | front + back composed on one page | two capture prompts, composed A4 preview | front done, back pending | [V:5,10] |
| S23 | CS AI chat | Tools/AI section | Q&A over a document | chat, suggested prompts | — | [V:4,12] **out of scope for core** |

Counts: 23 screens. Core-loop screens: S03, S07, S08, S09, S10, S11, S12, S16 (8).

## 2. Navigation structure

```
Bottom navigation  [V:14 "Me" tab] [I/G: other tab names]
├── Home         S03  recent docs + tool shortcuts
├── Files        S04  folders/tags
├── (●) Camera   S07  centre FAB, the primary action        [V:10,11 camera button at bottom]
├── Tools        S05  PDF tools, converters, AI
└── Me           S06  account, premium, Settings S19, Feedback

Modal stacks (full-screen, no bottom nav):
  Capture stack:  S07 → (S08) → S09 → S10 → (S11) → S12
  Doc stack:      S12 → S13 / S14 / S15 / S16 / S17 / S20 / S21
  Monetisation:   S02 overlays anything gated
```

Notes:
- Older docs [V:11] show single and batch switched by swiping on the shutter strip. Newer how-tos [V:9,10] show a mode strip with Single/Batch plus specialist modes (ID Card, QR). Assume a horizontal mode strip above the shutter. [I]
- The exact tab names besides "Me" are not confirmed (Home and a doc tab are very likely). Confirm with your own screenshots. [G]
- A third-party review [V:12] says the tool catalogue is overwhelming. **Clone opportunity:** a three-tab nav (Docs, Camera FAB, Settings) and no tool hub in v1.

## 3. Camera scanning workflow

```
F01 Scan a single page to PDF (core happy path)
    S03 tap camera FAB -> S07 (Single mode) -> auto-capture or shutter
    -> S09 confirm/adjust corners -> ✓ -> S10 pick filter (Magic default) -> ✓
    -> S12 doc saved, auto-named -> Share -> S16 PDF -> Android share sheet
    happy-path taps: 6 (FAB, shutter, ✓ crop, ✓ filter, Share, PDF)   [V:9,10,15]
    target for clone: 4 (FAB, auto-capture, ✓ review, Share)
    edge: camera permission denied; no document found (falls back to full frame);
          low light (flash prompt); blur/motion; very large or very small page;
          app backgrounded mid-capture; storage full
```

Camera capabilities:
| capability | evidence |
| --- | --- |
| Live edge overlay on the viewfinder | [V:6,7,15] |
| Auto-capture when the page is steady and square | [V:15] |
| Manual shutter | [V:9,10] |
| Flash modes (off/on/auto/torch) | [V:11] (flash mode button) |
| Zoom | [V:11] (old manual) |
| Mode strip: Single, Batch, ID Card, QR code | [V:2,9,10] |
| Other modes (Book, Whiteboard, Business card, Excel/Word conversion, Translate) | [I] (business cards and whiteboards are named as scan targets [V:1], not confirmed as modes) |
| Import from gallery | [V:12] |
| Grid lines, shutter sound, capture resolution | [I] |

## 4. Document detection and cropping

| behaviour | evidence |
| --- | --- |
| Automatic edge detection on capture | [V:1 "smart cropping", 6, 7] |
| Background removal (crop to the page quad) | [V:6,7] |
| Perspective correction (straightens tilted pages) | [V:16] |
| Manual adjustment by dragging corner handles | [V:9,15] |
| Magnifier loupe while dragging | [I] |
| Edge midpoint handles | [I] |
| "Full page / no crop" reset and "auto-detect again" | [I] |
| Rotate 90° on the crop screen | [I] |
| Auto-orientation (text upright) | [I] |
| Curved-page flattening (book) | [G] not evidenced |
| Applies to imported gallery images too | [I] (gallery import exists [V:12]; auto-crop on import is standard) |

Clone approach note (no code): on-device detection is the hard part. Options for
replica-architect: OpenCV contour + quad detection; Google ML Kit Document
Scanner API (gives detection, crop, filters and a ready UI, but less control over the UX);
or a small segmentation model. **Decide this first. It sets the quality ceiling.**

## 5. Image enhancement filters

| filter | evidence |
| --- | --- |
| Original (no processing) | [V:8] |
| Auto | [V:8] (older version) |
| Magic Color (the signature: whitens paper, saturates ink) | [V:8,9,12] |
| Lighten | [V:8] |
| Grayscale ("Gray Mode") | [V:8,9] |
| Black & White (binarised) | [V:8,9] |
| Black & White 2 (alternate threshold) | [V:8] |
| "Five image enhance modes" (API) | [V:7] |
| Brightness / contrast / detail sliders | [I] (named in third-party guides to scan apps generally) |
| Apply filter to all pages | [I] |
| Default filter setting | [V:15] ("switch the default mode") |
| AI Erase (remove marks/handwriting/stains) | [V:12] (premium, out of core) |
| Shadow / moiré removal | [G] |

Filter names: use your own names (e.g. "Clean", "Crisp B&W", "Soft Gray"). Do not
reuse "Magic Color". It is CamScanner's term.

## 6. Multi-page scanning

```
F02 Batch-scan a multi-page document
    S07 switch to Batch -> capture page 1..n (auto-capture keeps going)
    -> tap thumbnail / Complete -> S11 review grid (reorder, retake, delete,
       per-page crop/filter) -> Save -> S12
    happy-path taps: 3 + n (mode, n shutters or auto, Complete, Save)   [V:9,10,11]
    edge: retake page k; delete last; mixed orientations; 100+ pages (memory);
          crash mid-batch (draft must survive)

F03 Add pages to an existing document
    S12 Add page -> S07 (Batch) -> capture -> append at end -> S12
    edge: insert at position; import from gallery instead           [I]
```

| capability | evidence |
| --- | --- |
| Single vs Batch mode | [V:9,10,11] |
| Many pages into one document | [V:2] |
| Page counter thumbnail on the camera | [I] |
| Review all pages before saving | [I] (implied by "Complete" [V:10]) |
| Reorder pages (drag) | [I] |
| Delete / retake a page | [I] |
| Add pages to an existing doc | [I] |
| ID card: front and back composed on one page | [V:5,10] |

## 7. PDF creation and editing

| capability | evidence |
| --- | --- |
| Save as PDF (multi-page) | [V:1,2] |
| Save as JPG (per page) | [V:1,2] |
| Export to Word / TXT | [V:1,2] |
| PDF page size / margins / orientation setting | [I] |
| Export quality / file-size option | [I] (resolution is adjustable [V:17]) |
| "Scanned by CamScanner" watermark on free exports | [V:4,9,13] (do **not** copy. A no-watermark free tier is your pitch) |
| Custom watermark (user's own text) | [V:1] |
| Annotation / markup | [V:1] |
| E-signature | [V:1] |
| Password-protected document and share link | [V:1] |
| Import an existing PDF for editing | [V:3] |
| Merge / split / compress / PDF-to-Word/Excel | [V:2 "PDF converter", 16] **(should/could)** |
| Rename document | [I] (S12 title) |
| Auto-naming ("Scan 2026-11-19 17:54") | [I] |
| Print via Android print framework | [V:1] |
| Fax to 30+ countries | [V:1] **skip** (paid telecom service) |

## 8. OCR functionality

| capability | evidence |
| --- | --- |
| Extract text from images or PDFs | [V:1,2] |
| Search documents by OCR text | [V:1] |
| "Find images by content" (visual search) | [V:1] |
| Copy / edit / share extracted text | [V:1] |
| Export as TXT / Word | [V:1,2] |
| Started from the filter screen or doc view | [V:8] (older version) [I] (current version) |
| Language selection | [I] |
| Auto-run OCR on every page (users complained it was forced) | [V:2 AU review on "Recognize Text"] |
| Searchable PDF (invisible text layer) | [I] |
| Single-page OCR free, batch OCR premium | [V:3, older listing] [G: current gating] |

Clone approach: on-device (ML Kit Text Recognition v2, Latin + CJK + Devanagari
scripts) for privacy and offline use. Make auto-OCR a setting that users can switch
off (the reviews in [V:2] ask for this).

## 9. Document library

| capability | evidence |
| --- | --- |
| Recent documents on Home | [I] (shortcuts on Home [V:12]) |
| Folders | [V:2 review "folder", AU review "moving files between folders"] |
| Tags | [V:1,5] |
| Search (title, tag, OCR text) | [V:1,5] |
| Sort (date, name, size) | [I] |
| Grid / list view | [I] |
| Multi-select: move, delete, merge, share | [I] |
| Thumbnails + page count + date | [I] |
| Cloud sync across devices (account) | [V:1] |
| Upload to Google Drive / Dropbox / OneDrive / Evernote / Box | [V:2,3] |
| Shared with me / collaboration / comments | [V:old manual 11, old App Store] **could/skip** |
| Recycle bin | [G] |
| Moving between folders was reported to lose files | [V:2 AU review] (a reliability bar to beat) |

## 10. Settings and user experience

Settings (contents mostly inferred; standard for the category):
| group | items | evidence |
| --- | --- | --- |
| Scan | default mode (single/batch), auto-capture on/off, default filter, auto-crop on/off, shutter sound, grid | [V:15 default mode, auto-capture] [I: rest] |
| Document | default name pattern, PDF page size (A4/Letter/auto), orientation, export quality | [I] |
| OCR | auto-OCR on/off, recognition languages | [I] (the need comes from the [V:2] review) |
| Storage & sync | save location, auto-upload to cloud, Wi-Fi only | [V:3 auto-upload] [I: rest] |
| Security | app lock (PIN/biometric), doc passwords | [V:1 doc passwords] [I: app lock] |
| Account | sign in, subscription, restore purchase, delete data | [V:1 data deletion request] |
| About | feedback, privacy, terms | [V:14 Me → Feedback] |

Verified UX problems (from public reviews; this is your positioning):
- Intrusive ads, including redirects to the Play Store and hard-to-reach close buttons [V:1,13]
- Watermark on free exports [V:4,9,13]
- A 3-day trial that rolled into an annual charge [V:1]
- An overwhelming number of tools [V:12]
- Forced OCR / "Recognize Text" behaviour [V:2]
- Data safety: may share device IDs with third parties [V:1]

Clone UX principles that follow:
1. No ads. No watermark on free exports.
2. Camera opens in at most one tap. Auto-capture is on by default.
3. Everything on-device and offline, with no account needed for the core loop.
4. Honest trial and paywall terms. Clear what is gated.

## Components

| component | variants | states | used on |
| --- | --- | --- | --- |
| Bottom nav + centre FAB | 3 or 5 tabs | selected, badge | S03–S06 |
| Camera viewfinder | — | permission-denied, detecting, locked, capturing | S07 |
| Edge overlay (quad) | live, static | searching, found, stable/locked | S07, S09 |
| Shutter button | manual, auto-countdown | idle, pressed, busy | S07 |
| Mode strip | — | selected | S07 |
| Corner handle + loupe | corner, edge-mid | idle, dragging, invalid-quad | S09 |
| Filter carousel chip | with thumbnail | selected, processing | S10 |
| Slider | brightness, contrast | — | S10 |
| Page thumbnail | grid, strip | selected, dragging, processing | S11, S12, S14 |
| Doc list row / card | list, grid | default, selected, syncing, locked | S03, S04, S18 |
| Folder row | — | default, drop-target | S04 |
| Bottom action bar | doc, page | disabled items | S12, S13 |
| Bottom sheet | share/export, more, sort | — | S12, S16 |
| Dialog | rename, delete confirm, password | error | many |
| Snackbar / toast | info, undo | — | many |
| Empty state | no docs, no results, no text found | — | S03, S04, S15, S18 |
| Progress | linear, circular, per-page | — | S10, S15, S16 |
| Search field | — | empty, typing, results | S03, S18 |
| Settings row | toggle, chooser, link | disabled | S19 |
| Paywall plan card | weekly, monthly, yearly | selected | S02 |
| Signature pad | — | empty, drawn | S17 |

## Inferred data model

```
Document   id, title, folder_id?, created_at, updated_at, page_count,
           cover_page_id, tags[], is_locked, password_hash?, ocr_status
           (none | pending | done | failed), sync_state
           evidence: S12 title/rename, tags [V:1], passwords [V:1], OCR search [V:1]
           confidence: high (fields), medium (statuses)

Page       id, document_id, position, original_image_uri, processed_image_uri,
           quad (4 corner points, normalised 0..1), rotation (0/90/180/270),
           filter (original | clean | gray | bw | ...), adjustments
           {brightness, contrast}, width_px, height_px, ocr_text?, ocr_lang?,
           created_at
           evidence: re-crop and re-filter after save imply the original + quad are kept
           confidence: high for the clone design. CamScanner's storage is unknown [G]

Folder     id, name, parent_id?, created_at, sort_order
           evidence: folders [V reviews]   confidence: medium (nesting is a guess)

Tag        id, name    DocumentTag(document_id, tag_id)
           evidence: [V:1,5]   confidence: high

Signature  id, image_uri, created_at
           evidence: e-signature [V:1]   confidence: medium

Annotation id, page_id, type (ink | text | watermark | signature), payload (json), z
           evidence: annotation + watermark [V:1]   confidence: guess on structure

ExportJob  id, document_id, format (pdf | jpg | txt | docx), page_size, quality,
           password?, created_at, output_uri
           confidence: guess (may be transient, not stored)

Settings   key/value: default_mode, auto_capture, default_filter, auto_crop,
           auto_ocr, ocr_langs, page_size, name_pattern, app_lock
           confidence: medium

Account / Subscription   (only if sync or premium is built) user_id, plan,
           expires_at, store_purchase_token
           confidence: high that it exists; out of v1 scope
```

Relationships: Folder 1-n Document (Folder self-nests [G]), Document 1-n Page,
Page 1-n Annotation, Document n-n Tag, Signature is standalone (placed as Annotation).
Store this locally in Room (SQLite) plus app-private files. Sync is a later layer.

## Feature matrix

See `features.csv`: 75 rows. Must 24, should 22, could 21, skip 8. Every row's
notes start with [V:#] or [I] or [G].

## Out of scope (cannot or should not be cloned)

- The "CamScanner" name, logo, colours, the "Magic Color" filter name, the
  "Scanned by CamScanner" watermark, and any of their copy or screenshots
- Fax to 30+ countries: a paid telecom partnership
- CamScanner Cloud and its web app (camscanner.com): their service. Build your own sync later if needed
- Shared-with-me collaboration network and comments: needs your own backend and users
- CS AI chat / AI Erase / document translation: depends on third-party models and costs. Could be added later, not a clone target
- Their OCR and detection engines (proprietary). Use ML Kit / OpenCV / your own model
- Accolades, user counts, ratings ("500M+ installs", "Editors' Choice")
- Ad-network monetisation: deliberately not copied

## Size

Screens 23 (8 in the core loop). Flows: 3 drawn above, plus about 9 secondary
(import from gallery, OCR + copy, rename/move/tag, search, sign, password, print,
ID card, settings). Entities 7 (+2 if accounts/sync).

Hard parts:
1. **Real-time edge detection + auto-capture** on the CameraX frame stream, with stability
   detection that feels instant on mid-range Android phones
2. **Enhancement quality**: a "Clean" filter that whitens paper and keeps ink sharp
   under uneven light and shadows. This is what users judge the app on
3. **OCR + searchable PDF**: text layer alignment, multiple scripts, background processing
   via WorkManager without draining the battery
4. (secondary) Large batches: memory, thumbnail caching, crash-safe drafts
5. (secondary) Play Billing subscriptions with honest trial handling

Size: **M** (a few weeks) for the core loop + library + OCR, offline-only, on
Android with Kotlin/Compose. **L** if cloud sync, PDF tools (merge/split/convert to
Word) and signatures are all included. No promise of pixel or quality parity on
detection and filters. Those need tuning against real documents.

## Next evidence to gather

- Your own screenshots of the Play listing's screenshot carousel, plus a screen
  recording of you using the app (your own device and account, at normal speed),
  saved into `replica/screens/` as `S07-camera.png` etc. This converts most [I] rows to [V].
- Confirm: bottom-nav tab names, the current filter list, the Settings contents, and
  which features are paywalled today.
