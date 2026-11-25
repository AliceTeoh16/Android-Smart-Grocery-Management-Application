import test from 'node:test';
import assert from 'node:assert/strict';
import { createSqliteStorage, createTursoStorage, initializeStorage, validateTursoConfiguration } from './storage.js';
import { protocolDatabase } from './storage-test-support.js';
import { createSharingServer } from './server.js';

for (const kind of ['sqlite', 'turso']) {
  test(kind + ': transactions isolate awaited writes, preserve foreign keys and roll back failed batches', async () => {
    const bridge = kind === 'turso' ? protocolDatabase() : null;
    const store = bridge ? await bridge.storage() : createSqliteStorage();
    try {
      await initializeStorage(store);
      await assert.rejects(store.transaction(async () => {
        await store.run("INSERT INTO users VALUES('a','a@example.test','Alice','salt','hash')");
        await store.batch([{ sql: "INSERT INTO users VALUES('b','b@example.test','Bob','salt','hash')" },
          { sql: "INSERT INTO sessions VALUES('bad','missing-user',1)" }]);
      }));
      assert.equal((await store.one('SELECT COUNT(*) AS n FROM users')).n, 0);
      let release, entered;
      const gate = new Promise(resolve => { release = resolve; });
      const started = new Promise(resolve => { entered = resolve; });
      const first = store.transaction(async () => {
        await store.run("INSERT INTO users VALUES('a','a@example.test','Alice','salt','hash')");
        entered(); await gate;
      });
      await started;
      let observed = false;
      const other = store.one('SELECT COUNT(*) AS n FROM users').then(row => { observed = true; return row.n; });
      await new Promise(resolve => setImmediate(resolve));
      assert.equal(observed, false); release(); await first; assert.equal(await other, 1);
    } finally { await store.close(); bridge?.close(); }
  });
}
test('hosted configuration rejects insecure URLs and missing credentials', async () => {
  assert.throws(() => validateTursoConfiguration('http://database.example', 'token'), /secure/);
  assert.throws(() => validateTursoConfiguration('libsql://database.example', ''), /TURSO_AUTH_TOKEN/);
  assert.throws(() => validateTursoConfiguration('https://name:password@database.example/', 'token'), /secure/);
  await assert.rejects(createTursoStorage({ url: 'not-a-url', authToken: 'token' }), /valid TURSO_DATABASE_URL/);
  const service = createSharingServer({ storageMode: 'turso' });
  try { await assert.rejects(service.ready, /valid TURSO_DATABASE_URL/); }
  finally { await service.close(); }
});
test('a lost hosted commit response is safe to retry and duplicate registrations return account-exists', async () => {
  const bridge = protocolDatabase(); const store = await bridge.storage();
  const service = createSharingServer({ storage: store });
  await new Promise(resolve => service.server.listen(0, '127.0.0.1', resolve));
  const origin = 'http://127.0.0.1:' + service.server.address().port;
  const call = async (path, body, token) => {
    const response = await fetch(origin + path, { method: 'POST', headers: { 'content-type': 'application/json',
      ...(token ? { authorization: 'Bearer ' + token } : {}) }, body: JSON.stringify(body) });
    return { status: response.status, body: await response.json() };
  };
  try {
    const input = { name: 'Alice', email: 'alice@example.test', password: 'correct123' };
    const accounts = await Promise.all([call('/v1/auth/register', input), call('/v1/auth/register', input)]);
    assert.deepEqual(accounts.map(result => result.status).sort(), [201, 409]);
    const account = accounts.find(result => result.status === 201).body;
    const home = (await call('/v1/households', { name: 'Home' }, account.token)).body.household;
    const operations = Array.from({ length: 200 }, (_, i) => ({ mutationId: 'purchase-' + i, id: 'purchase-' + i,
      collection: 'logs', baseVersion: 0, deleted: false, data: { itemId: null, itemName: 'Purchase ' + i,
        category: 'Food & Beverage', changeType: 'ADD', amountChanged: 1, unitPriceAtChange: 2, timestamp: 1000 } }));
    const before = bridge.calls;
    bridge.loseNextCommit = true;
    const lost = await call('/v1/households/' + home.id + '/sync', { operations }, account.token);
    assert.equal(lost.status, 500);
    assert.ok(bridge.calls - before < 20, 'A 200-item batch must not perform one network request per record');
    const retry = await call('/v1/households/' + home.id + '/sync', { operations }, account.token);
    assert.equal(retry.status, 200); assert.equal(retry.body.acknowledged.length, 200);
    assert.equal(retry.body.records.filter(row => row.collection === 'logs').length, 200);
    assert.equal((await store.one('SELECT COUNT(*) AS n FROM mutations')).n, 200);
  } finally { await service.close(); bridge.close(); }
});
