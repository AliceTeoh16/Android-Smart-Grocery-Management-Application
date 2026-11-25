import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { createGateway } from './server.js';
import { createSharingServer } from '../sharing/server.js';
import { mkdtemp, writeFile, rm, rmdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

async function listen(server) {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  return `http://127.0.0.1:${server.address().port}`;
}
async function close(server) {
  await new Promise(resolve => { server.close(resolve); server.closeAllConnections(); });
}
test('routes auth to sharing and preserves AI JSON, binary bodies, status, headers and health capabilities', async () => {
  const stub = label => http.createServer(async (req, res) => {
    if (req.url === '/health') {
      res.setHeader('Content-Type', 'application/json');
      res.end(JSON.stringify(label === 'ai' ? { ok: true, inventoryScan: { enabled: true } } : { service: 'household-sharing' }));
      return;
    }
    const chunks = []; for await (const chunk of req) chunks.push(chunk);
    res.writeHead(201, { 'content-type': req.headers['content-type'], 'x-upstream': label });
    assert.equal(req.headers.authorization, 'Bearer test-only');
    assert.ok(req.url.endsWith('?preserve=yes'));
    res.end(Buffer.concat(chunks));
  });
  const ai = stub('ai'), sharing = stub('sharing');
  const aiOrigin = await listen(ai), sharingOrigin = await listen(sharing);
  const gateway = createGateway({ aiOrigin, sharingOrigin }); const url = await listen(gateway);
  try {
    for (const [path, body, type, label] of [
      ['/v1/auth/login', Buffer.from('{"email":"test","password":"secret"}'), 'application/json', 'sharing'],
      ['/recipes/generate', Buffer.from('{"available_ingredients":["rice"]}'), 'application/json', 'ai'],
      ['/ingredients/scan', Buffer.from([0, 255, 13, 10, 128]), 'application/octet-stream', 'ai']]) {
      const result = await fetch(url + path + '?preserve=yes', { method: 'POST',
        headers: { authorization: 'Bearer test-only', 'content-type': type }, body });
      assert.equal(result.status, 201); assert.equal(result.headers.get('x-upstream'), label);
      assert.deepEqual(Buffer.from(await result.arrayBuffer()), body);
    }
    const health = await (await fetch(url + '/health')).json();
    assert.equal(health.ok, true); assert.equal(health.service, 'shelfwise-gateway');
    assert.deepEqual(health.inventoryScan, { enabled: true });
    assert.equal((await (await fetch(url + '/health/sharing')).json()).service, 'household-sharing');
  } finally { await close(gateway); await close(ai); await close(sharing); }
});
test('real household auth and invite joining work through one gateway with existing server data', async () => {
  const service = createSharingServer(); const sharingOrigin = await listen(service.server);
  const gateway = createGateway({ sharingOrigin }); const url = await listen(gateway);
  const call = async (path, body, token, origin = url) => {
    const response = await fetch(origin + path, { method: body ? 'POST' : 'GET',
      headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) },
      ...(body ? { body: JSON.stringify(body) } : {}) });
    assert.ok(response.ok, `HTTP ${response.status}`); return response.json();
  };
  try {
    const owner = await call('/v1/auth/register', { name: 'Owner', email: 'owner@example.test', password: 'correct123' }, null, sharingOrigin);
    const login = await call('/v1/auth/login', { email: 'owner@example.test', password: 'correct123' });
    assert.equal(login.user.id, owner.user.id);
    const home = await call('/v1/households', { name: 'Home', description: 'Gateway test' }, login.token);
    const member = await call('/v1/auth/register', { name: 'Member', email: 'member@example.test', password: 'correct123' });
    const joined = await call('/v1/households/join', { code: home.household.joinCode }, member.token);
    assert.equal(joined.household.id, home.household.id); assert.equal(joined.members.length, 2);
    const invalid = await fetch(url + '/v1/auth/login', { method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ email: 'owner@example.test', password: 'wrong' }) });
    assert.equal(invalid.status, 401);
  } finally { await close(gateway); await service.close(); }
});
test('upstream timeout or refusal returns a friendly service error without connection details', async () => {
  const hanging = http.createServer(() => {}); const aiOrigin = await listen(hanging);
  const gateway = createGateway({ aiOrigin, timeoutMs: 100 }); const url = await listen(gateway);
  try {
    const response = await fetch(url + '/recipes/generate', { method: 'POST', body: '{}' });
    assert.equal(response.status, 503); assert.equal((await response.json()).code, 'SERVICE_UNAVAILABLE');
    await close(hanging);
    const refused = await fetch(url + '/recipes/generate'); assert.equal(refused.status, 503);
    assert.ok(!(await refused.text()).includes('127.0.0.1'));
  } finally { await close(gateway); if (hanging.listening) await close(hanging); }
});

test('phone download returns the latest APK bytes without caching or exposing other files', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'shelfwise-gateway-'));
  const apkPath = join(directory, 'phone.apk');
  const gateway = createGateway({ apkPath }); const url = await listen(gateway);
  try {
    assert.equal((await fetch(url + '/download/shelfwise.apk')).status, 503);
    for (const bytes of [Buffer.from('first test APK'), Buffer.from('updated test APK')]) {
      await writeFile(apkPath, bytes);
      const response = await fetch(url + '/download/shelfwise.apk');
      assert.equal(response.status, 200); assert.equal(response.headers.get('cache-control'), 'no-store');
      assert.equal(response.headers.get('content-type'), 'application/vnd.android.package-archive');
      assert.deepEqual(Buffer.from(await response.arrayBuffer()), bytes);
    }
    const head = await fetch(url + '/download/shelfwise.apk', { method: 'HEAD' });
    assert.equal(head.status, 200); assert.equal((await head.arrayBuffer()).byteLength, 0);
    assert.notEqual((await fetch(url + '/download/other-file')).status, 200);
  } finally { await close(gateway); await rm(apkPath, { force: true }); await rmdir(directory); }
});
