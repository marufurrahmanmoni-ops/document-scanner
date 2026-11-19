# Release preflight: DocScanner for Google Play

Date: 2026-11-19. Nothing has been uploaded or published. You do every Play Console step.

## Verdict: **not ready to submit to production yet**

The build pipeline is ready: a signed release, AAB, R8 and 16 KB alignment all verified. The blockers are verification and
account steps that only you can do (rows marked ✗ below). An **internal testing** upload is possible as soon as you have your
own upload key and have settled the application ID.

## Preflight results

| # | check | result | evidence |
| --- | --- | --- | --- |
| 1 | All MUST features implemented | ⚠ **built 25/25, verified on a device 0/25** | `parity.md`. Each must-have needs one pass on a real phone. |
| 2 | Critical tests passing | ⚠ **every test that can run here passes**; 24 cannot run here | JVM 35/35 · Firestore rules 11/11 · Functions 8/8 · lint 0 errors. Not run: 17 Robolectric, 7 instrumented (`test-plan.md`). |
| 3 | No critical security issues | ✓ no open S1/S2 | BUG-001 (S1) fixed. Secret scan of tracked files: clean. No keystore/env/local.properties tracked. `allowBackup=false`; DB is SQLCipher; blobs are Tink-encrypted; FileProvider not exported; only the launcher activity is exported; no cleartext traffic (target 36 default). |
| 4 | No original branding | ✓ code sweep clean, ⚠ name not cleared | `CamScanner|intsig|Magic Colo(u)r` in `app/` + `firebase/`: 0 matches. The icon and strings are original. **"DocScanner" is a generic name and is probably taken on Play; `/replica-brand` (name, trademark check) has not run.** |
| 5 | Correct application ID | ✓ | `io.github.marufurrahmanmoni_ops.docscanner` (chosen by the owner 2026-11-20). Kotlin package stays `com.docscanner.app` (internal only). Permanent after the first upload. |
| 6 | Release signing | ✓ pipeline, ✗ your key | Reads `keystore.properties` (git-ignored) or `SIGNING_*` env vars. Verified with a throwaway key kept outside the repo: `apksigner verify` OK, AAB `jarsigner` OK. **Your upload key does not exist yet.** |
| 7 | R8 / ProGuard | ✓ builds, ⚠ runtime unverified | `isMinifyEnabled` + `isShrinkResources`. Keep rules for OpenCV JNI, SQLCipher, Tink protos and serializable routes. No missing-class errors. A minified build can still fail at runtime through reflection, so **run the release APK on a phone** (scan, save, share, sign in). |
| 8 | Permissions and privacy | ✓ reviewed, ✗ privacy policy not hosted | See the table below. Draft policy: `replica/launch/privacy-policy.md`. |
| 9 | Play compliance | ⚠ see the checklist | target SDK 36 meets the current rule; 16 KB pages OK; Billing 8; in-app account deletion. Still to do: the hosted deletion URL, Data safety and the other forms below. |
| 10 | Release build verification | ✓ | `bundleRelease` and `assembleRelease` succeed; the release APK is 16 KB-aligned (`zipalign -c -P 16`); `aapt2 badging` shows `io.github.marufurrahmanmoni_ops.docscanner` 1 / 0.1.0, targetSdk 36. |

## Artifacts (in `dist/`, not committed)

| file | size | what it is |
| --- | --- | --- |
| `DocScanner-0.1.0-debug.apk` | 142 MB | debug build, for testing on your phone |
| `DocScanner-0.1.0-release-TESTKEY.apk` | 118 MB | R8 release, signed with a **throwaway test key**: install it to check the minified build on a phone. **Never upload.** |
| `DocScanner-0.1.0-release-TESTKEY.aab` | 59 MB | the same, as an App Bundle, to prove the bundle builds and signs. **Do not upload**: Play would bind it to a key you don't hold. |
| `app-debug-androidTest.apk` | 1 MB | instrumented tests |

Estimated per-device download (arm64) from the bundle: about 21 MB. Most of it is OpenCV (23.5 MB uncompressed) and SQLCipher.

### Build your real upload AAB

