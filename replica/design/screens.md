# Screen specifications

The screen IDs match `../recon.md`. CamScanner was used for what each screen has to do and which
states it needs. It was not used for layout or look. All layouts below are original.
Sizes are in dp. Tokens are in `tokens.json`, and components are in `components.md`.

## Global layout

| window class | width | navigation | gutter | library grid | page grid | doc view |
| --- | --- | --- | --- | --- | --- | --- |
| compact | < 600 | BottomNavBar + docked ScanFab | 16 | 2 col | 3 col | single pane |
| medium | 600–839 | NavigationRail (ScanFab at top) | 24 | 3 col | 4 col | single pane, max width 840 |
| expanded | ≥ 840 | NavigationRail | 32 | 5 col (adaptive, min 156) | 6 col | two panes: library list 360 + document |

- Edge-to-edge on. Content pads for the status bar, the nav bar and the IME. The camera draws under the system bars.
- Orientation: tab screens and the doc view rotate freely. The camera locks to the sensor's natural orientation and rotates the controls instead of the layout.
- Foldables: on a half-open device (tabletop posture) the camera puts the preview on top and the CaptureBar on the bottom half.
- Font scale 1.5x: titles wrap to 2 lines, tiles grow taller, and no label truncates except document titles (2 lines + ellipsis).
- RTL is not needed for en/bn, but use `start`/`end` everywhere anyway.

Navigation map:

```
Tabs: Home(S03) · Files(S04) · [Scan → S07] · Tools(S05) · Settings(S19)
Capture (full screen, no nav):  S07 ─▶ S09 ─▶ S10 ─▶ S12          (single)
                                S07 ⟲ n ─▶ S11 ─▶ S12            (batch)
                                S08 (gallery) ─▶ S09 per image ─▶ S10 ─▶ S11
Document:  S12 ─▶ S13 page · S14 manage · S15 text · S16 share · S20 lock · S21 print
Back from S07 with pages captured → "Discard 3 pages?" dialog (Keep scanning / Discard).
```

---

## S01 Onboarding (first launch only)
- 3 pages max, each with an illustration (our own), headline, and one line of body: *Scan in a tap* · *Clean, sharp PDFs* · *Your files stay on your phone*.
- A page indicator, then "Skip" (text) and "Next" / "Start scanning" (primary lg, full width).
- The last page asks for camera permission in context: "Start scanning" requests it, then opens S07 straight away. The first value is a scan, not a tour.
- Expanded: illustration left, text right.

## S03 Home
```
┌──────────────────────────────┐
│ Good evening        (avatar) │ large TopAppBar (headline)
│ [🔍 Search documents       ] │ SearchField → S18
│                              │
│ Quick actions                │ overline
│ [Import][ID card][PDF][Text] │ 4 tonal tiles 72h, radius md
│                              │
│ Recent                See all│ title + text button → S04
│ ┌────┐ ┌────┐                │ DocumentTile grid, 2 col
│ │    │ │    │                │ (max 6, then "See all")
│ └────┘ └────┘                │
├──────────────────────────────┤
│ Home  Files  (◉)  Tools  Set │ BottomNavBar + ScanFab
└──────────────────────────────┘
```
- Empty: replace Recent with the "No documents yet" EmptyState. The primary button opens S07. Quick actions stay.
- Loading: 4 tile skeletons. Never a full-screen spinner.
- Processing doc (OCR / export running): the tile shows "Processing…" and the user can still open it.
- Quick actions (v1): Import photos (S08), Scan ID card (S07 in ID mode), Import PDF, Extract text (S07 then S15).

## S04 Files (library)
- TopAppBar "Files". Actions: search, sort, view toggle (grid ⇄ list).
- Row of filter chips: All · Folders · Tags… · Locked. They scroll horizontally.
- Folders first (FolderTile in grid / FolderRow in list), then documents. Sort by Date edited (default) / Name / Size / Pages. Asc/desc is remembered.
- Breadcrumb under the app bar inside a folder: `Files › Work › Tax`. Each crumb is tappable.
- Long-press enters selection: a contextual TopAppBar ("3 selected"), with a bottom action bar of Share · Move · Merge · Delete.
- Move: a bottom sheet folder picker with "New folder". The drop target highlights. The move is atomic and shows a snackbar with Undo. (Users of the original report lost files on moves. This must be bulletproof.)
- Delete: goes to Recently deleted (30 days) and shows a snackbar Undo. A permanent delete needs a dialog.
- FAB: none extra. "New folder" is in the overflow menu.
- Empty folder: EmptyState "This folder is empty" with buttons Scan here / Move documents here.
- Expanded: two panes. The list is on the left (360) and the selected document's S12 is on the right.

