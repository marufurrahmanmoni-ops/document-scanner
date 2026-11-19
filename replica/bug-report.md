# Bug report

Only bugs reproduced here are listed. Severity: **S1** data loss, security hole, payments wrong, or core flow blocked ·
**S2** feature broken, no workaround · **S3** broken with a workaround, or visibly wrong · **S4** cosmetic.

| id | severity | title | status | fixed in |
| --- | --- | --- | --- | --- |
| BUG-001 | S1 | Renaming a document on another phone deletes all its pages on this phone | **fixed** | `c20fc71` |
| BUG-002 | S3 | Edge detector outlines a "page" in clutter where there is none | **fixed** | `c20fc71` |

**Open S1: 0 · open S2: 0.** This covers only what could be exercised here. Nothing has run on a phone yet, so on-device
bugs are still unknown (see "To check").

---

## BUG-001 · S1 · Remote document update cascade-deletes local pages

**Where:** `data/db/Daos.kt`, `SyncDao.upsertDocument` / `upsertPage`. Applies only to users who turn on cloud backup on two phones.

**Steps**
1. Phone A and phone B are both signed in with backup on; document "Lease" with 3 pages is on both.
2. Rename "Lease" on phone B. Sync runs.
3. Phone A pulls the change.

**Expected:** phone A shows the new title with 3 pages.
**Actual:** phone A shows the new title with **0 pages**. The page rows are gone; the images become orphans and are purged by housekeeping.

**Cause:** the methods were `@Insert(onConflict = REPLACE)`. SQLite's REPLACE deletes the old row before inserting,
and `pages.document_id` has `ON DELETE CASCADE` with foreign keys on (Room enables them), so every page went with it.

**Evidence:** `tools/repro/bug001_replace_cascade.py` builds the real tables from the exported schema
(`app/schemas/.../2.json`) with `PRAGMA foreign_keys = ON`:

```
mode=replace pages before=3 after=0   exit 1   # before the fix
mode=upsert  pages before=3 after=3   exit 0   # after the fix
```

**Fix:** both methods are now `@Upsert` (INSERT, then UPDATE on conflict). The generated `SyncDao_Impl` has no
`INSERT OR REPLACE` for documents or pages. The one remaining REPLACE (`putShadow` on `sync_shadow`) has no child rows.

**Regression tests:** `DataLayerTest.applyingARemoteDocumentUpdateKeepsItsPages` (Robolectric, NOT RUN here) and the repro script (run, passes).

---

## BUG-002 · S3 · False document outline in cluttered scenes

**Where:** `imaging/EdgeDetector.kt`.

**Steps:** point the camera at a cluttered surface with no document (cables, wood grain, objects).
**Expected:** no outline; the shutter stays manual.
**Actual:** in 1 of 10 synthetic clutter scenes the detector returned a 4-corner outline covering 70% of the frame,
with a slanted bottom edge that lies on nothing. With auto-capture on, it could capture a bad crop.
Workaround: drag the corners on the crop screen. That is why it is S3.

**Evidence:** `ImagingTest.emptyClutterIsNotADocument` failed: `expected:<0> but was:<1>`, quad
`[(0.03,0.03) (0.91,0.03) (0.96,0.66) (0.05,0.95)]`, area 0.70.

**Cause:** random line segments formed a closed ragged contour with 5–6 real corners. `approxPolyDP` at 2% of the
perimeter simplified it to 4 points that passed the convexity and angle checks, though its "sides" cut through empty space.

**Fix:** a new `sidesOnEdges` check. A real page's sides lie on edge pixels, so each side must have an edge pixel within
2 px under at least 70% of its middle 80%.

**Verified:** 0 false positives in 20 clutter scenes, and every detection scene still passes (A4 on dark and light desks,
clutter, receipts, ID cards, books, low light, blur).
**Not verified:** real camera frames. Bowed book pages on a real phone are the case to watch.

---

## To check (not reproduced, so not bugs yet)

- Everything on a phone: first launch, permission flows, camera interruption, rotation, 30-page batches on low RAM, large PDFs.
- `firebase-iid` was excluded from `firebase-functions` to drop an unused push permission. R8 and the build pass.
  Callable functions are expected to work without it, but this has not been run against a real project.
- `QuadTracker.minSharpness = 25` is a guess and needs calibration on real phones.
- 45 lint warnings (0 errors), not triaged.
- Bengali strings are a draft translation; a native speaker should review them.
