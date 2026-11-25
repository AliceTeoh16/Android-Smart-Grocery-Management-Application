import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { mkdtempSync, rmSync, rmdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { backupSharing } from './backup-sharing.mjs';
import { importSharingSnapshot } from './import-turso.mjs';
import { protocolDatabase } from '../sharing/storage-test-support.js';
import { initializeStorage } from '../sharing/storage.js';
import { createSharingServer } from '../sharing/server.js';

async function serving(storage, databasePath) {
  const service = createSharingServer({ storage, databasePath });
  await new Promise(resolve => service.server.listen(0, '127.0.0.1', resolve));
  const origin = 'http://127.0.0.1:' + service.server.address().port;
  return { ...service, call: async (path, token, body) => {
    const response = await fetch(origin + path, { method: body ? 'POST' : 'GET',
      headers: { 'content-type': 'application/json', ...(token ? { authorization: 'Bearer ' + token } : {}) },
      ...(body ? { body: JSON.stringify(body) } : {}) });
    return { status: response.status, body: await response.json() };
  } };
}
function directory() {
  const path = mkdtempSync(join(tmpdir(), 'shelfwise-import-test-'));
  return { path, cleanup() {
    for (const name of ['source.sqlite', 'snapshot.sqlite', 'phone.sqlite']) {
      for (const suffix of ['', '-wal', '-shm']) rmSync(join(path, name + suffix), { force: true });
    }
    rmdirSync(path);
  } };
}
test('import preserves remote account identity, household data, tokens and mutation retries across host restart', async () => {
  const files = directory(), source = join(files.path, 'source.sqlite'), snapshot = join(files.path, 'snapshot.sqlite');
  const bridge = protocolDatabase(); let local, cloud, target;
  try {
    local = await serving(undefined, source);
    const account = (await local.call('/v1/auth/register', null, { name: 'Alice', email: 'alice@example.test', password: 'correct123' })).body;
    const home = (await local.call('/v1/households', account.token, { name: 'Persistent family' })).body.household;
    const operations = [{ mutationId: 'stock-create', collection: 'inventory', id: 'rice', baseVersion: 0, deleted: false,
      data: { name: 'Rice', quantity: 3, price: 2, category: 'Food & Beverage', subcategory: 'Pantry', expiryDate: null, threshold: 1, purchasedDate: 1000 } },
      { mutationId: 'offline-purchase', collection: 'logs', id: 'purchase', baseVersion: 0, deleted: false,
        data: { itemId: 'rice', itemName: 'Rice', category: 'Food & Beverage', changeType: 'ADD', amountChanged: 3, unitPriceAtChange: 2, timestamp: 1000 } }];
    await local.call('/v1/households/' + home.id + '/sync', account.token, { operations });
    await local.close(); local = null;
    backupSharing(source, snapshot);
    target = await bridge.storage();
    bridge.loseNextCommit = true;
    await assert.rejects(importSharingSnapshot(snapshot, target));
    const imported = await importSharingSnapshot(snapshot, target);
    assert.equal(imported.alreadyImported, true); assert.equal(imported.counts.mutations, 2);
    cloud = await serving(target);
    const login = await cloud.call('/v1/auth/login', null, { email: 'alice@example.test', password: 'correct123' });
    assert.equal(login.status, 200); assert.equal(login.body.user.id, account.user.id);
    const retried = await cloud.call('/v1/households/' + home.id + '/sync', account.token, { operations });
    assert.equal(retried.status, 200);
    assert.equal(retried.body.records.find(row => row.id === 'rice').data.quantity, 3);
    assert.equal(retried.body.records.filter(row => row.collection === 'logs').length, 1);
    await cloud.close(); cloud = null; target = null;
    cloud = await serving(await bridge.storage());
    const afterRestart = await cloud.call('/v1/households', login.body.token);
    assert.equal(afterRestart.status, 200); assert.equal(afterRestart.body.households[0].household.id, home.id);
    assert.equal(afterRestart.body.households[0].records.filter(row => row.collection === 'logs').length, 1);
  } finally { if (local) await local.close(); if (cloud) await cloud.close(); else if (target) await target.close(); bridge.close(); files.cleanup(); }
});
test('a phone database is rejected before any query or private data is sent to the hosted service', async () => {
  const files = directory(), phonePath = join(files.path, 'phone.sqlite');
  const phone = new DatabaseSync(phonePath); phone.exec('CREATE TABLE users(userId TEXT PRIMARY KEY,email TEXT)'); phone.close();
  const bridge = protocolDatabase(), target = await bridge.storage();
  try {
    await assert.rejects(importSharingSnapshot(phonePath, target), /Phone Room databases must stay private/);
    assert.equal(bridge.calls, 0);
  } finally { await target.close(); bridge.close(); files.cleanup(); }
});
test('import refuses an existing hosted account instead of clearing or overwriting hosted data', async () => {
  const files = directory(), source = join(files.path, 'source.sqlite');
  const service = createSharingServer({ databasePath: source }); await service.ready; await service.close();
  const bridge = protocolDatabase(), target = await bridge.storage();
  try {
    await initializeStorage(target);
    await target.run("INSERT INTO users VALUES('keep','keep@example.test','Keep','salt','hash')");
    await assert.rejects(importSharingSnapshot(source, target), /already contains application data/);
    assert.equal((await target.one('SELECT COUNT(*) AS n FROM users')).n, 1);
  } finally { await target.close(); bridge.close(); files.cleanup(); }
});
