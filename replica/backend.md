# Backend: DocScanner on Firebase

Status as of 2026-11-19. Everything below is **built, compiled and unit- or emulator-tested**, but **not yet run against
a real Firebase project or Google Play**. That needs the project owner's accounts (see "What you must create").

| layer | choice | code |
| --- | --- | --- |
| Auth | Firebase Authentication: email + password (verification, reset) and Google (Credential Manager) | `cloud/AuthRepository.kt`, `feature/account/AccountScreen.kt` |
| Profile | Firestore `users/{uid}`: display name + timestamps only | `AuthRepository.writeProfile` |
| Backup + sync | Firestore records + Cloud Storage blobs, **end-to-end encrypted** with a passphrase | `cloud/sync/*`, `feature/account/BackupSection.kt` |
| Background | WorkManager: debounced one-time sync after edits + hourly periodic, exponential backoff | `cloud/sync/SyncWorker.kt` |
| Subscriptions | Google Play Billing 8 on the phone; verification, acknowledgement and entitlement in Cloud Functions | `cloud/BillingRepository.kt`, `firebase/functions/src/*` |
| App Check | Play Integrity (release), debug provider (debug); `verifyPurchase` enforces App Check | `src/{debug,release}/…/AppCheckInstaller.kt` |
| Account deletion | in-app, re-authenticated, deletes cloud data then the auth user. Server trigger cleans up leftovers | `cloud/AccountDeletion.kt`, `cleanupDeletedUser` |
| Rules | `firebase/firestore.rules`, `firebase/storage.rules` | 11 emulator tests in `firebase/tests/` |

## Consent and privacy

- **Backup is off by default.** Nothing about a document is uploaded until the user signs in, reads the consent text,
  ticks "I agree to upload encrypted copies of my documents", and sets or enters the backup passphrase. The consent time is
  recorded (`SyncSettings.consentAt`).
- Pausing backup stops all uploads at once and keeps the cloud copy. Deleting the account removes it.
- Without backup, the only network traffic is sign-in and subscription calls the user starts.
- Password accounts must confirm their email before anything is uploaded. Both the app and the Security Rules enforce this.

## Encryption model (end-to-end)

- A random 256-bit **backup key** encrypts everything that leaves the phone (AES-256-GCM).
- The cloud holds only the key **wrapped** with a key derived from the passphrase (PBKDF2-HMAC-SHA256, 310 000 iterations,
  16-byte salt), at `users/{uid}/vault/key`. The rules reject < 100 000 iterations and any extra field.
- On the phone, the unlocked key is kept wrapped by the Android Keystore (Tink AEAD), so background sync needs no prompt.
- Subkeys come from HMAC-SHA256 expansion: one for encryption, one for **object names** (`HMAC(sha256)`), so the server
  can't test whether a known file is stored.
- Every ciphertext binds `uid/record/{id}` or `uid/blob/{sha}` as associated data, so blobs can't be swapped or replayed.
- Titles and page recipes are encrypted too. The server sees only ids, entity type, revision, device id, the deleted flag and
  timestamps.
- **A forgotten passphrase means the backup can't be restored.** The UI says this before creation. Changing the
  passphrase re-wraps the same key, so all backups stay readable.
- Tests: `VaultCryptoTest` covers right/wrong passphrase, re-wrap, tamper and swap detection, foreign key, and name hiding.

## Sync design

```
users/{uid}/records/{entity}_{id}  { entity, entityId, rev, deviceId, deleted, clientUpdatedAt,
                                     serverUpdatedAt (server time), payload: AES-GCM(JSON), blobs: [name] }
users/{uid}/blobs/{name}           AES-GCM(original JPEG)
```

- **Push:** drains `sync_outbox` (written in the same transaction as every local edit since v1). Each record is written in a
  Firestore transaction that reads the server `rev`. If it differs from the last agreed `rev` (`sync_shadow`), the
  phone decrypts the server copy and **3-way merges** it. The rules also require `rev == old + 1`, so a stale writer
  is rejected server-side as well.
- **Pull:** queries records with `serverUpdatedAt` after the cursor (2 s overlap), documents before pages, and applies them
  straight to Room without echoing into the outbox. A page that arrives before its document holds the cursor back and
  is applied in a second pass.
- **Only originals are uploaded.** Processed images and thumbnails are rebuilt from original + recipe on each phone.
  Downloaded blobs are checked against their SHA-256.
