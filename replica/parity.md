# Parity report: DocScanner vs CamScanner feature map

Date: 2026-11-19. Source: `replica/features.csv` (82 rows), the test results in `test-plan.md`, and the code at the commit
after `c20fc71`.

## Why there is no parity percentage

`parity.py` counts `partial` as half done. In this matrix, `partial` means **built and compiling, never run on a phone**.
A percentage would turn "compiles" into "half works" and look like a measurement it isn't, so it is left out. These are the real counts:

| priority | rows | built (compiles; logic unit-tested where noted) | verified on a device | not built | skipped |
| --- | --- | --- | --- | --- | --- |
| must | 25 | 25 | **0** | 0 | — |
| should | 27 | 16 | **0** | 11 | — |
| could | 22 | 5 | **0** | 17 | — |
| skip | 8 | — | — | — | 8 |

**Verdict: not shippable yet.** No feature has been verified on a phone, and the release preflight (`deploy.md`) has open
items that only you can do. There are no open S1 or S2 bugs, but that covers only what could run here.

Evidence for the layout diff is missing: `replica/screens/` has no screenshots of the original, and there are none of the
clone because no device or emulator is available. **The layout comparison was not run.** To run it, add pairs at
390×844 and use `imgdiff.py`.

## By area

| area | must | should | could | evidence that exists |
| --- | --- | --- | --- | --- |
| capture | 7/7 built | — | 0/2 | QuadTracker unit tests pass; instrumented capture/interruption tests written, not run |
| detection | 5/5 built | 1/1 built | — | **15 JVM imaging tests pass on the real Kotlin detector** (A4, receipts, IDs, books, low light, blur, clutter) |
| enhance | 4/4 built | 1/2 | 1/2 | filter tests pass (Clean, B&W, Auto colour keep, size extremes) |
| multi-page | 4/4 built | 2/2 built | — | order-key unit tests pass; data-layer tests written, not run |
| pdf | 1/1 built | 1/1 built | 0/5 | **no automated PDF test** |
| export | 1/1 built | 2/3 | 0/2 | file-name unit test only |
| library | 2/2 built | 1/4 | 1/3 | data-layer tests written, not run |
| ocr | — | **0/6** | 0/2 | not built |
| settings | — | 2/2 built | — | none |
| account / sync | 1/1 built | 5/5 built | 2/2 built | crypto 4, merge 6, rules 11, functions 8: all pass; no real Firebase project yet |
| security | — | — | 0/2 | n/a |
| monetisation | — | 1/1 built | 1/1 built | functions tests pass with mocked Play API |
| onboarding | — | — | 0/1 | n/a |

## Missing MUST features
None are missing from the code. All 25 are built, and **all 25 are unverified on a device**. Each must-have needs one pass on a phone before it moves from
`partial` to `yes`.

## Incomplete SHOULD features (not built)
1. OCR: extract text from a page
2. Copy and share extracted text
3. Export text as TXT
4. Search documents by OCR text
5. Searchable PDF with a text layer
6. Auto OCR on/off setting
7. Search documents by title
8. Folders
9. Move documents between folders (reliably: a known complaint about the original)
10. Print through the Android print service
11. Brightness and contrast sliders

Items 1–7 are effectively must-haves for this product: `architecture.md` makes English and Bengali OCR a core requirement.
They are journeys J11–J13 in the test plan, all BLOCKED.

## Failed workflows
No automated workflow failed at the end of this run. Two were found and fixed (`bug-report.md`):
- BUG-001 (S1): a rename on another phone wiped the pages locally. Fixed.
- BUG-002 (S3): a false page outline in clutter. Fixed.

Blocked workflows: J11 Bengali OCR, J12 English OCR, J13 search, J14 folders, J15 PDF editing.

## Behaviour differences (by design)

| flow | original | ours | keep or fix |
| --- | --- | --- | --- |
| export on free tier | watermark on free exports | no watermark | keep: differentiator |
| ads | yes | none | keep |
| OCR | forced "Recognize Text" (user complaints) | not built; planned off by default | build, keep it optional |
| cloud | their cloud, always on with account | end-to-end encrypted, off until the user opts in | keep |
| folders, moving | reviews report lost files when moving | not built | build with the move inside one DB transaction |
| AI chat, fax, collaboration | yes | skipped | keep skipped for v1 |

## UX gaps
- No onboarding (could). The empty home with one Scan button is the first-run path, and it hasn't been checked on a device.
- Camera locks to portrait; the controls don't rotate.
- Fonts: platform sans instead of Inter + Noto Sans Bengali (fonts.google.com was unreachable). Bengali text quality depends on the phone's fonts.
- Bengali strings are a draft translation.
- No multi-select in the library (could).

## Technical limitations
- **No device or emulator here** (no KVM): no UI, camera, PDF or performance evidence.
- **Robolectric blocked** (`gcp.mirrors.maven.org`): 17 data-layer tests can't run here.
- **Storage emulator blocked** (`storage.googleapis.com`): Storage rules untested.
- Size: the debug APK is 142 MB (all ABIs, uncompressed native libs). The release AAB is 59 MB; the estimated per-device download
  for arm64 is about 21 MB compressed, mostly OpenCV (23.5 MB uncompressed) and SQLCipher (5.2 MB).
- Performance: **not measured**. Cold start, filter latency, 30-page memory and PDF write time are all MANUAL in the test plan.

## Improvement plan, in order

**P0: before any release (verification, not new features)**
1. One phone session: install the debug APK, run `connectedDebugAndroidTest` (7 tests) and the manual checklist in `test-plan.md`. Move each must-have to `yes` or file bugs.
2. On your computer, run `./gradlew testDebugUnitTest -Probolectric=true` (17 data-layer tests).
3. Create the real Firebase project, then test sign-in, backup, restore on a second phone, and account deletion (J16–J19 manual cases).
4. Add a JVM test for PDF writing (a 100-page PDF: size, page count, opens) and for the Kotlin perspective warp.

**P1: the missing core (M3)**
5. OCR, English and Bengali (Tesseract `eng` + `ben`, run in WorkManager, off by default), with copy/share/TXT.
6. Search by title and by OCR text (Room FTS4).
7. Folders, with a transactional move.
8. Searchable PDF with a text layer.

**P2: SHOULD polish**
9. Print, and brightness/contrast sliders.
10. Bundle Inter + Noto Sans Bengali, and get the Bengali translation reviewed by a native speaker.
11. Calibrate auto-capture sharpness on real phones; triage the 45 lint warnings.

**P3: COULD, after launch**
12. ID card mode, PDF page editing (J15), signature, annotation, import PDF, merge/split, app lock.

Next: `/replica-build` for P1, starting with OCR.
