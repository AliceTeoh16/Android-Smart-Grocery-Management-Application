import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { randomUUID } from 'node:crypto';
import { mkdtempSync, rmSync, rmdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createTursoStorage } from './storage.js';

// A local Hrana protocol fixture exercises the real Turso SDK without cloud credentials.
const hosts = new Map();
let originalFetch;
const encode = value => value === null ? { type: 'null' } : typeof value === 'number'
  ? Number.isInteger(value) ? { type: 'integer', value: String(value) } : { type: 'float', value }
  : { type: 'text', value: String(value) };
const decode = value => value.type === 'null' ? null : value.type === 'integer' || value.type === 'float' ? Number(value.value) : value.value;

function resultFor(database, statement) {
  const prepared = database.prepare(statement.sql);
  const columns = prepared.columns().map(column => ({ name: column.name, decltype: column.type || '' }));
  const args = (statement.args || []).map(decode);
  if (columns.length) {
    const values = prepared.all(...args);
    return { cols: columns, rows: values.map(row => columns.map(column => encode(row[column.name]))), affected_row_count: 0, last_insert_rowid: null };
  }
  const result = prepared.run(...args);
  return { cols: [], rows: [], affected_row_count: Number(result.changes), last_insert_rowid: String(result.lastInsertRowid) };
}
function executeBatch(database, batch) {
  const results = [], errors = [];
  const allowed = condition => !condition || condition.type === 'is_autocommit' ? !condition || !database.isTransaction
    : condition.type === 'ok' ? results[condition.step] != null
    : condition.type === 'error' ? errors[condition.step] != null
    : condition.type === 'not' ? !allowed(condition.cond)
    : condition.type === 'and' ? condition.conds.every(allowed)
    : condition.type === 'or' ? condition.conds.some(allowed) : false;
  for (const step of batch.steps) {
    if (!allowed(step.condition)) { results.push(null); errors.push(null); continue; }
    try { results.push(resultFor(database, step.stmt)); errors.push(null); }
    catch (error) { results.push(null); errors.push({ code: error.code || 'SQLITE_ERROR', message: error.message }); }
  }
  return { step_results: results, step_errors: errors };
}
function installFetch() {
  if (originalFetch) return;
  originalFetch = globalThis.fetch;
  globalThis.fetch = async (input, options) => {
    const address = new URL(typeof input === 'string' || input instanceof URL ? input : input.url);
    const bridge = hosts.get(address.host);
    return bridge ? bridge.request(address.pathname, options) : originalFetch(input, options);
  };
}

export function protocolDatabase(databasePath) {
  installFetch();
  const directory = databasePath ? null : mkdtempSync(join(tmpdir(), 'shelfwise-turso-test-'));
  const path = databasePath || join(directory, 'database.sqlite');
  const host = randomUUID() + '.turso.test';
  const sessions = new Map();
  const bridge = {
    calls: 0, loseNextCommit: false,
    async request(endpoint, options) {
      bridge.calls++;
      assert.equal(new Headers(options.headers).get('authorization'), 'Bearer test-only-storage-token');
      const request = JSON.parse(options.body);
      let baton = request.baton;
      if (!baton) {
        baton = randomUUID(); const db = new DatabaseSync(path);
        db.exec('PRAGMA journal_mode=WAL; PRAGMA busy_timeout=1000;'); sessions.set(baton, db);
      }
      const database = sessions.get(baton);
      assert.ok(database, 'Protocol session must still be open');
      if (endpoint === '/v3/cursor') {
        const batch = executeBatch(database, request.batch), entries = [{ baton, base_url: null }];
        for (let step = 0; step < batch.step_results.length; step++) {
          const result = batch.step_results[step], error = batch.step_errors[step];
          if (error) { entries.push({ type: 'step_error', step, error }); continue; }
          if (!result) continue;
          entries.push({ type: 'step_begin', step, cols: result.cols });
          for (const row of result.rows) entries.push({ type: 'row', row });
          entries.push({ type: 'step_end', step, affected_row_count: result.affected_row_count, last_insert_rowid: result.last_insert_rowid });
        }
        return new Response(entries.map(entry => JSON.stringify(entry)).join('\n') + '\n');
      }
      assert.equal(endpoint, '/v3/pipeline');
      const results = [];
      for (const operation of request.requests) {
        try {
          let response = { type: operation.type };
          if (operation.type === 'sequence') {
            database.exec(operation.sql);
            if (operation.sql === 'COMMIT' && bridge.loseNextCommit) {
              bridge.loseNextCommit = false;
              throw Object.assign(new Error('Simulated lost commit response'), { transport: true });
            }
          } else if (operation.type === 'get_autocommit') response.is_autocommit = !database.isTransaction;
          else if (operation.type === 'batch') response.result = executeBatch(database, operation.batch);
          else if (operation.type === 'close') { database.close(); sessions.delete(baton); baton = null; }
          else throw new Error('Unsupported protocol operation: ' + operation.type);
          results.push({ type: 'ok', response });
        } catch (error) {
          if (error.transport) throw error;
          results.push({ type: 'error', error: { code: error.code || 'SQLITE_ERROR', message: error.message } });
        }
      }
      return new Response(JSON.stringify({ baton, base_url: null, results }), { headers: { 'content-type': 'application/json' } });
    },
    storage: () => createTursoStorage({ url: 'libsql://' + host, authToken: 'test-only-storage-token' }),
    close() {
      hosts.delete(host);
      for (const database of sessions.values()) database.close();
      sessions.clear();
      if (directory) {
        for (const file of [path, path + '-wal', path + '-shm']) rmSync(file, { force: true });
        rmdirSync(directory);
      }
      if (!hosts.size) { globalThis.fetch = originalFetch; originalFetch = undefined; }
    }
  };
  hosts.set(host, bridge);
  return bridge;
}
