// Pure entitlement logic: no Firebase, no network. Unit-tested in test/entitlement.test.ts.
import { createHash } from 'node:crypto';

/** The subset of androidpublisher v3 SubscriptionPurchaseV2 this app relies on. */
export interface PlaySubscription {
  subscriptionState?: string | null;
  acknowledgementState?: string | null;
  lineItems?: Array<{
    productId?: string | null;
    expiryTime?: string | null;
    autoRenewingPlan?: { autoRenewEnabled?: boolean | null } | null;
  }> | null;
  externalAccountIdentifiers?: { obfuscatedExternalAccountId?: string | null } | null;
  linkedPurchaseToken?: string | null;
  testPurchase?: object | null;
}

/** What the app reads at users/{uid}/entitlements/current. Written only by these functions. */
export interface Entitlement {
  active: boolean;
  productId: string | null;
  state: string;
  expiresAtMillis: number | null;
  autoRenewing: boolean;
  test: boolean;
}

/** States in which the user has paid for access right now. CANCELED = auto-renew off, still valid until expiry. */
const ENTITLED_STATES = new Set([
  'SUBSCRIPTION_STATE_ACTIVE',
  'SUBSCRIPTION_STATE_IN_GRACE_PERIOD',
  'SUBSCRIPTION_STATE_CANCELED',
]);

export function sha256Hex(s: string): string {
  return createHash('sha256').update(s, 'utf8').digest('hex');
}

/** The purchase must carry the hashed uid the app set at checkout, so one token cannot unlock two accounts. */
export function belongsTo(sub: PlaySubscription, uid: string): boolean {
  return sub.externalAccountIdentifiers?.obfuscatedExternalAccountId === sha256Hex(uid);
}

export function toEntitlement(sub: PlaySubscription, allowedProducts: string[], nowMillis: number): Entitlement {
  const items = (sub.lineItems ?? []).filter((li) => li.productId && allowedProducts.includes(li.productId));
  // Latest expiry among this app's products.
  const best = items
    .map((li) => ({ li, exp: li.expiryTime ? Date.parse(li.expiryTime) : NaN }))
    .filter((x) => !Number.isNaN(x.exp))
    .sort((a, b) => b.exp - a.exp)[0];
  const state = sub.subscriptionState ?? 'SUBSCRIPTION_STATE_UNSPECIFIED';
  const expires = best ? best.exp : null;
  return {
    active: !!best && ENTITLED_STATES.has(state) && expires !== null && expires > nowMillis,
    productId: best?.li.productId ?? null,
    state,
    expiresAtMillis: expires,
    autoRenewing: best?.li.autoRenewingPlan?.autoRenewEnabled === true,
    test: !!sub.testPurchase,
  };
}

export function needsAcknowledge(sub: PlaySubscription): boolean {
  return sub.acknowledgementState === 'ACKNOWLEDGEMENT_STATE_PENDING'
    && ENTITLED_STATES.has(sub.subscriptionState ?? '');
}

/** Validates callable input; returns an error message or null. */
export function validateVerifyInput(data: unknown, allowedProducts: string[]): string | null {
  if (typeof data !== 'object' || data === null) return 'invalid request';
  const d = data as Record<string, unknown>;
  if (typeof d.purchaseToken !== 'string' || d.purchaseToken.length < 10 || d.purchaseToken.length > 2048) return 'invalid purchaseToken';
  if (typeof d.productId !== 'string' || !allowedProducts.includes(d.productId)) return 'unknown productId';
  return null;
}

/** Real-time developer notification payload (Pub/Sub, base64 JSON) → the token to refresh, or null. */
export function tokenFromRtdn(json: unknown, packageName: string): string | null {
  if (typeof json !== 'object' || json === null) return null;
  const j = json as { packageName?: string; subscriptionNotification?: { purchaseToken?: string } };
  if (j.packageName !== packageName) return null;
  const t = j.subscriptionNotification?.purchaseToken;
  return typeof t === 'string' && t.length > 0 ? t : null;
}
