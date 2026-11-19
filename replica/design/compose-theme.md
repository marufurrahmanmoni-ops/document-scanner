# Compose theme mapping

How `tokens.json` maps onto Jetpack Compose Material 3. This replaces the skill's
`tokens.css` and Tailwind step: on a native Android app the theme is the token layer.

Rule: **components read roles from the theme, never raw hex.** A screen that writes
`Color(0xFF1F5FD6)` is a bug. replica-brand changes the values in one file.

Planned files (spec only, not written yet):

```
core/designsystem/
  theme/Color.kt        token values -> ScannerColors + M3 ColorScheme
  theme/Type.kt         Inter + Noto Sans Bengali -> Typography
  theme/Shape.kt        radius tokens -> Shapes
  theme/Dimens.kt       space, size, elevation tokens
  theme/Motion.kt       durations + easings
  theme/ScannerTheme.kt CompositionLocals + MaterialTheme wrapper
  component/...         primitives from components.md
  preview/DesignCatalog.kt  @Preview gallery of every primitive and state
res/font/ inter_*.ttf, noto_sans_bengali_*.ttf
```

## Colour: Material 3 roles

Dynamic colour (Material You) is **off**. The brand blue must stay the same on every device.
Only a light scheme ships in v1. The camera screens use their own fixed dark sub-palette.

| M3 role | token | hex |
| --- | --- | --- |
| primary | accent | #1F5FD6 |
| onPrimary | on-accent | #FFFFFF |
| primaryContainer | accent-container | #E8F0FE |
| onPrimaryContainer | on-accent-container | #0E3A8C |
| secondary | text-muted | #4B5563 |
| onSecondary | bg | #FFFFFF |
| secondaryContainer | surface-sunken | #EBEEF3 |
| onSecondaryContainer | text | #111827 |
| background | bg | #FFFFFF |
| onBackground | text | #111827 |
| surface | bg | #FFFFFF |
| onSurface | text | #111827 |
| surfaceVariant | surface | #F5F7FA |
| onSurfaceVariant | text-muted | #4B5563 |
| surfaceContainerLowest | bg | #FFFFFF |
| surfaceContainerLow | surface | #F5F7FA |
| surfaceContainer | surface | #F5F7FA |
| surfaceContainerHigh | surface-sunken | #EBEEF3 |
| surfaceContainerHighest | surface-sunken | #EBEEF3 |
| outline | border-input | #7F8896 |
| outlineVariant | border | #E2E6EC |
| error | danger | #C42B2B |
| onError | on-danger | #FFFFFF |
| errorContainer | danger-container | #FDECEC |
| onErrorContainer | danger | #C42B2B |
| scrim | overlay.scrim | #111827 @ 48% |
| surfaceTint | **transparent** | (no tonal tint, so white stays white) |

Extended roles (no M3 slot), exposed as `LocalScannerColors`:
`textSubtle, textDisabled, borderStrong, accentPressed, success, successContainer,
warning, warningContainer, cameraBg, cameraSurface, cameraText, cameraTextMuted,
edgeSearching, edgeDetected, edgeLocked, edgeFill, page, selected, pressed`.

## Typography

| M3 style | token | sp size/line | weight |
| --- | --- | --- | --- |
| displaySmall | display | 32/40 | 700 |
| headlineSmall | headline | 24/32 | 700 |
| titleLarge | title-lg | 20/28 | 600 |
| titleMedium | title | 16/24 | 600 |
| bodyLarge | body | 16/24 | 400 |
| bodyMedium | body-sm | 14/20 | 400 |
| labelLarge | label | 14/20 | 600 |
| labelMedium | label-sm | 12/16 | 600 |
| bodySmall | caption | 12/16 | 400 |
| labelSmall | overline | 11/16 | 600, +0.8 tracking, uppercase (Latin only) |

Font family: one `FontFamily` per script. Compose picks a glyph fallback per character,
but line height does not adjust. Do this:

- When the app locale is `bn`, `ScannerTheme` swaps in the `bengali_line` values from
  tokens.json. Bengali matras and conjuncts clip at Latin line heights.
- In OCR result text (S15), the line height comes from the detected script of that
  block, not from the app locale. A Bengali document read in an English UI still needs 28sp lines.
- Set `PlatformTextStyle(includeFontPadding = false)` and
  `LineHeightStyle(Alignment.Center, Trim.None)` everywhere. Do not use Trim on Bengali.
- Respect the user's font scale up to 200%. Layouts are specified to wrap, never truncate,
  at 1.5x (see screens.md).

## Shape

| M3 slot | token | dp | used by |
| --- | --- | --- | --- |
| extraSmall | radius.xs | 4 | page thumbnails, tags |
| small | radius.sm | 8 | chips, text fields, snackbars |
| medium | radius.md | 12 | buttons, cards, doc tiles, dialogs' inner cards |
| large | radius.lg | 16 | FAB, filter tiles |
| extraLarge | radius.xl | 24 | bottom sheet top corners, dialogs |
| (custom) pill | radius.pill | 50% | shutter, page-count pill, segmented mode strip |

## Dimens, elevation, motion

Expose `LocalDimens` with `space.*`, `size.*`, and `elevation.*` exactly as named in tokens.json
(for example `Dimens.space4 = 16.dp`, `Dimens.scanFab = 64.dp`).
`LocalMotion` holds `fast=100, base=200, slow=300, page=350` ms and the three easings
as `CubicBezierEasing`. Check `Settings.Global.ANIMATOR_DURATION_SCALE == 0`, and if so
`LocalMotion` returns zero durations.

## Icons

Use Material Symbols Rounded (Apache 2.0), weight 400, optical size 24. Use outlined when
inactive and filled when selected in navigation. Make all app illustrations (empty states,
onboarding) fresh: simple two-tone line art in `accent` + `border-strong`.
Do not trace any CamScanner illustration.
