// DocScanner Cloud Functions (replica/backend.md "Cloud Functions").
// Only what must run on a server: verifying Google Play subscriptions and cleaning up deleted accounts.
// Document contents never reach these functions (they are end-to-end encrypted on the phone).
import { initializeApp } from 'firebase-admin/app';
import { getFirestore, FieldValue, Timestamp } from 'firebase-admin/firestore';
import { getStorage } from 'firebase-admin/storage';
import { onCall, HttpsError } from 'firebase-functions/v2/https';
import { onMessagePublished } from 'firebase-functions/v2/pubsub';
import { setGlobalOptions, logger } from 'firebase-functions/v2';
import { defineString } from 'firebase-functions/params';
import * as functionsV1 from 'firebase-functions/v1';
import { androidpublisher, auth as gauth } from '@googleapis/androidpublisher';
import {
  belongsTo, needsAcknowledge, sha256Hex, toEntitlement, tokenFromRtdn, validateVerifyInput, type PlaySubscription,
} from './entitlement';

initializeApp();

// ---- configuration (set in functions/.env, see .env.example; never commit real values)
const PACKAGE_NAME = defineString('PLAY_PACKAGE_NAME', { default: 'io.github.marufurrahmanmoni_ops.docscanner' });
const SUBSCRIPTION_IDS = defineString('PLAY_SUBSCRIPTION_IDS', { description: 'Comma-separated Play subscription product ids' });
const REGION = defineString('FUNCTIONS_REGION', { default: 'us-central1' });

setGlobalOptions({ region: REGION, maxInstances: 10 });

/** Pub/Sub topic that Play Console sends real-time notifications to (fixed at deploy time). */
const RTDN_TOPIC = 'play-billing';

const allowedProducts = () => SUBSCRIPTION_IDS.value().split(',').map((s) => s.trim()).filter(Boolean);

/** Play Developer API client using the function's service account (must be linked in Play Console). */
function publisher() {
  const auth = new gauth.GoogleAuth({ scopes: ['https://www.googleapis.com/auth/androidpublisher'] });
  return androidpublisher({ version: 'v3', auth });
}

async function fetchSubscription(token: string): Promise<PlaySubscription> {
  const res = await publisher().purchases.subscriptionsv2.get({ packageName: PACKAGE_NAME.value(), token });
  return res.data as PlaySubscription;
}

/** Writes the entitlement and the token→uid link used by real-time notifications. Idempotent. */
async function store(uid: string, token: string, sub: PlaySubscription): Promise<void> {
  const ent = toEntitlement(sub, allowedProducts(), Date.now());
  const db = getFirestore();
  const batch = db.batch();
  batch.set(db.doc(`users/${uid}/entitlements/current`), {
    active: ent.active,
    productId: ent.productId,
    state: ent.state,
    expiresAt: ent.expiresAtMillis ? Timestamp.fromMillis(ent.expiresAtMillis) : null,
    autoRenewing: ent.autoRenewing,
    test: ent.test,
    updatedAt: FieldValue.serverTimestamp(),
  });
  // Server-only collection (no client rule allows it): maps a purchase token hash to its owner.
  batch.set(db.doc(`purchaseTokens/${sha256Hex(token)}`), { uid, updatedAt: FieldValue.serverTimestamp() });
  if (sub.linkedPurchaseToken) {
    // An upgrade/downgrade/resubscribe replaces the old token: retire it.
    batch.delete(db.doc(`purchaseTokens/${sha256Hex(sub.linkedPurchaseToken)}`));
  }
  await batch.commit();
}

/**
 * Called by the app after a purchase (and on start for unacknowledged purchases).
 * Verifies the token with Google Play, checks it belongs to this user, acknowledges it, stores the entitlement.
 */
export const verifyPurchase = onCall({ enforceAppCheck: true }, async (req) => {
  if (!req.auth) throw new HttpsError('unauthenticated', 'sign in first');
  const problem = validateVerifyInput(req.data, allowedProducts());
  if (problem) throw new HttpsError('invalid-argument', problem);
  const { purchaseToken, productId } = req.data as { purchaseToken: string; productId: string };

  let sub: PlaySubscription;
  try {
    sub = await fetchSubscription(purchaseToken);
  } catch (e) {
    logger.warn('Play lookup failed', { uid: req.auth.uid, err: String(e) });
    throw new HttpsError('unavailable', 'could not reach Google Play, try again');
  }
  if (!belongsTo(sub, req.auth.uid)) throw new HttpsError('permission-denied', 'purchase belongs to another account');

  if (needsAcknowledge(sub)) {
    // Unacknowledged purchases are refunded by Google after 3 days; acknowledge only after verifying.
    await publisher().purchases.subscriptions.acknowledge({
      packageName: PACKAGE_NAME.value(), subscriptionId: productId, token: purchaseToken, requestBody: {},
    });
  }
  await store(req.auth.uid, purchaseToken, sub);
  const ent = toEntitlement(sub, allowedProducts(), Date.now());
  return { active: ent.active, expiresAtMillis: ent.expiresAtMillis, state: ent.state };
});

/** Google Play real-time developer notifications: renewals, cancellations, refunds, holds. */
export const playNotifications = onMessagePublished({ topic: RTDN_TOPIC }, async (event) => {
  const token = tokenFromRtdn(event.data.message.json, PACKAGE_NAME.value());
  if (!token) return; // test notification or another app
  const link = await getFirestore().doc(`purchaseTokens/${sha256Hex(token)}`).get();
  const uid = link.get('uid') as string | undefined;
  if (!uid) {
    logger.info('notification for a token not yet verified by the app; ignoring');
    return;
  }
  await store(uid, token, await fetchSubscription(token)); // throws → Pub/Sub retries
});

/**
 * Safety net for account deletion: the app deletes cloud data itself before deleting the account, but if that was
 * interrupted, this removes everything that is left. Uses the v1 auth trigger (there is no v2 equivalent).
 */
export const cleanupDeletedUser = functionsV1.auth.user().onDelete(async (user) => {
  const db = getFirestore();
  await db.recursiveDelete(db.doc(`users/${user.uid}`));
  await getStorage().bucket().deleteFiles({ prefix: `users/${user.uid}/` });
  const tokens = await db.collection('purchaseTokens').where('uid', '==', user.uid).get();
  await Promise.all(tokens.docs.map((d) => d.ref.delete()));
  logger.info('deleted all data for removed account');
});
