# Components

Every primitive the screens use. Each one is built once in `core/designsystem/component`
and shown in the `DesignCatalog` @Preview gallery in every state before any screen uses it.
Token names refer to `tokens.json`. Screen IDs (S..) refer to `../recon.md`.

Rules for every component:
- Touch target at least 48dp, even when the visual is smaller (`Modifier.minimumInteractiveComponentSize()`).
- Focus: a 2dp `focus` ring outside the shape, offset 2dp. It is visible with a keyboard or D-pad and on ChromeOS.
- TalkBack: every icon-only control has a `contentDescription` written as a verb ("Delete page").
  Groups use `Modifier.semantics(mergeDescendants = true)`.
- Disabled: `text-disabled` for content, at 38% on fills. Never rely on colour alone.
- Pressed: `overlay.pressed` ripple, bounded to the shape.
- Every label is our own words. See "Copy" at the end.

---

## Navigation

```
BottomNavBar
  items     Home, Files, [Scan], Tools, Settings   (Scan is ScanFab docked in the centre)
  height    size.bottom-nav 80 + system nav inset
  tokens    bg bg, top border 1dp border, icon 24, label label-sm
  states    selected (icon filled, accent-container pill 64x32 behind the icon, label text, weight 600),
            unselected (icon outlined, text-muted), pressed, focus
  a11y      Role.Tab, selected state announced, "Tab 2 of 4". The Scan FAB is a separate Button, not a tab
  adaptive  compact: bottom bar. medium+: NavigationRail (80dp) with ScanFab at the rail's top
  used on   S03 S04 S05 S19
```

```
ScanFab
  purpose   the one-tap path to the camera, always visible on tab screens
  size      64dp circle (size.scan-fab), icon document_scanner 32
  tokens    bg accent, icon on-accent, elevation fab 6, pressed accent-pressed
  position  docked in the centre of the bottom bar, raised 12dp above its top edge
  states    default, pressed (scale 0.96, fast), focus, disabled (only while camera permission is permanently denied; tapping opens the permission sheet)
  gesture   long-press opens a small menu: Scan document / Import photos / Scan ID card
  a11y      label "Scan a document", custom action "More scan options"
  used on   S03 S04 S05 S19
```

```
TopAppBar
  variants  large (Home: headline title collapsing to title-lg), small (title-lg), contextual (selection: "3 selected", close X, actions)
  height    64 (small), 112 → 64 (large, collapses on scroll)
  tokens    bg bg, title text, actions text-muted, divider border appears only after scroll
  states    default, scrolled (1dp bottom border), selection mode (bg accent-container, text on-accent-container)
  used on   all non-camera screens
```

```
SearchField
  height    48, radius pill
  tokens    bg surface, placeholder text-subtle, text text, leading icon text-muted
  states    resting (tappable, opens S18), focused (border 2dp focus), typing (clear X), loading (trailing 16dp progress)
  a11y      Role.Search via semantics, IME action Search
  used on   S03 S04 S18
```

## Buttons and inputs

```
Button
  variants  primary (accent / on-accent), secondary (surface / text, 1dp border), tonal (accent-container / on-accent-container),
            text (transparent / accent), danger (danger / on-danger)
  sizes     sm 36, md 44, lg 52. Horizontal padding 16 / 20 / 24
  shape     radius md 12
  type      label (14/600). lg uses title (16/600)
  states    default, pressed, focus-visible, disabled, loading (label stays for TalkBack, 20dp spinner replaces the leading icon, width locked)
  used on   S01 S02 S09 S10 S11 S16 S20
```

```
IconButton
  variants  standard (text-muted), filled (accent), tonal (accent-container), on-camera (camera-text on camera-scrim 40dp circle)
  size      48 touch, 24 icon
  states    default, pressed, selected (toggle: flash on etc.), focus, disabled
  used on   everywhere
```

```
TextField
  height    52, radius sm 8, label above field (not floating, so it works for Bengali)
  tokens    border border-input 1dp, focus 2dp focus, error 2dp danger + caption message, bg bg
  states    empty, filled, focused, error, disabled, read-only
  used on   S12 rename dialog, S18, S20, S19
```

```
Switch / Checkbox / RadioButton
  base      M3, coloured by theme (track on accent, off border-input)
  rows      always inside a SettingsRow or ListRow so the whole row is the target
  used on   S11 (select), S19, S16
```