```bash
# 1. Once: create your upload key outside the repo (you choose and keep the passwords; back up the file).
keytool -genkeypair -v -keystore ~/keys/docscanner-upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000
# 2. Copy keystore.properties.example to keystore.properties and fill it in (git-ignored).
# 3. Build.
./gradlew :app:bundleRelease
#    → app/build/outputs/bundle/release/app-release.aab   (upload this, with Play App Signing on)
```

## Permissions in the release manifest

| permission | from | why | Data safety impact |
| --- | --- | --- | --- |
| CAMERA | app | scanning | runtime prompt; photos stay on the phone |
| INTERNET, ACCESS_NETWORK_STATE | app | sign-in, billing, opt-in backup | — |
| com.android.vending.BILLING | Play Billing | subscription | purchase history |
| WAKE_LOCK, RECEIVE_BOOT_COMPLETED, FOREGROUND_SERVICE | WorkManager | background sync retries | none (no foreground-service type is used) |
| USE_BIOMETRIC, USE_FINGERPRINT | androidx.credentials via firebase-auth | Credential Manager | none |
| READ_GSERVICES | reCAPTCHA via firebase-auth | sign-up abuse protection | device info, handled by Google |
| ~~c2dm RECEIVE~~ | firebase-iid | removed in this release: the app sends no push | — |

No storage or media permissions: gallery import uses the system photo picker.

## Google Play submission checklist (you do these)

**Account and app**
- [ ] Play developer account ($25 once), identity verified.
- [x] Application ID: `io.github.marufurrahmanmoni_ops.docscanner`.
- [ ] An app name you have cleared (row 4).
- [ ] If your account is **personal and created after 13 Nov 2023**: a **closed test with at least 12 testers, opted in for 14 continuous days**, before you can apply for production. Internal testing does not count. Plan for this; it is the longest step.

**Signing**
- [ ] Create the upload key (command above); back up the `.jks` and its passwords somewhere safe outside git.
- [ ] Enrol in Play App Signing on the first upload.
- [ ] Add the Play app-signing and upload SHA-1/SHA-256 fingerprints to Firebase (Google sign-in and App Check need them).

**Backend (from `backend.md`)**
- [ ] Production Firebase project; values in `local.properties` / CI env vars; deploy the rules and functions.
- [ ] App Check: Play Integrity provider enforced on Firestore, Storage and Functions.
- [ ] Play Console subscription product id = `PLAY_SUBSCRIPTION_ID`; RTDN Pub/Sub topic `play-billing`; service account linked.

**Store listing and policy forms**
- [ ] Privacy policy hosted at a public URL (draft: `replica/launch/privacy-policy.md`). It must name Firebase/Google Cloud and Google Play Billing.
- [ ] **Account deletion web URL**: Play requires a web page where users can request deletion without the app. The draft policy has a section for it; host it and enter the URL.
- [ ] Data safety form: collected = email (account), purchase history; optional = user files (only if backup is on: encrypted end-to-end, can be deleted). Not sold, not used for ads. Encrypted in transit: yes.
- [ ] Content rating questionnaire; target audience 18+ or 13+ (not children); ads: **none**.
- [ ] Listing: short and full description, 512×512 icon, 1024×500 feature graphic, at least 2 phone screenshots. These are not made yet; they need a phone.

**Before promoting any build**
- [ ] Phone session: the manual checklist in `test-plan.md` on the **release** APK.
- [ ] `./gradlew connectedDebugAndroidTest` and `testDebugUnitTest -Probolectric=true` on your computer.
- [ ] Native-speaker review of the Bengali strings.
- [ ] Bump `versionCode` for every upload.

---

## Release notes: 0.1.0 (first test build)

For the Play "What's new" box (under 500 characters):

> First test release. Scan paper documents with your camera: automatic edge detection with auto-capture, manual crop, perspective correction and four clean-up filters. Scan many pages in one go, reorder or retake them, and save or share as a PDF or JPG, with no watermark and no ads. Optional account with end-to-end encrypted backup and restore. English and Bengali interface.

Internal notes:
- Not yet included: text recognition (OCR), search, folders, PDF page editing.
- Fixed before release: a sync bug that could delete pages after a rename on another phone (BUG-001), and false page outlines on cluttered backgrounds (BUG-002).
- Known limits: the camera is portrait only; the Bengali translation is a draft.
