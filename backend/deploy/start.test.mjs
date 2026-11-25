import { test } from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { mkdtempSync, rmSync, rmdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { configuration, importServer, launchServices } from './start.mjs';
import { backupSharing } from './backup-sharing.mjs';

function harness() {
  const processes = [], finishes = [];
  const environment = { PORT: '10000', SHARING_DATA_DIR: join(tmpdir(), 'shelfwise-test-data'),
    GEMINI_API_KEY: 'test-only-key', GEMINI_MODEL: 'existing-model', GEMINI_FALLBACK_MODELS: 'existing-fallbacks',
    APK_PATH: join(tmpdir(), 'shelfwise-test.apk') };
  const group = launchServices({ environment, finish: code => finishes.push(code), spawnChild: (binary, args, options) => {
    const child = new EventEmitter(); child.signals = [];
    child.kill = signal => { child.signals.push(signal); queueMicrotask(() => child.emit('exit', 0, signal)); };
    processes.push({ child, binary, args, options }); return child;
  } });
  return { group, environment, processes, finishes };
}
test('one cloud origin supervises unchanged AI, sharing and gateway processes with separate ports', async () => {
  const { group, environment, processes, finishes } = harness();
  assert.equal(processes.length, 3);
  assert.equal(processes[0].options.env.PORT, '8787');
  assert.equal(processes[1].options.env.SHARING_PORT, '8790');
  assert.equal(processes[1].options.env.SHARING_HOST, '127.0.0.1');
  assert.equal(processes[2].options.env.PORT, '10000');
  assert.ok(processes[2].options.cwd.endsWith('gateway'));
  for (const process of processes) {
    for (const key of ['GEMINI_API_KEY', 'GEMINI_MODEL', 'GEMINI_FALLBACK_MODELS', 'APK_PATH', 'SHARING_DATA_DIR']) {
      assert.equal(process.options.env[key], environment[key]);
    }
  }
  group.stop(); await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(finishes, [0]);
  assert.ok(processes.every(process => process.child.signals.join() === 'SIGTERM'));
});
test('an unexpected child exit shuts down all siblings and returns a failure for host restart', async () => {
  const { processes, finishes } = harness();
  processes[1].child.emit('exit', 0);
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(finishes, [1]);
  assert.equal(processes[0].child.signals[0], 'SIGTERM'); assert.equal(processes[2].child.signals[0], 'SIGTERM');
});
test('persistent storage and port conflicts are validated before processes start', () => {
  assert.throws(() => configuration({ PORT: '10000' }), /persistent disk/);
  assert.throws(() => configuration({ PORT: '8787', SHARING_DATA_DIR: tmpdir() }), /gateway port/);
  assert.throws(() => launchServices({ environment: { SHARING_DATA_DIR: tmpdir() } }), /GEMINI_API_KEY/);
  assert.throws(() => launchServices({ environment: { SHARING_DATA_DIR: tmpdir(), SHARING_IMPORT_MODE: 'true' } }), /Import mode/);
});
test('import mode exposes setup health and blocks application requests without opening a database', async () => {
  const server = importServer();
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const origin = `http://127.0.0.1:${server.address().port}`;
  try {
    const health = await (await fetch(origin + '/health')).json();
    assert.equal(health.mode, 'database-import');
    const response = await fetch(origin + '/v1/auth/login', { method: 'POST', body: '{}' });
    assert.equal(response.status, 503); assert.equal((await response.json()).code, 'MAINTENANCE');
  } finally { await new Promise(resolve => { server.close(resolve); server.closeAllConnections(); }); }
});
test('database snapshots include committed WAL data and refuse to overwrite existing files', () => {
  const directory = mkdtempSync(join(tmpdir(), 'shelfwise-snapshot-'));
  const source = join(directory, 'source.sqlite'), destination = join(directory, "backup's.sqlite");
  const live = new DatabaseSync(source);
  try {
    live.exec("PRAGMA journal_mode=WAL; CREATE TABLE records(id INTEGER PRIMARY KEY, name TEXT); INSERT INTO records VALUES(1,'Shared pantry');");
    backupSharing(source, destination);
    const snapshot = new DatabaseSync(destination, { readOnly: true });
    try { assert.equal(snapshot.prepare('SELECT name FROM records').get().name, 'Shared pantry'); }
    finally { snapshot.close(); }
    assert.throws(() => backupSharing(source, destination), /already exists/);
    assert.throws(() => backupSharing(join(directory, 'missing.sqlite'), join(directory, 'other.sqlite')), /does not exist/);
  } finally {
    live.close();
    for (const file of [source, source + '-wal', source + '-shm', destination, destination + '-wal', destination + '-shm']) rmSync(file, { force: true });
    rmdirSync(directory);
  }
});