```
SegmentedControl
  purpose   2–4 exclusive choices (PDF / JPG, A4 / Letter / Auto)
  height    40, radius pill, bg surface, selected bg bg + elevation raised + text
  used on   S16 S19
```

## Camera

```
CameraViewfinder
  layout    full-bleed preview (CameraX PreviewView, FILL_CENTER), bg camera-bg, letterbox camera-bg
  overlays  EdgeOverlay, top CameraTopBar, bottom CaptureBar, hint toast
  states    permission-needed (S07a), starting (camera-surface + spinner), live, low-light (hint), capturing (shutter flash: 80ms white at 40%), processing (frozen frame + spinner)
  used on   S07
```

```
EdgeOverlay
  purpose   shows the detected page quad on the live preview
  stroke    3dp, with a 1dp rgba(0,0,0,0.35) outer halo so it reads on light paper
  states    searching   no quad. 4 faint corner brackets in edge-searching at 60%. Hint "Point at a document"
            detected    quad stroke edge-detected + fill edge-fill. Corners 8dp dots
            locked      steady for 600ms: stroke edge-locked, quad pulses once (base), auto-capture fires
            capturing   quad freezes, shutter animation
  motion    quad corners interpolate frame to frame (fast, ease-standard) so it does not jitter
  a11y      TalkBack polite announcements, throttled to 1 per 2s: "Document found", "Hold still", "Captured page 3"
  used on   S07
```

```
CaptureBar
  layout    bottom of S07, bg camera-scrim, safe-area aware
  parts     [Gallery thumb 48]  [ShutterButton]  [Page stack thumb / Done]
            above: ModeStrip
  used on   S07
```

```
ShutterButton
  size      76 outer ring 4dp camera-text, inner 62 disc camera-text
  states    manual (white disc), auto-armed (inner disc shows a ring timer in edge-locked while locked), pressed (inner 56), busy (spinner in ring), disabled
  a11y      "Take photo". With auto-capture on: "Take photo now, auto capture is on"
  used on   S07
```

```
ModeStrip
  items     Single · Batch · ID card · QR    (v1. More modes later)
  style     horizontal swipeable text chips on camera-scrim. Selected camera-text label (600) + 4dp dot, others camera-text-muted
  behaviour swipe on the viewfinder also changes mode. A haptic tick on change
  used on   S07
```

```
PageStackThumb
  purpose   in batch mode, shows the last captured page and the count
  size      48x64 page with a 2-layer stack offset, count badge (accent, label-sm) at the top-right
  states    hidden (0 pages), 1, n, processing (shimmer)
  tap       opens S11 review
  used on   S07
```

```
CameraTopBar
  items     close X · flash (off/auto/on/torch, cycles) · auto-capture toggle (A) · grid toggle · settings shortcut
  style     IconButton on-camera
  used on   S07
```

## Crop and enhance

```
CropCanvas
  layout    page image fit to the area with 24dp padding, bg camera-bg (the crop screen is dark like the camera)
  handles   4 corners: 24dp visual circle, bg camera-text, 3dp accent ring, 48dp hit area
            4 edge midpoints: 32x8 pill handles, move an edge in parallel
  quad      2dp accent stroke, outside area dimmed camera-scrim
  loupe     while dragging: a 112dp circle above the finger, 2.5x zoom, crosshair, flips sides near the screen edge
  states    auto (detected quad), full-frame (no detection: handles at the image corners + hint "Couldn't find edges. Drag the corners"),
            invalid (self-intersecting or concave: stroke danger + Done disabled + hint), dragging
  actions   bottom: Rotate left · Auto detect · Full page · [Done]
  a11y      each handle is focusable. Arrow keys / TalkBack adjust actions move 1% per step. Announces "Top-left corner"
  used on   S09
```

```
FilterTile
  size      72x96, radius lg, thumbnail of the current page with the filter applied (rendered at 256px)
  label     label-sm under the tile: Original · Auto · Clean · Gray · B&W · B&W Soft
  states    default, selected (2dp accent ring + check badge), processing (shimmer), focus
  used on   S10
```

```
FilterCarousel
  horizontal LazyRow of FilterTile, 12 gap, snaps, selected tile kept in view
  trailing  "Adjust" tile opens AdjustSheet (brightness, contrast sliders)
  footer    Switch row "Apply to all pages" (batch only)
  used on   S10
```

```
Slider
  M3 slider, track accent, value shown in label-sm, -100..100 with a centre detent at 0, reset text button
  used on   AdjustSheet (S10)
```

## Documents and pages