- **Restore** on a new phone: sign in → enter passphrase → everything is pulled. "Restore all" re-pulls from the start.
- **Retry:** WorkManager exponential backoff from 30 s, up to 8 attempts, only for transient errors (network, UNAVAILABLE,
  ABORTED, Storage retry). Firebase Storage also retries internally. A failed record stays in the outbox
  (`attempts`, `last_error`) and is never lost.
- **Wi-Fi only** is on by default.

### Conflict handling (`SyncMerge`, 6 tests)

- A field changed on one phone only takes that phone's value, so edits to different fields both survive.
- A field changed on both phones: the newer edit wins (tie broken by device id, so both phones converge), and it is
  counted. The account screen then tells the user "kept the newer version".
- **An edit beats a concurrent delete**, so the document is kept. Deletes never silently destroy edits.

## Security Rules (`firebase/firestore.rules`, `firebase/storage.rules`)

- Everything lives under `users/{uid}`, readable and writable only by that uid. Everything else is denied. **No public access.**
- Records: field allow-list, entity enum, id format, payload ≤ 64 KB bytes, `serverUpdatedAt == request.time`,
  `rev` +1. Entity and id can't be changed on update.
- Vault: single doc `key`, fixed shape, KDF floor. Profile: name ≤ 80 characters + timestamps. Entitlements: **read-only for
  the user**, written only by Cloud Functions.
- Storage: own folder only, name must be 64 hex characters, `application/octet-stream`, under 60 MB, **no overwrite**, list only
  your own folder.
- **Tested:** 11 Firestore rule tests pass on the Firestore emulator (`cd firebase && npm run test:rules`). A negative control
  (opening reads to everyone) made 2 of them fail, as it should.
- **Not tested:** Storage rules. The Storage emulator's rules runtime downloads from `storage.googleapis.com`, which was
  blocked in the build workspace. Run `npx firebase emulators:exec --only storage …` somewhere it is reachable before launch.

## Subscriptions (Google Play Billing)

1. The app loads the product (`PLAY_SUBSCRIPTION_ID`) and opens Google Play's own purchase sheet. The purchase carries
   `obfuscatedAccountId = sha256(uid)`.
2. The purchase token goes to the callable **`verifyPurchase`** (App Check enforced, sign-in required, input validated). The function
   calls the Play Developer API `purchases.subscriptionsv2.get`, rejects tokens bound to another uid, **acknowledges**
   (unacknowledged purchases are refunded after 3 days), and writes `users/{uid}/entitlements/current`.
3. **`playNotifications`** receives Real-time Developer Notifications on Pub/Sub topic **`play-billing`** and refreshes the
   entitlement on renewals, cancellations, grace periods, holds and refunds. Purchase tokens are mapped to users through the
   server-only collection `purchaseTokens/{sha256(token)}`.
4. Entitled states: ACTIVE, IN_GRACE_PERIOD, and CANCELED until expiry. ON_HOLD, PAUSED, EXPIRED and PENDING are not entitled.
   There are 8 tests in `firebase/functions/test/`.
5. Cancelling is one tap: "Manage or cancel in Google Play" opens Play's subscription centre.
6. **Open product decision:** what premium unlocks. The entitlement is available in the app (`BillingRepository.observeEntitlement`),
   but nothing is gated yet, because no feature has been chosen as paid. Gate on the server-written entitlement, never on the client.

## Account deletion

In app: Account → Delete account. The user types DELETE and re-authenticates (password or Google). Then the app deletes
all Storage blobs, records, vault and profile, then the auth user, then stops sync and forgets the key. Ticking a box also erases
the documents on the phone. The UI tells users a Play subscription must be cancelled in Play, because Google bills it, not us.
Safety net: the `cleanupDeletedUser` auth trigger recursively deletes `users/{uid}`, the Storage prefix and purchase-token
links if the client was interrupted.

## Configuration and environment variables

The app reads its config from **`local.properties`** (git-ignored) or **environment variables** of the same name (CI).
Template: `local.properties.example`. **If the values are missing, the build still succeeds with cloud features off.** The
account screen then says so, and no button pretends to work.

