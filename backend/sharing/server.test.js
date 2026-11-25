import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createSharingServer } from './server.js';
import { protocolDatabase } from './storage-test-support.js';

async function fixture(options) {
  const bridge = process.env.SHARING_TEST_STORAGE === 'turso' ? protocolDatabase(options?.databasePath) : null;
  const service = createSharingServer({ ...options, ...(bridge ? { storage: await bridge.storage() } : {}) });
  await new Promise(resolve => service.server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${service.server.address().port}`;
  async function call(path, token, body) {
    const response = await fetch(base + path, { method: body === undefined ? 'GET' : 'POST',
      headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body) });
    return { status: response.status, data: await response.json() };
  }
  const register = async email => {
    const result = await call('/v1/auth/register', null, { name: email.split('@')[0], email, password: 'correct123' });
    assert.equal(result.status, 201); return result.data;
  };
  return { ...service, close: async () => { await service.close(); bridge?.close(); }, call, register };
}
const stock = quantity => ({ name: 'Eggs', quantity, price: 2, category: 'Food & Beverage', subcategory: 'Dairy', expiryDate: Date.now() + 86400000, threshold: 1, purchasedDate: Date.now() });
const mutation = (device, collection, id, data, extra = {}) => ({ mutationId: `${device}:${collection}:${id}`, collection, id, data, baseVersion: 0, deleted: false, ...extra });

test('inventory units and storage sync with purchase units and stay compatible with older clients', async () => {
  const f = await fixture();
  try {
    const a = await f.register('unit-a@example.com'), b = await f.register('unit-b@example.com');
    const home = (await f.call('/v1/households', a.token, { name: 'Unit home' })).data.household;
    await f.call('/v1/households/join', b.token, { code: home.joinCode });
    const url = `/v1/households/${home.id}/sync`;
    const first = await f.call(url, a.token, { operations: [
      mutation('unit', 'inventory', 'eggs', { ...stock(12), price: 10 / 12, unit: 'pcs', storageLocation: 'Fridge' }),
      mutation('unit', 'logs', 'purchase', { itemId: 'eggs', itemName: 'Eggs', category: 'Food & Beverage',
        changeType: 'ADD', amountChanged: 12, unitPriceAtChange: 10 / 12, timestamp: 1000, unit: 'pcs' })] });
    assert.equal(first.status, 200);
    const seen = (await f.call(url, b.token, { operations: [] })).data;
    assert.equal(seen.records.find(r => r.id === 'eggs').data.storageLocation, 'Fridge');
    assert.equal(seen.records.find(r => r.id === 'eggs').data.unit, 'pcs');
    const purchase = seen.records.find(r => r.id === 'purchase').data;
    assert.equal(purchase.unit, 'pcs'); assert.ok(Math.abs(purchase.amountChanged * purchase.unitPriceAtChange - 10) < 1e-8);
    const legacy = await f.call(url, b.token, { operations: [mutation('legacy', 'inventory', 'eggs', { quantity: 11 }, { baseVersion: 1, quantityDelta: -1 })] });
    const eggs = legacy.data.records.find(r => r.id === 'eggs').data;
    assert.equal(eggs.quantity, 11); assert.equal(eggs.storageLocation, 'Fridge'); assert.equal(eggs.unit, 'pcs');
    const oldCreate = await f.call(url, a.token, { operations: [mutation('old', 'inventory', 'legacy-item', stock(1))] });
    assert.equal(oldCreate.status, 200);
    const invalid = await f.call(url, b.token, { operations: [mutation('bad', 'inventory', 'eggs', { unit: 'password' }, { baseVersion: 2 })] });
    assert.equal(invalid.status, 400);
    assert.equal((await f.call('/health')).data.inventoryUnits, true);
  } finally { await f.close(); }
});

test('central accounts normalize email, verify passwords, and revoke logged-out tokens', async () => {
  const f = await fixture();
  try {
    const alice = await f.register('ALICE@example.com');
    assert.equal(alice.user.email, 'alice@example.com');
    assert.deepEqual(Object.keys(alice.user).sort(), ['email', 'id', 'name']);
    assert.equal((await f.call('/v1/auth/register', null, { name: 'Duplicate', email: 'alice@example.com', password: 'correct123' })).status, 409);
    assert.equal((await f.call('/v1/auth/login', null, { email: 'alice@example.com', password: 'wrong123' })).status, 401);
    assert.equal((await f.call('/v1/auth/login', null, { email: 'missing@example.com', password: 'correct123' })).data.code, 'ACCOUNT_NOT_FOUND');
    const secondDevice = await f.call('/v1/auth/login', null, { email: ' ALICE@example.com ', password: 'correct123' });
    assert.equal(secondDevice.data.user.id, alice.user.id);
    assert.equal((await f.call('/v1/auth/logout', alice.token, {})).status, 200);
    assert.equal((await f.call('/v1/households', alice.token)).status, 401);
    assert.equal((await f.call('/v1/households', secondDevice.data.token)).status, 200);
  } finally { await f.close(); }
});

test('invite codes join across devices while invitations alone grant no shared access', async () => {
  const f = await fixture();
  try {
    const alice = await f.register('alice@example.com'), bob = await f.register('bob@example.com'), eve = await f.register('eve@example.com');
    const home = (await f.call('/v1/households', alice.token, { name: 'Family', description: 'Shared home' })).data.household;
    assert.match(home.joinCode, /^\d{6}$/);
    assert.equal((await f.call(`/v1/households/${home.id}/sync`, eve.token, { operations: [] })).status, 403);
    await f.call(`/v1/households/${home.id}/invitations`, alice.token, { email: 'bob@example.com' });
    assert.equal((await f.call(`/v1/households/${home.id}/sync`, bob.token, { operations: [] })).status, 403);
    const joined = await f.call('/v1/households/join', bob.token, { code: home.joinCode });
    assert.equal(joined.status, 200);
    assert.equal(joined.data.members.find(m => m.id === bob.user.id).role, 'Member');
    assert.equal((await f.call('/v1/households', bob.token)).data.households[0].household.id, home.id);
    assert.equal((await f.call(`/v1/households/${home.id}/members`, alice.token, { userId: bob.user.id })).status, 200);
    assert.equal((await f.call(`/v1/households/${home.id}/sync`, bob.token, { operations: [] })).status, 403);
  } finally { await f.close(); }
});

test('two devices share all collections and merge concurrent stock deltas without retry duplication', async () => {
  const f = await fixture();
  try {
    const a = await f.register('a@example.com'), b = await f.register('b@example.com');
    const home = (await f.call('/v1/households', a.token, { name: 'Home' })).data.household;
    await f.call('/v1/households/join', b.token, { code: home.joinCode });
    const url = `/v1/households/${home.id}/sync`;
    const initial = [mutation('a1', 'inventory', 'eggs', stock(5)),
      mutation('a2', 'shopping', 'plan', { itemName: 'Milk', category: 'Food & Beverage', subcategory: 'Dairy', quantityNeeded: 2, quantityLabel: '', estimatedUnitPrice: 3,
        addedBy: 'Manual', isChecked: false, sourceId: 'eggs', createdAt: 1000, updatedAt: 1000 }),
      mutation('a3', 'logs', 'purchase', { itemId: 'eggs', itemName: 'Eggs', category: 'Food & Beverage', changeType: 'ADD', amountChanged: 5, unitPriceAtChange: 2, timestamp: 1000 }),
      mutation('a4', 'budget', '2026-11', { monthYear: '2026-11', limitAmount: 500 })];
    assert.equal((await f.call(url, a.token, { operations: initial })).status, 200);
    const pulled = await f.call(url, b.token, { operations: [] });
    assert.equal(pulled.data.records.find(r => r.collection === 'budget').data.limitAmount, 500);
    const editB = mutation('b1', 'inventory', 'eggs', { quantity: 4 }, { baseVersion: 1, quantityDelta: -1 });
    assert.equal((await f.call(url, b.token, { operations: [editB] })).status, 200);
    const editA = mutation('a5', 'inventory', 'eggs', { quantity: 7 }, { baseVersion: 1, quantityDelta: 2 });
    const result = await f.call(url, a.token, { operations: [editA] });
    assert.equal(result.data.records.find(r => r.id === 'eggs').data.quantity, 6);
    const retry = await f.call(url, a.token, { operations: [editA] });
    assert.equal(retry.data.records.find(r => r.id === 'eggs').data.quantity, 6);
    const lastLimit = await f.call(url, b.token, { operations: [mutation('b2', 'budget', '2026-11', { limitAmount: 650 }, { baseVersion: 1 })] });
    assert.equal(lastLimit.data.records.find(r => r.collection === 'budget').data.limitAmount, 650);
    assert.equal(lastLimit.data.records.filter(r => r.collection === 'logs').reduce((sum, r) => sum + r.data.amountChanged * r.data.unitPriceAtChange, 0), 10);
  } finally { await f.close(); }
});

test('deletions win over stale edits and spending logs remain append-only', async () => {
  const f = await fixture();
  try {
    const a = await f.register('a@example.com');
    const home = (await f.call('/v1/households', a.token, { name: 'Home' })).data.household;
    const url = `/v1/households/${home.id}/sync`;
    await f.call(url, a.token, { operations: [mutation('one', 'inventory', 'eggs', stock(5)),
      mutation('two', 'logs', 'purchase', { itemId: 'eggs', itemName: 'Eggs', category: 'Food & Beverage', changeType: 'ADD', amountChanged: 5, unitPriceAtChange: 2, timestamp: 1000 })] });
    await f.call(url, a.token, { operations: [mutation('delete', 'inventory', 'eggs', null, { baseVersion: 1, deleted: true })] });
    const stale = await f.call(url, a.token, { operations: [mutation('stale', 'inventory', 'eggs', { quantity: 50 }, { baseVersion: 1, quantityDelta: 45 }),
      mutation('log-edit', 'logs', 'purchase', { amountChanged: 500 }, { baseVersion: 1 })] });
    assert.equal(stale.data.records.find(r => r.id === 'eggs').deleted, true);
    assert.equal(stale.data.records.find(r => r.id === 'purchase').data.amountChanged, 5);
  } finally { await f.close(); }
});

test('the shared API rejects personal payloads, cross-household references, and non-owner edits', async () => {
  const f = await fixture();
  try {
    const a = await f.register('a@example.com'), b = await f.register('b@example.com');
    const first = (await f.call('/v1/households', a.token, { name: 'First' })).data.household;
    const second = (await f.call('/v1/households', a.token, { name: 'Second' })).data.household;
    await f.call('/v1/households/join', b.token, { code: first.joinCode });
    await f.call(`/v1/households/${second.id}/sync`, a.token, { operations: [mutation('stock', 'inventory', 'other', stock(2))] });
    const url = `/v1/households/${first.id}/sync`;
    assert.equal((await f.call(url, a.token, { operations: [mutation('private', 'inventory', 'bad', { ...stock(2), householdId: null, userId: 'private-user' })] })).data.code, 'PRIVATE_DATA');
    assert.equal((await f.call(url, a.token, { operations: [mutation('unknown', 'inventory', 'bad', { ...stock(2), toString: 'unrecognized field' })] })).data.code, 'INVALID_RECORD');
    assert.equal((await f.call(url, a.token, { operations: [mutation('ref', 'logs', 'bad', { itemId: 'other', itemName: 'Other', category: 'Food & Beverage', changeType: 'ADD', amountChanged: 1, unitPriceAtChange: 1, timestamp: 1 })] })).data.code, 'INVALID_REFERENCE');
    const result = await f.call(url, b.token, { operations: [mutation('name', 'household', 'main', { name: 'Hijack' }, { baseVersion: 1 })] });
    assert.equal(result.data.household.name, 'First');
    assert.equal((await f.call(`/v1/households/${second.id}/sync`, b.token, { operations: [] })).status, 403);
  } finally { await f.close(); }
});

test('households, sessions, and idempotency survive server restart', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'household-sharing-')); let f;
  try {
    const databasePath = join(dir, 'state.sqlite');
    f = await fixture({ databasePath }); const a = await f.register('a@example.com');
    const home = (await f.call('/v1/households', a.token, { name: 'Persistent home' })).data.household;
    const op = mutation('persistent', 'inventory', 'eggs', stock(5));
    await f.call(`/v1/households/${home.id}/sync`, a.token, { operations: [op] });
    await f.close(); f = await fixture({ databasePath });
    const state = await f.call(`/v1/households/${home.id}/sync`, a.token, { operations: [op] });
    assert.equal(state.status, 200); assert.equal(state.data.records.find(r => r.id === 'eggs').data.quantity, 5);
  } finally { if (f) await f.close(); rmSync(dir, { recursive: true, force: true }); }
});


test('item author labels use authenticated names, preserve creators, and survive retries', async () => {
  const f = await fixture();
  try {
    const ben = await f.register('ben@example.test'), alice = await f.register('alice@example.test');
    const home = (await f.call('/v1/households', ben.token, { name: 'Authors' })).data.household;
    await f.call('/v1/households/join', alice.token, { code: home.joinCode });
    const sync = (token, operations) => f.call('/v1/households/' + home.id + '/sync', token, { operations });
    const created = await sync(ben.token, [mutation('ben-stock', 'inventory', 'stock', {
      ...stock(3), addedByName: 'Fake creator', lastModifiedByName: 'Fake actor', lastModifiedAction: 'Updated', lastModifiedAt: 1
    })]);
    const initial = created.data.records.find(r => r.id === 'stock').data;
    assert.equal(initial.addedByName, 'ben'); assert.equal(initial.lastModifiedByName, 'ben'); assert.equal(initial.lastModifiedAction, 'Added');
    const change = mutation('alice-stock', 'inventory', 'stock', { quantity: 4, addedByName: 'Fake creator', lastModifiedByName: 'Fake actor' }, { baseVersion: 1, quantityDelta: 1 });
    const updated = (await sync(alice.token, [change])).data.records.find(r => r.id === 'stock');
    assert.equal(updated.data.quantity, 4); assert.equal(updated.data.addedByName, 'ben');
    assert.equal(updated.data.lastModifiedByName, 'alice'); assert.equal(updated.data.lastModifiedAction, 'Updated');
    assert.ok(updated.data.lastModifiedAt >= initial.lastModifiedAt);
    const retry = (await sync(alice.token, [change])).data.records.find(r => r.id === 'stock');
    assert.deepEqual(retry, updated);
    const shopping = { itemName: 'Eggs', category: 'Food & Beverage', subcategory: 'Dairy', quantityNeeded: 2, quantityLabel: '',
      estimatedUnitPrice: 3, addedBy: 'Voice', isChecked: false, sourceId: null, createdAt: Date.now(), updatedAt: Date.now() };
    await sync(ben.token, [mutation('ben-list', 'shopping', 'list', shopping)]);
    const check = mutation('alice-check', 'shopping', 'list', { isChecked: true, lastModifiedByName: 'Fake actor', lastModifiedAction: 'Unchecked' }, { baseVersion: 1 });
    const checked = (await sync(alice.token, [check])).data.records.find(r => r.id === 'list');
    assert.equal(checked.data.addedByName, 'ben'); assert.equal(checked.data.lastModifiedByName, 'alice');
    assert.equal(checked.data.lastModifiedAction, 'Checked'); assert.equal(checked.data.addedBy, 'Voice');
    assert.deepEqual((await sync(alice.token, [check])).data.records.find(r => r.id === 'list'), checked);
    // Older clients can still update without sending author fields.
    const uncheck = mutation('ben-uncheck', 'shopping', 'list', { isChecked: false }, { baseVersion: 2 });
    const unchecked = (await sync(ben.token, [uncheck])).data.records.find(r => r.id === 'list').data;
    assert.equal(unchecked.lastModifiedByName, 'ben'); assert.equal(unchecked.lastModifiedAction, 'Unchecked');
    assert.equal(unchecked.addedByName, 'ben');
  } finally { await f.close(); }
});