## S05 Tools
- One curated grid (2 col compact / 4 medium+). There are at most 8 tools in v1, in 2 groups:
  - **Scan**: ID card, Import photos, QR code
  - **PDF**: Import PDF, Merge, Extract text, Sign (later), Protect
- Each tool is a ListRow-style tile: icon in accent-container, title, one-line description. There is no tool catalogue sprawl (the recon found the original's tool hub overwhelming).

## S07 Camera (the most important screen)
```
┌──────────────────────────────┐ camera-bg, edge-to-edge
│ ✕        ⚡A   (A)  #   ⚙   │ CameraTopBar (on-camera icons)
│                              │
│    ┌──────────────────┐      │
│    │                  │      │ EdgeOverlay on live preview
│    │   (quad: blue →  │      │ searching → detected → locked(green)
│    │    green, pulse) │      │
│    └──────────────────┘      │
│      [ Hold still ]          │ hint pill, camera-scrim, label
│                              │
│  Single  •Batch  ID card  QR │ ModeStrip
│ [▣]        ( ◯ )       [▤ 3]│ gallery · ShutterButton · PageStackThumb
└──────────────────────────────┘
```
- Opens in at most 300ms after the FAB tap. Show a camera-surface frame with a spinner until the first preview frame arrives. Last mode used is remembered (default Batch, auto-capture on).
- Auto-capture: when the quad is locked for 600ms, capture, do a haptic, show a white flash (80ms), and the thumbnail flies into PageStackThumb (page, 350ms). In batch, scanning continues with a 1s cooldown and needs the quad to change before the next capture, so the same page is not taken twice.
- Single mode: after capture go straight to S09 (or S10 if the setting "Skip crop when detection is confident" is on).
- Hints (one at a time, priority order): "Allow camera" › "Too dark, turn on flash" › "Move closer" › "Hold still" › "Point at a document".
- **S07a Permission needed**: camera-bg, illustration, "Camera access is needed to scan", buttons Allow (primary) / Import photos instead (text). If permanently denied, the primary button becomes "Open settings".
- Back/close with pages captured: the discard dialog.
- Accessibility: all controls reachable by TalkBack, and the shutter works with volume keys (setting). Edge state is announced (polite, throttled).

## S08 Import from gallery
- Use the Android Photo Picker (`PickMultipleVisualMedia`). It needs no storage permission and gives the system UI.
- After the picker returns: a short "Preparing 5 photos…" progress, then each image runs auto-detect → S11 review (or S09 for a single image).

## S09 Crop
- Dark canvas (camera-bg) so the page stands out. CropCanvas with loupe.
- Top: back (←, "Retake"), title "Adjust edges" (title-lg, camera-text), page "2 / 5" in batch.
- Bottom bar: Rotate ↺ · Auto · Full page · [Next] (primary lg).
- Invalid quad: Next disabled + hint in danger-container banner.
- In batch from S11, the user can swipe left/right between pages without leaving the crop screen.

## S10 Enhance
- Light screen again (bg). Large page preview on surface-sunken with elevation page.
- FilterCarousel docked at the bottom with "Apply to all pages" (batch). The "Adjust" tile opens sliders.
- Default filter = Settings value (default **Auto**). The preview re-renders in under 150ms on a 1600px proxy, and the full-resolution render happens at save.
- Top bar: back, "Enhance", rotate, and an overflow menu (Recognise text now).
- Primary: "Save" (single) / "Done" (batch → S11).

## S11 Batch review
- TopAppBar: "5 pages", actions Add more (camera+), Select.
- PageThumbnail grid (3 col compact) with drag reorder. Tap a page to open S09/S10 for that page. Long-press drags it.
- Bottom bar: Retake · Crop · Filter · Delete (act on selection, or on the focused page).
- The document title field is at the top, prefilled "Scan 19 Nov 2026 18:05", and editable inline.
- Primary pinned bottom: **Save PDF** (lg, full width). The secondary overflow menu has "Save as images".
- The draft auto-saves on every change. Killing the app and reopening shows the banner "Continue your unsaved scan?" on Home.

## S12 Document view (professional PDF preview)
```
┌──────────────────────────────┐
│ ←  Tax invoice 2026      ⋮  │ small TopAppBar, title tappable → rename
├──────────────────────────────┤ surface-sunken canvas
│        ┌──────────┐          │
│        │  page 1  │  [3/12]  │ PdfPageView, page elevation 2
│        │ (white)  │          │ PageCounterPill
│        └──────────┘          │
│        ┌──────────┐          │ vertical scroll, 16 gap
│        │  page 2  │          │ pinch zoom 1–5x
├──────────────────────────────┤
│ Share   Add   Edit   Text  ⋯ │ bottom action bar (icons + labels)
└──────────────────────────────┘
```
- The view toggle in ⋮ switches between continuous pages (default) and a page grid (S14).
- Expanded: a thumbnail rail on the left (96 wide), the page canvas in the centre, and an optional info pane on the right (tags, folder, created, size, OCR status).
- Search inside the doc (⋮ → Find): matched words are highlighted on the page using the OCR boxes.
- OCR running: a linear progress under the app bar, "Recognising text 3 of 12". The document is fully usable meanwhile.
- Locked doc: open → S20 prompt first. The thumbnails in the library are blurred.

## S13 Page editor
- One page full screen on the canvas. Tools in a bottom bar: Crop · Filter · Rotate · Annotate (later) · Sign (later) · Delete.
- Every edit is non-destructive (the original is kept). "Reset page" is in the overflow menu.

## S14 Manage pages
- PageThumbnail grid with drag reorder and multi-select. Actions: Move to another document · Extract as new document · Delete · Rotate.

## S15 Text (OCR result)
- Top: language segmented control **English · বাংলা · Auto**. Changing it re-runs OCR for that page.
- Body: selectable, editable text, body style. The line height is set by the detected script (bengali_line for Bengali).
- Per-page tabs ("Page 1, 2, 3…") or "All pages" joined with page breaks.
- Actions: Copy all (snackbar "Copied"), Share text, Save as TXT. The confidence warning banner shows if the mean confidence is under 60%.
- States: running (skeleton lines + progress), result, no text found (EmptyState), low confidence (warning Banner "Some text may be wrong. Try the Gray filter or a sharper photo.").

## S16 Share / export (bottom sheet)
```
Share "Tax invoice 2026"
Format      [ PDF | JPG ]          SegmentedControl
Pages       All pages ▸            (range picker)
Quality     [ Small | Balanced | Original ]   + estimated size "≈ 2.4 MB"
Page size   [ Auto | A4 | Letter ] (PDF only)
☐ Include text layer (searchable PDF)    (on if OCR done)
☐ Protect with password
──────────────────────────────
[ Save to device ]  [ Share ▸ ]   secondary + primary
```
- No watermark, ever. The size estimate updates live.
- "Share" builds the file in cache and opens the Android share sheet through a FileProvider URI. "Save to device" uses the SAF document picker (`CreateDocument`).
- Progress replaces the buttons while exporting ("Building PDF… 6 / 12"). It can be cancelled.

## S18 Search
- The search field is focused with recent searches below. Results: documents (title and tag matches) and "Found in text" rows that show a snippet with the match highlighted (body-sm, match in accent weight 600) and the page number.
- Tapping a text hit opens S12 at that page with the hit highlighted.
- Works offline over the local full-text index. English and Bengali (Unicode-normalised).

## S19 Settings
Sections (SectionHeader + rows):
- **Scanning**: Default mode (Single/Batch) · Auto-capture · Default filter · Skip crop when edges are clear · Shutter sound · Volume key to capture
- **Documents**: File name format · Default page size · Default export quality · Default folder
- **Text recognition**: Recognise text automatically (default off) · Default language (English · বাংলা · Both). Both models ship with the app, so this works offline from the first launch
- **Privacy & security**: App lock (PIN / biometric) · Lock after (immediately / 1 min / 5 min) · Hide previews in recent apps (FLAG_SECURE)
- **Storage**: Space used · Recently deleted · Clear cache
- **About**: Version · Licences (OSS: OpenCV, Tesseract, fonts) · Privacy policy · Send feedback

## S20 Lock / password
- App lock: full screen with a PIN pad (6 digits) and a biometric prompt first. Document password: a Dialog with a TextField and show/hide.
- Wrong attempts: shake (fast), with an error caption. After 5 attempts there is a 30s cooldown.

## S21 Print
- Uses the system print dialog via `PrintManager` with a PDF print adapter. There is no custom UI.

## S22 ID card
- Camera mode "ID card": the overlay is a card-ratio frame (85.6:54), with the hint "Front side" then "Back side".
- Result: both sides placed on one A4 page with a 24dp gap, in the preview. Options: page size and order. Then save it as a document.

## Not specified (out of v1)
S02 paywall (spec when pricing is decided), S17 signature, S23 AI. Their primitives (PaywallPlanCard) are listed in components.md.
