// Firestore security rules tests. Run: npm run test:rules  (starts the emulator, runs node --test)
import { test, before, after, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { doc, setDoc, getDoc, updateDoc, deleteDoc, serverTimestamp, Bytes, collection, getDocs } from 'firebase/firestore';

let env;
const ALICE = 'alice', BOB = 'bob';
const verified = { email: 'a@example.com', email_verified: true, firebase: { sign_in_provider: 'password' } };
const unverified = { email: 'u@example.com', email_verified: false, firebase: { sign_in_provider: 'password' } };
const google = { email: 'g@example.com', email_verified: false, firebase: { sign_in_provider: 'google.com' } };

before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-docscanner',
    firestore: { rules: readFileSync('firestore.rules', 'utf8'), host: '127.0.0.1', port: 8080 },
  });
});
after(async () => env.cleanup());
beforeEach(async () => env.clearFirestore());

const db = (uid, token = verified) => uid ? env.authenticatedContext(uid, token).firestore() : env.unauthenticatedContext().firestore();
const rec = (over = {}) => ({
  entity: 'document', entityId: 'doc1', rev: 1, deviceId: 'dev-a', deleted: false, clientUpdatedAt: 1,
  serverUpdatedAt: serverTimestamp(), payload: Bytes.fromUint8Array(new Uint8Array([1, 2, 3])), blobs: [], ...over,
});
const vault = (over = {}) => ({
  version: 1, salt: Bytes.fromUint8Array(new Uint8Array(16)), iterations: 310000,
  wrappedKey: Bytes.fromUint8Array(new Uint8Array(61)), updatedAt: serverTimestamp(), ...over,
});

test('owner can write and read own record', async () => {
  const ref = doc(db(ALICE), 'users/alice/records/document_doc1');
  await assertSucceeds(setDoc(ref, rec()));
  await assertSucceeds(getDoc(ref));
});

test('another user can neither read, write nor delete', async () => {
  await env.withSecurityRulesDisabled(async (c) => setDoc(doc(c.firestore(), 'users/alice/records/document_doc1'), rec({ serverUpdatedAt: new Date() })));
  const bob = db(BOB);
  await assertFails(getDoc(doc(bob, 'users/alice/records/document_doc1')));
  await assertFails(getDocs(collection(bob, 'users/alice/records')));
  await assertFails(setDoc(doc(bob, 'users/alice/records/document_doc2'), rec({ entityId: 'doc2' })));
  await assertFails(deleteDoc(doc(bob, 'users/alice/records/document_doc1')));
  await assertFails(getDoc(doc(bob, 'users/alice')));
});

test('signed-out users get nothing', async () => {
  await assertFails(getDoc(doc(db(null), 'users/alice/records/document_doc1')));
  await assertFails(setDoc(doc(db(null), 'users/alice/records/document_doc1'), rec()));
});

test('unverified password accounts cannot upload; Google accounts can', async () => {
  await assertFails(setDoc(doc(db(ALICE, unverified), 'users/alice/records/document_doc1'), rec()));
  await assertSucceeds(setDoc(doc(db(ALICE, google), 'users/alice/records/document_doc1'), rec()));
});

test('rev must increase by exactly one (no lost updates)', async () => {
  const ref = doc(db(ALICE), 'users/alice/records/document_doc1');
  await assertFails(setDoc(ref, rec({ rev: 2 })));            // create must start at 1
  await assertSucceeds(setDoc(ref, rec()));
  await assertFails(setDoc(ref, rec({ rev: 1 })));            // stale writer
  await assertFails(setDoc(ref, rec({ rev: 3 })));            // skipping
  await assertSucceeds(setDoc(ref, rec({ rev: 2 })));
});

test('record shape is enforced', async () => {
  const d = db(ALICE);
  await assertFails(setDoc(doc(d, 'users/alice/records/document_doc1'), rec({ entity: 'folder' })));          // unknown entity
  await assertFails(setDoc(doc(d, 'users/alice/records/page_doc1'), rec()));                                 // id mismatch
  await assertFails(setDoc(doc(d, 'users/alice/records/document_doc1'), rec({ title: 'plain text leak' }))); // extra field
  await assertFails(setDoc(doc(d, 'users/alice/records/document_doc1'), rec({ payload: 'not bytes' })));
  await assertFails(setDoc(doc(d, 'users/alice/records/document_doc1'), rec({ serverUpdatedAt: new Date(0) }))); // forged time
  await assertFails(setDoc(doc(d, 'users/alice/records/document_doc1'),
    rec({ payload: Bytes.fromUint8Array(new Uint8Array(70000)) })));                                         // too big
});

test('entity cannot be changed on update', async () => {
  const ref = doc(db(ALICE), 'users/alice/records/document_doc1');
  await assertSucceeds(setDoc(ref, rec()));
  await assertFails(setDoc(ref, rec({ rev: 2, entity: 'page' })));
});

test('vault: only the wrapped key, only by its owner', async () => {
  const ref = doc(db(ALICE), 'users/alice/vault/key');
  await assertSucceeds(setDoc(ref, vault()));
  await assertSucceeds(getDoc(ref));
  await assertFails(getDoc(doc(db(BOB), 'users/alice/vault/key')));
  await assertFails(setDoc(doc(db(ALICE), 'users/alice/vault/other'), vault()));
  await assertFails(setDoc(ref, vault({ iterations: 1000 })));          // weak KDF refused
  await assertFails(setDoc(ref, vault({ passphrase: 'never stored' }))); // no extra fields
});

test('entitlements are read-only for the user', async () => {
  await env.withSecurityRulesDisabled(async (c) => setDoc(doc(c.firestore(), 'users/alice/entitlements/current'), { active: true }));
  await assertSucceeds(getDoc(doc(db(ALICE), 'users/alice/entitlements/current')));
  await assertFails(setDoc(doc(db(ALICE), 'users/alice/entitlements/current'), { active: true, expiresAt: 9e15 }));
  await assertFails(getDoc(doc(db(BOB), 'users/alice/entitlements/current')));
});

test('profile: only name and timestamps', async () => {
  const ref = doc(db(ALICE), 'users/alice');
  await assertSucceeds(setDoc(ref, { displayName: 'Alice', createdAt: serverTimestamp(), updatedAt: serverTimestamp() }));
  await assertFails(updateDoc(ref, { isAdmin: true, updatedAt: serverTimestamp() }));
  await assertFails(setDoc(ref, { displayName: 'x'.repeat(81), updatedAt: serverTimestamp() }));
});

test('nothing outside users/{uid} is reachable', async () => {
  await assertFails(setDoc(doc(db(ALICE), 'public/anything'), { a: 1 }));
  await assertFails(getDoc(doc(db(ALICE), 'public/anything')));
});
