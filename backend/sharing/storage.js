import { AsyncLocalStorage } from 'node:async_hooks';
import { DatabaseSync } from 'node:sqlite';
import { mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

export const SHARING_TABLES = ['users', 'sessions', 'households', 'members', 'records', 'mutations'];
export const SHARING_COLUMNS = {
  users: ['id', 'email', 'name', 'salt', 'passwordHash'],
  sessions: ['tokenHash', 'userId', 'expiresAt'],
  households: ['id', 'name', 'description', 'joinCode'],
  members: ['householdId', 'userId', 'role', 'status', 'lastSeenAt'],
  records: ['householdId', 'collection', 'id', 'data', 'version', 'deleted', 'userId'],
  mutations: ['householdId', 'mutationId', 'userId']
};
export const SHARING_SCHEMA = `
CREATE TABLE IF NOT EXISTS users(id TEXT PRIMARY KEY, email TEXT UNIQUE NOT NULL, name TEXT NOT NULL, salt TEXT NOT NULL, passwordHash TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS sessions(tokenHash TEXT PRIMARY KEY, userId TEXT NOT NULL REFERENCES users(id), expiresAt INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS households(id TEXT PRIMARY KEY, name TEXT NOT NULL, description TEXT NOT NULL, joinCode TEXT UNIQUE NOT NULL);
CREATE TABLE IF NOT EXISTS members(householdId TEXT NOT NULL REFERENCES households(id), userId TEXT NOT NULL REFERENCES users(id), role TEXT NOT NULL, status TEXT NOT NULL, lastSeenAt INTEGER, PRIMARY KEY(householdId,userId));
CREATE TABLE IF NOT EXISTS records(householdId TEXT NOT NULL REFERENCES households(id), collection TEXT NOT NULL, id TEXT NOT NULL, data TEXT NOT NULL, version INTEGER NOT NULL, deleted INTEGER NOT NULL, userId TEXT NOT NULL REFERENCES users(id), PRIMARY KEY(householdId,collection,id));
CREATE TABLE IF NOT EXISTS mutations(householdId TEXT NOT NULL, mutationId TEXT NOT NULL, userId TEXT NOT NULL, PRIMARY KEY(householdId,mutationId));`;

/** Own the connection for each complete transaction, including every await and the commit. */
export function serializedStorage(connection, kind) {
  const context = new AsyncLocalStorage();
  let tail = Promise.resolve(), closed = false;
  const locked = async action => {
    const prior = tail;
    let release;
    tail = new Promise(resolve => { release = resolve; });
    await prior;
    try {
      if (closed) throw new Error('Sharing database is closed.');
      return await action();
    } finally { release(); }
  };
  const inside = () => context.getStore()?.active === true;
  const perform = action => inside() ? action() : locked(action);
  const store = {
    kind,
    one: (sql, ...args) => perform(() => connection.get(sql, ...args)),
    all: (sql, ...args) => perform(() => connection.all(sql, ...args)),
    run: (sql, ...args) => perform(() => connection.run(sql, ...args)),
    exec: sql => perform(() => connection.exec(sql)),
    batch: statements => perform(() => connection.batch(statements)),
    transaction: async action => {
      if (inside()) return action();
      return locked(async () => {
        const token = { active: true };
        // Apply connection-local foreign-key settings before BEGIN on this same session.
        await connection.exec('PRAGMA foreign_keys=ON; BEGIN IMMEDIATE;');
        try {
          const value = await context.run(token, action);
          await connection.exec('COMMIT');
          return value;
        } catch (error) {
          try { await connection.exec('ROLLBACK'); } catch { /* Preserve the original failure. */ }
          throw error;
        } finally { token.active = false; }
      });
    },
    close: () => locked(async () => { await connection.close(); closed = true; })
  };
  return store;
}

export function createSqliteStorage(databasePath = ':memory:') {
  if (databasePath !== ':memory:') mkdirSync(dirname(resolve(databasePath)), { recursive: true });
  const database = new DatabaseSync(databasePath);
  database.exec('PRAGMA foreign_keys=ON; PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000;');
  const run = (sql, ...args) => database.prepare(sql).run(...args);
  return serializedStorage({
    get: (sql, ...args) => database.prepare(sql).get(...args),
    all: (sql, ...args) => database.prepare(sql).all(...args),
    run,
    exec: sql => database.exec(sql),
    batch: statements => statements.map(statement => run(statement.sql, ...(statement.args || []))),
    close: () => database.close()
  }, 'sqlite');
}

export function validateTursoConfiguration(url, authToken) {
  let address;
  try { address = new URL(url); } catch { throw new Error('Set a valid TURSO_DATABASE_URL.'); }
  if (!['https:', 'libsql:', 'turso:'].includes(address.protocol) || !address.hostname ||
      address.username || address.password || address.search || address.hash ||
      !['', '/'].includes(address.pathname)) throw new Error('Use the secure Turso database URL without credentials or a path.');
  if (typeof authToken !== 'string' || !authToken.trim()) throw new Error('Set TURSO_AUTH_TOKEN in the server environment.');
}

export async function createTursoStorage({ url, authToken } = {}) {
  validateTursoConfiguration(url, authToken);
  // Local Wi-Fi mode continues to work without installing the hosted database driver.
  const { connect } = await import('@tursodatabase/serverless');
  return serializedStorage(connect({ url, authToken, defaultQueryTimeout: 15_000 }), 'turso');
}

export async function initializeStorage(store) {
  await store.exec('PRAGMA foreign_keys=ON;' + SHARING_SCHEMA);
  // One statement also handles older shared records without one network request per item.
  await store.run(`UPDATE records SET data=json_set(data,
    '$.addedByName',(SELECT name FROM users WHERE id=records.userId)),version=version+1
    WHERE collection IN ('inventory','shopping') AND deleted=0
    AND COALESCE(TRIM(json_extract(data,'$.addedByName')),'')=''
    AND EXISTS(SELECT 1 FROM users WHERE id=records.userId AND LENGTH(TRIM(name))>0)`);
}