```
DocumentTile (grid)
  size      min width size.doc-card-min-width 156, aspect 3:4 thumbnail + 2 text lines
  parts     thumbnail (page colour, radius xs, 1dp border, elevation page), title (title, 2 lines max), meta caption "12 pages · 19 Nov" text-muted
            badges on the thumbnail: lock, OCR-done ("T"), syncing (later)
  states    default, pressed, selected (accent ring 2dp + check circle top-left + overlay.selected), processing (thumbnail shimmer + "Processing…"), error (warning badge)
  a11y      merged: "Tax invoice, 12 pages, edited 19 November, locked"
  used on   S03 S04 S18
```

```
DocumentRow (list)
  height    size.list-row 72. Thumb 40x52 · title + meta · trailing more-menu
  states    same as DocumentTile
  used on   S04 (list view), S18
```

```
FolderRow / FolderTile
  icon      folder, accent-container bg 40dp, accent icon
  meta      "8 documents"
  states    default, drop-target (dashed accent border, while moving), selected
  used on   S04
```

```
PageThumbnail
  size      grid cell, aspect from the page, radius xs, elevation page, page-number badge label-sm bottom-centre
  states    default, selected, dragging (elevation menu 8, scale 1.04), processing, failed (retry icon)
  reorder   long-press to drag, with haptic. TalkBack custom actions "Move earlier" / "Move later"
  used on   S11 S12 S14
```

```
PdfPageView
  purpose   the professional preview: page as paper on a desk
  layout    canvas bg surface-sunken. Page bg page, elevation page 2, 1dp border at 40%, 16dp vertical gap between pages
  zoom      pinch 1x–5x, double-tap toggles fit-width / 2.5x, pans clamp to the page
  overlay   PageCounterPill: "3 / 12" label-sm on scrim pill, top-centre, fades out after 1.5s idle
  states    loading (page-sized skeleton), rendered, OCR-overlay (highlights search hits in accent at 30%)
  used on   S12
```

```
TagChip
  height    32, radius sm, bg surface, label-sm text-muted. Selected bg accent-container text on-accent-container
  used on   S04 filter row, S12 info sheet
```

## Feedback and overlays

```
BottomSheet (modal)
  top radius xl, drag handle 32x4 border-strong, bg bg, scrim overlay.scrim
  max height 90%. Content scrolls. Primary action pinned at the bottom
  used on   S16 share/export, more menus, sort, AdjustSheet, permission explainer
```

```
Dialog
  radius xl, width min(320, 90%), title title-lg, body body-sm text-muted, actions right-aligned text buttons (danger for destructive)
  used on   rename, delete confirm, password prompt, discard scan
```

```
Snackbar
  bg text (#111827), text bg, action accent-container colour. Bottom, above the nav. 4s, 10s if it has Undo
  used on   delete (Undo), export done ("Saved to Downloads", Open), errors with Retry
```

```
ProgressIndicator
  linear (top of sheet for export), circular 20/40, per-page ring on PageThumbnail
  determinate when page counts are known ("Recognising text 3 of 12")
```

```
EmptyState
  illustration 160dp (our own line art), title title, body body-sm text-muted, one primary Button
  variants  no documents (Home/Files: "Scan your first document" + Scan button),
            empty folder, no search results ("No matches for ‘receipt’" + tips),
            no text found (OCR), camera permission denied (Open settings)
```

```
ListRow / SettingsRow
  height    56 single-line, 72 two-line. Leading icon 24 text-muted, title body, supporting body-sm text-muted, trailing switch/chevron/value
  section   SectionHeader overline text-subtle, 24 top padding
  used on   S16 S19 S06
```

```
Banner (inline)
  variants  info (accent-container), warning (warning-container), error (danger-container)
  used on   low storage, low OCR confidence, export failed
```

```
PaywallPlanCard (later, could)
  radius md, 1dp border. Selected: 2dp accent + accent-container. Price title-lg, period caption
  Rule: the trial length and the renewal price are in body text, never in caption grey
```

## Copy (our own voice)

Short, plain, verb-first. No "magic". Examples:
- Scan FAB: "Scan" · Camera hint: "Point at a document" / "Hold still" / "Move closer"
- Crop fail: "Couldn't find edges. Drag the corners to the page."
- Filters: Original, Auto, Clean, Gray, B&W, B&W Soft
- Export done: "PDF saved" · Empty: "No documents yet. Scan one to get started."
- Every string lives in `strings.xml` with a Bengali `values-bn/strings.xml` from day one.
