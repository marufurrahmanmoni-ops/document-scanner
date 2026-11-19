import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  belongsTo, needsAcknowledge, sha256Hex, toEntitlement, tokenFromRtdn, validateVerifyInput, type PlaySubscription,
} from '../src/entitlement';

const NOW = Date.parse('2026-11-19T12:00:00Z');
const PRODUCTS = ['premium_monthly', 'premium_yearly'];
const sub = (over: Partial<PlaySubscription> = {}): PlaySubscription => ({
  subscriptionState: 'SUBSCRIPTION_STATE_ACTIVE',
  acknowledgementState: 'ACKNOWLEDGEMENT_STATE_PENDING',
  lineItems: [{ productId: 'premium_monthly', expiryTime: '2026-12-19T12:00:00Z', autoRenewingPlan: { autoRenewEnabled: true } }],
  externalAccountIdentifiers: { obfuscatedExternalAccountId: sha256Hex('alice') },
  ...over,
});

test('active subscription grants access', () => {
  const e = toEntitlement(sub(), PRODUCTS, NOW);
  assert.equal(e.active, true);
  assert.equal(e.productId, 'premium_monthly');
  assert.equal(e.autoRenewing, true);
  assert.equal(e.expiresAtMillis, Date.parse('2026-12-19T12:00:00Z'));
});

test('cancelled keeps access until expiry, then loses it', () => {
  const s = sub({ subscriptionState: 'SUBSCRIPTION_STATE_CANCELED' });
  assert.equal(toEntitlement(s, PRODUCTS, NOW).active, true);
  assert.equal(toEntitlement(s, PRODUCTS, Date.parse('2026-12-20T00:00:00Z')).active, false);
});

test('grace period keeps access; hold, paused, expired and pending do not', () => {
  assert.equal(toEntitlement(sub({ subscriptionState: 'SUBSCRIPTION_STATE_IN_GRACE_PERIOD' }), PRODUCTS, NOW).active, true);
  for (const st of ['SUBSCRIPTION_STATE_ON_HOLD', 'SUBSCRIPTION_STATE_PAUSED', 'SUBSCRIPTION_STATE_EXPIRED', 'SUBSCRIPTION_STATE_PENDING']) {
    assert.equal(toEntitlement(sub({ subscriptionState: st }), PRODUCTS, NOW).active, false, st);
  }
});

test('products from another app never grant access', () => {
  const s = sub({ lineItems: [{ productId: 'someone_elses_product', expiryTime: '2030-01-01T00:00:00Z' }] });
  assert.equal(toEntitlement(s, PRODUCTS, NOW).active, false);
});

test('purchase is bound to the account that bought it', () => {
  assert.equal(belongsTo(sub(), 'alice'), true);
  assert.equal(belongsTo(sub(), 'bob'), false);
  assert.equal(belongsTo(sub({ externalAccountIdentifiers: null }), 'alice'), false);
});

test('acknowledge only pending, entitled purchases', () => {
  assert.equal(needsAcknowledge(sub()), true);
  assert.equal(needsAcknowledge(sub({ acknowledgementState: 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED' })), false);
  assert.equal(needsAcknowledge(sub({ subscriptionState: 'SUBSCRIPTION_STATE_PENDING' })), false);
});

test('callable input is validated', () => {
  assert.equal(validateVerifyInput({ purchaseToken: 'x'.repeat(20), productId: 'premium_monthly' }, PRODUCTS), null);
  assert.notEqual(validateVerifyInput(null, PRODUCTS), null);
  assert.notEqual(validateVerifyInput({ purchaseToken: 'short', productId: 'premium_monthly' }, PRODUCTS), null);
  assert.notEqual(validateVerifyInput({ purchaseToken: 'x'.repeat(20), productId: 'free_money' }, PRODUCTS), null);
});

test('RTDN payloads for other apps are ignored', () => {
  const msg = { packageName: 'com.docscanner.app', subscriptionNotification: { purchaseToken: 'tok' } };
  assert.equal(tokenFromRtdn(msg, 'com.docscanner.app'), 'tok');
  assert.equal(tokenFromRtdn(msg, 'com.other.app'), null);
  assert.equal(tokenFromRtdn({ packageName: 'com.docscanner.app', testNotification: {} }, 'com.docscanner.app'), null);
});