| variable | where it comes from | required for |
| --- | --- | --- |
| `FIREBASE_API_KEY` | Firebase console → Project settings → Android app | all cloud features |
| `FIREBASE_APP_ID` | same (`1:…:android:…`) | all cloud features |
| `FIREBASE_PROJECT_ID` | same | all cloud features |
| `FIREBASE_STORAGE_BUCKET` | same (`<project>.firebasestorage.app`) | backup |
| `GOOGLE_WEB_CLIENT_ID` | Authentication → Sign-in method → Google → Web client ID | Google sign-in |
| `FUNCTIONS_REGION` | where you deploy functions (default `us-central1`) | subscriptions |
| `PLAY_SUBSCRIPTION_ID` | Play Console → Monetize → Subscriptions → product id | subscription UI |

Cloud Functions read **`firebase/functions/.env`** (git-ignored). Template: `.env.example`.

| variable | meaning |
| --- | --- |
| `PLAY_PACKAGE_NAME` | `com.docscanner.app` (or the final application id after `/replica-brand`) |
| `PLAY_SUBSCRIPTION_IDS` | comma-separated product ids that grant premium |
| `FUNCTIONS_REGION` | must match the app's `FUNCTIONS_REGION` |

Firebase client config is not secret by design. It is protected by Security Rules and App Check. **No service-account key
is stored anywhere:** functions use their runtime service account.

## What you must create (Claude cannot create accounts or keys)

1. **Firebase project** (console.firebase.google.com). Add an Android app with package `com.docscanner.app` and copy the values
   above into `local.properties`. Register the **SHA-1 and SHA-256** of the debug keystore and the Play app-signing key (needed for
   Google sign-in and App Check).
2. **Authentication:** enable Email/Password and Google. Customise the verification email sender and template in your own words.
3. **Firestore** (production mode) and **Cloud Storage**. Deploy rules with `cd firebase && npx firebase deploy --only firestore:rules,storage`.
4. **App Check:** register the Play Integrity provider (needs the app in Play Console). Add the debug token printed by debug builds
   (logcat tag `DebugAppCheckProvider`). Turn on **enforcement** for Firestore, Storage and Functions after testing.
5. **Blaze plan** (pay-as-you-go), which Cloud Functions require. Then `cd firebase/functions && npm ci && cp .env.example .env`, fill it in,
   and run `npx firebase deploy --only functions`.
6. **Google Play Console:** create the app and the subscription product(s). Link the functions' service account
   (`<project-id>@appspot.gserviceaccount.com`, or the compute default account for v2) under **Users and permissions** with
   "View financial data" and "Manage orders and subscriptions". Create the Pub/Sub topic **`play-billing`**, grant
   `google-play-developer-notifications@system.gserviceaccount.com` Publisher on it, and enter it under Monetization setup →
   Real-time developer notifications. Use **license testers** for test purchases.
7. **Budget alerts** in Google Cloud billing.

## Security checklist

- [x] secrets only in git-ignored files or env vars; `local.properties`, `functions/.env` ignored (checked with `git check-ignore`)
- [x] input validated on the server: `validateVerifyInput`, plus Security Rules field allow-lists and types
- [x] authorisation on every read and write, **tested with a second user** (emulator tests: Bob gets nothing of Alice's)
- [x] App Check enforced on the callable. Rules and Storage enforcement to be switched on in the console (step 4)
- [x] rate limits: Firebase Auth has built-in sign-in, sign-up and email throttling (`TOO_MANY_ATTEMPTS` handled in the UI); functions `maxInstances: 10`
- [x] webhooks: Play RTDN arrives through Pub/Sub (Google-authenticated), and each token is re-verified with the Play API before use
- [x] uploads: size and type limits, own folder only, immutable, content end-to-end encrypted
- [x] no user data in URLs or logs: log lines carry no document data, titles or tokens; Storage names are HMACs
- [x] dependencies audited: `npm audit --omit=dev` in `firebase/functions` found 8 moderate (one transitive `uuid` issue). Fixed by moving to firebase-admin 14 + firebase-functions 7 → **0 vulnerabilities**, tsc and tests still pass
- [ ] privacy policy listing processors: **Google Firebase** (Auth, Firestore, Storage, Functions, App Check) and **Google Play Billing**.
      Write it in `/replica-launch`
- [ ] Storage rules emulator test (blocked host, see above)
- [ ] end-to-end test on two real phones against a real project

## Known limits

- Folders and tags aren't synced yet, because they don't exist in the app yet (Phase 7). The record format already
  supports new entity types; add them to the rules' `entity` enum.
- One account per phone at a time. Signing into a different account and turning backup on uploads **this phone's**
  documents to that account. The consent text says "every saved document on this phone".
- Realtime listeners aren't used. Other phones' changes arrive on the next sync (after an edit, hourly, or "Sync now").
