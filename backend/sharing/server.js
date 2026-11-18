import http from 'node:http';
import { DatabaseSync } from 'node:sqlite';
import { randomBytes, randomUUID, randomInt, scrypt, createHash, timingSafeEqual } from 'node:crypto';
import { promisify } from 'node:util';
import { mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const derive = promisify(scrypt);
const hash = value => createHash('sha256').update(value).digest('hex');
const fail = (status, code, message) => { throw Object.assign(new Error(message), { status, code }); };
const text = (value, max = 300) => typeof value === 'string' && value.length <= max;
const finite = value => typeof value === 'number' && Number.isFinite(value) && Math.abs(value) <= 1e12;
const integer = value => Number.isSafeInteger(value) && Math.abs(value) <= 2_000_000_000;
const instant = value => Number.isSafeInteger(value) && value >= 0 && value <= 8_640_000_000_000_000;
const id = value => typeof value === 'string' && /^[a-zA-Z0-9:_-]{1,160}$/.test(value);
const collections = new Set(['inventory', 'shopping', 'logs', 'budget', 'household', 'settings']);
const attributionFields = {
  addedByName: v => v === null || text(v, 100), lastModifiedByName: v => v === null || text(v, 100),
  lastModifiedAction: v => v === null || ['Added', 'Updated', 'Checked', 'Unchecked'].includes(v),
  lastModifiedAt: v => v === null || instant(v)
};
const fields = {
  inventory: { ...attributionFields, name: v => text(v) && v.trim(), quantity: v => integer(v) && v >= 0, price: v => finite(v) && v >= 0,
    category: v => text(v, 100), subcategory: v => v === null || text(v, 100), expiryDate: v => v === null || instant(v),
    threshold: v => integer(v) && v >= 0, purchasedDate: instant },
  shopping: { ...attributionFields, itemName: v => text(v) && v.trim(), category: v => text(v, 100), subcategory: v => v === null || text(v, 100),
    quantityNeeded: v => integer(v) && v >= 0, quantityLabel: v => text(v, 100), estimatedUnitPrice: v => finite(v) && v >= 0,
    addedBy: v => text(v, 100), isChecked: v => typeof v === 'boolean', sourceId: v => v === null || id(v), createdAt: instant, updatedAt: instant },
  logs: { itemId: v => v === null || id(v), itemName: v => text(v), category: v => text(v, 100), changeType: v => ['ADD', 'DEDUCT', 'VOICE_DEDUCT', 'RECIPE_DEDUCT'].includes(v),
    amountChanged: v => integer(v) && v >= 0, unitPriceAtChange: v => finite(v) && v >= 0, timestamp: instant },
  budget: { monthYear: v => /^\d{4}-(0[1-9]|1[0-2])$/.test(v), limitAmount: v => finite(v) && v >= 0 },
  household: { name: v => text(v, 100) && v.trim(), description: v => text(v, 1000) },
  settings: { expiryWarningDays: v => Number.isInteger(v) && v >= 1 && v <= 30 }
};

export function createSharingServer({ databasePath = ':memory:', now = Date.now } = {}) {
  if (databasePath !== ':memory:') mkdirSync(dirname(resolve(databasePath)), { recursive: true });
  const db = new DatabaseSync(databasePath);
  db.exec(`PRAGMA foreign_keys=ON; PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000;
    CREATE TABLE IF NOT EXISTS users(id TEXT PRIMARY KEY, email TEXT UNIQUE NOT NULL, name TEXT NOT NULL, salt TEXT NOT NULL, passwordHash TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS sessions(tokenHash TEXT PRIMARY KEY, userId TEXT NOT NULL REFERENCES users(id), expiresAt INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS households(id TEXT PRIMARY KEY, name TEXT NOT NULL, description TEXT NOT NULL, joinCode TEXT UNIQUE NOT NULL);
    CREATE TABLE IF NOT EXISTS members(householdId TEXT NOT NULL REFERENCES households(id), userId TEXT NOT NULL REFERENCES users(id), role TEXT NOT NULL, status TEXT NOT NULL, lastSeenAt INTEGER, PRIMARY KEY(householdId,userId));
    CREATE TABLE IF NOT EXISTS records(householdId TEXT NOT NULL REFERENCES households(id), collection TEXT NOT NULL, id TEXT NOT NULL, data TEXT NOT NULL, version INTEGER NOT NULL, deleted INTEGER NOT NULL, userId TEXT NOT NULL REFERENCES users(id), PRIMARY KEY(householdId,collection,id));
    CREATE TABLE IF NOT EXISTS mutations(householdId TEXT NOT NULL, mutationId TEXT NOT NULL, userId TEXT NOT NULL, PRIMARY KEY(householdId,mutationId));`);
  const limits = new Map();
  const rate = (key, limit, window = 60_000) => {
    const current = limits.get(key);
    const value = current && current.until > now() ? current : { count: 0, until: now() + window };
    if (++value.count > limit) fail(429, 'RATE_LIMITED', 'Too many attempts. Please try again later.');
    limits.set(key, value);
    if (limits.size > 10_000) for (const [key, value] of limits) if (value.until <= now()) limits.delete(key);
  };
  const one = (sql, ...args) => db.prepare(sql).get(...args);
  const all = (sql, ...args) => db.prepare(sql).all(...args);
  const run = (sql, ...args) => db.prepare(sql).run(...args);
  const transaction = action => { db.exec('BEGIN IMMEDIATE'); try { const result = action(); db.exec('COMMIT'); return result; } catch (error) { db.exec('ROLLBACK'); throw error; } };
  // Add only a known creator to older records; the previous editor cannot be inferred.
  transaction(() => {
    for (const row of all("SELECT r.*, u.name AS creator FROM records r JOIN users u ON u.id=r.userId WHERE r.collection IN ('inventory','shopping') AND r.deleted=0")) {
      const data = JSON.parse(row.data);
      if (Object.hasOwn(data, 'addedByName')) continue;
      Object.assign(data, { addedByName: row.creator, lastModifiedByName: null, lastModifiedAction: null, lastModifiedAt: null });
      run('UPDATE records SET data=?,version=version+1 WHERE householdId=? AND collection=? AND id=?', JSON.stringify(data), row.householdId, row.collection, row.id);
    }
  });
  const member = (householdId, userId, owner = false) => {
    const row = one('SELECT * FROM members WHERE householdId=? AND userId=?', householdId, userId);
    if (!row || row.status !== 'Active' || (owner && row.role !== 'Owner')) fail(403, 'FORBIDDEN', 'Household access is not available for this account.');
    return row;
  };
  const publicUser = user => ({ id: user.id, email: user.email, name: user.name });
  const record = row => row && ({ collection: row.collection, id: row.id, data: JSON.parse(row.data), version: row.version, deleted: Boolean(row.deleted), userId: row.userId });
  const snapshot = householdId => ({ household: one('SELECT * FROM households WHERE id=?', householdId),
    members: all('SELECT u.id, u.email, u.name, m.role, m.status, m.lastSeenAt FROM members m JOIN users u ON u.id=m.userId WHERE m.householdId=? ORDER BY m.role,u.id', householdId),
    records: all('SELECT * FROM records WHERE householdId=? ORDER BY collection,id', householdId).map(record) });
  const session = user => {
    const token = randomBytes(32).toString('base64url');
    const expiresAt = now() + 30 * 24 * 60 * 60 * 1000;
    run('INSERT INTO sessions VALUES(?,?,?)', hash(token), user.id, expiresAt);
    return { user: publicUser(user), token, expiresAt };
  };
  const body = async req => {
    let size = 0; const chunks = [];
    for await (const chunk of req) { size += chunk.length; if (size > 1024 * 1024) fail(413, 'TOO_LARGE', 'Request is too large.'); chunks.push(chunk); }
    try { const value = JSON.parse(Buffer.concat(chunks).toString() || '{}'); if (!value || Array.isArray(value) || typeof value !== 'object') throw Error(); return value; }
    catch { fail(400, 'INVALID_JSON', 'Invalid request.'); }
  };
  const putRecord = (hh, collection, recordId, data, version, deleted, userId) => run(`INSERT INTO records VALUES(?,?,?,?,?,?,?)
    ON CONFLICT(householdId,collection,id) DO UPDATE SET data=excluded.data,version=excluded.version,deleted=excluded.deleted`, hh, collection, recordId, JSON.stringify(data), version, Number(deleted), userId);
  const validatePatch = (collection, patch, full) => {
    if (!patch || Array.isArray(patch) || typeof patch !== 'object') fail(400, 'INVALID_RECORD', 'Invalid shared record.');
    for (const [key, value] of Object.entries(patch)) if (!Object.hasOwn(fields[collection], key) || !fields[collection][key](value)) fail(400, 'INVALID_RECORD', `Invalid ${collection} field: ${key}.`);
    if (full && Object.keys(fields[collection]).some(key => !Object.hasOwn(attributionFields, key) && !Object.hasOwn(patch, key))) fail(400, 'INVALID_RECORD', 'Shared record is incomplete.');
  };

  const server = http.createServer(async (req, res) => {
    const send = (status, value) => { res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' }); res.end(JSON.stringify(value)); };
    try {
      const url = new URL(req.url, 'http://sharing.local');
      const path = url.pathname; const ip = req.socket.remoteAddress || 'unknown';
      rate(`request:${ip}`, 600);
      if (path === '/health' && req.method === 'GET') return send(200, { service: 'household-sharing', version: 1 });
      if (path === '/v1/auth/register' && req.method === 'POST') {
        rate(`auth:${ip}`, 30);
        const input = await body(req); const email = String(input.email || '').trim().toLowerCase();
        if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) || email.length > 254 || !text(input.name, 100) || !input.name.trim() ||
            !text(input.password, 256) || input.password.length < 8 || !/\d/.test(input.password)) fail(400, 'VALIDATION', 'Use a valid email, name, and password of at least 8 characters including a number.');
        if (one('SELECT id FROM users WHERE email=?', email)) fail(409, 'ACCOUNT_EXISTS', 'An account already exists for this email.');
        const salt = randomBytes(32).toString('hex'); const derived = await derive(input.password, salt, 32);
        const user = { id: randomUUID(), email, name: input.name.trim(), salt, passwordHash: derived.toString('hex') };
        if (one('SELECT id FROM users WHERE email=?', email)) fail(409, 'ACCOUNT_EXISTS', 'An account already exists for this email.');
        run('INSERT INTO users VALUES(?,?,?,?,?)', user.id, user.email, user.name, salt, user.passwordHash);
        return send(201, session(user));
      }
      if (path === '/v1/auth/login' && req.method === 'POST') {
        rate(`auth:${ip}`, 30);
        const input = await body(req);
        const user = one('SELECT * FROM users WHERE email=?', String(input.email || '').trim().toLowerCase());
        if (!user) fail(404, 'ACCOUNT_NOT_FOUND', 'No account found with this email. Please sign up to create an account first.');
        if (!text(input.password, 256)) fail(401, 'INVALID_CREDENTIALS', 'Email or password is incorrect.');
        const actual = await derive(input.password, user.salt, 32);
        if (!timingSafeEqual(actual, Buffer.from(user.passwordHash, 'hex'))) fail(401, 'INVALID_CREDENTIALS', 'Email or password is incorrect.');
        return send(200, session(user));
      }
      const token = req.headers.authorization?.match(/^Bearer ([a-zA-Z0-9_-]+)$/)?.[1];
      const auth = token && one('SELECT * FROM sessions WHERE tokenHash=? AND expiresAt>?', hash(token), now());
      if (!auth) fail(401, 'UNAUTHORIZED', 'Sign in to the sharing server again.');
      const userId = auth.userId;
      if (path === '/v1/auth/logout' && req.method === 'POST') { run('DELETE FROM sessions WHERE tokenHash=?', hash(token)); return send(200, { ok: true }); }
      if (path === '/v1/households' && req.method === 'GET') {
        return send(200, { households: all("SELECT h.id FROM households h JOIN members m ON m.householdId=h.id WHERE m.userId=? AND m.status='Active'", userId).map(row => snapshot(row.id)) });
      }
      if (path === '/v1/households' && req.method === 'POST') {
        const input = await body(req);
        if (!text(input.name, 100) || !input.name.trim() || !text(input.description ?? '', 1000)) fail(400, 'VALIDATION', 'Enter a household name and description.');
        return send(201, transaction(() => {
          let code; do { code = String(randomInt(100000, 1000000)); } while (one('SELECT id FROM households WHERE joinCode=?', code));
          const hh = randomUUID(); const description = input.description || '';
          run('INSERT INTO households VALUES(?,?,?,?)', hh, input.name.trim(), description, code);
          run("INSERT INTO members VALUES(?,?,'Owner','Active',?)", hh, userId, now());
          putRecord(hh, 'household', 'main', { name: input.name.trim(), description }, 1, false, userId);
          putRecord(hh, 'settings', 'main', { expiryWarningDays: 3 }, 1, false, userId);
          return snapshot(hh);
        }));
      }
      if (path === '/v1/households/join' && req.method === 'POST') {
        rate(`join:${userId}`, 20);
        const input = await body(req);
        if (!/^\d{6}$/.test(input.code || '')) fail(400, 'INVALID_CODE', 'Enter a valid 6-digit invite code.');
        const hh = one('SELECT id FROM households WHERE joinCode=?', input.code);
        if (!hh) fail(404, 'HOUSEHOLD_NOT_FOUND', 'Household invite code was not found on the sharing server.');
        return send(200, transaction(() => {
          run("INSERT INTO members VALUES(?,?,'Member','Active',?) ON CONFLICT(householdId,userId) DO UPDATE SET status='Active',lastSeenAt=excluded.lastSeenAt", hh.id, userId, now());
          return snapshot(hh.id);
        }));
      }
      const match = path.match(/^\/v1\/households\/([a-zA-Z0-9_-]+)\/(sync|invitations|leave|members)$/);
      if (!match) fail(404, 'NOT_FOUND', 'Endpoint not found.');
      const [, hh, action] = match;
      const access = member(hh, userId);
      if (action === 'invitations' && req.method === 'POST') {
        member(hh, userId, true);
        const input = await body(req);
        const invited = one('SELECT id FROM users WHERE email=?', String(input.email || '').trim().toLowerCase());
        if (!invited) fail(404, 'ACCOUNT_NOT_FOUND', 'This email has no sharing account yet. Share the invitation link instead.');
        run("INSERT INTO members VALUES(?,?,'Member','Invited',NULL) ON CONFLICT(householdId,userId) DO NOTHING", hh, invited.id);
        return send(200, snapshot(hh));
      }
      if (action === 'leave' && req.method === 'POST') {
        transaction(() => {
          run('DELETE FROM members WHERE householdId=? AND userId=?', hh, userId);
          if (access.role === 'Owner') {
            const next = one("SELECT userId FROM members WHERE householdId=? AND status='Active' ORDER BY userId LIMIT 1", hh);
            if (next) run("UPDATE members SET role='Owner' WHERE householdId=? AND userId=?", hh, next.userId);
          }
        });
        return send(200, { ok: true });
      }
      if (action === 'members' && req.method === 'POST') {
        member(hh, userId, true); const input = await body(req);
        if (input.userId === userId) fail(400, 'VALIDATION', 'Use Leave household to leave.');
        run('DELETE FROM members WHERE householdId=? AND userId=?', hh, String(input.userId));
        return send(200, snapshot(hh));
      }
      if (action !== 'sync' || req.method !== 'POST') fail(404, 'NOT_FOUND', 'Endpoint not found.');
      const input = await body(req);
      if (!Array.isArray(input.operations) || input.operations.length > 200) fail(400, 'VALIDATION', 'Invalid synchronization batch.');
      const result = transaction(() => {
        const acknowledged = [];
        for (const op of input.operations) {
          if (!id(op.mutationId) || !id(op.id) || !collections.has(op.collection) || !Number.isSafeInteger(op.baseVersion) || op.baseVersion < 0 || typeof op.deleted !== 'boolean') fail(400, 'INVALID_RECORD', 'Invalid shared mutation.');
          // Device-private records and identities are not accepted in shared payloads.
          if (op.data && ['userId', 'householdId', 'scopeKey', 'password', 'passwordHash', 'token'].some(key => Object.hasOwn(op.data, key))) fail(400, 'PRIVATE_DATA', 'Only household record fields can be synchronized.');
          const previous = one('SELECT * FROM records WHERE householdId=? AND collection=? AND id=?', hh, op.collection, op.id);
          if (one('SELECT 1 FROM mutations WHERE householdId=? AND mutationId=?', hh, op.mutationId)) { acknowledged.push(op.mutationId); continue; }
          if ((op.collection === 'household' && access.role !== 'Owner') || (['household', 'settings', 'logs'].includes(op.collection) && op.deleted)) {
            acknowledged.push(op.mutationId); run('INSERT INTO mutations VALUES(?,?,?)', hh, op.mutationId, userId); continue;
          }
          if (['household', 'settings'].includes(op.collection) && op.id !== 'main') fail(400, 'INVALID_RECORD', 'Invalid household setting record.');
          if (op.collection === 'budget' && (!/^\d{4}-(0[1-9]|1[0-2])$/.test(op.id) || op.data?.monthYear && op.data.monthYear !== op.id)) fail(400, 'INVALID_RECORD', 'Invalid budget month.');
          if (!previous && one('SELECT COUNT(*) AS count FROM records WHERE householdId=?', hh).count >= 10000) fail(409, 'QUOTA', 'Household storage limit reached.');
          // A stale update cannot resurrect a deleted item. Logs are append-only audit records.
          if (previous?.deleted || op.collection === 'logs' && previous) {
            acknowledged.push(op.mutationId); run('INSERT INTO mutations VALUES(?,?,?)', hh, op.mutationId, userId); continue;
          }
          let data = previous ? JSON.parse(previous.data) : {};
          if (!op.deleted) {
            validatePatch(op.collection, op.data, !previous);
            data = { ...data, ...op.data };
            const quantityField = op.collection === 'inventory' ? 'quantity' : op.collection === 'shopping' ? 'quantityNeeded' : null;
            if (quantityField && previous && op.quantityDelta != null) {
              if (!integer(op.quantityDelta)) fail(400, 'INVALID_RECORD', 'Invalid quantity change.');
              data[quantityField] = Math.max(0, Math.min(2_000_000_000, JSON.parse(previous.data)[quantityField] + op.quantityDelta));
              if (op.collection === 'shopping') {
                const label = data.quantityLabel.match(/^(\d+(?:\.\d+)?)\s*(.*)$/);
                const expected = op.data.quantityLabel === undefined ? JSON.parse(previous.data).quantityNeeded : op.data.quantityNeeded;
                if (label && Number(label[1]) === expected) data.quantityLabel = String(data.quantityNeeded) + (label[2] ? ' ' + label[2] : '');
              }
            }
            if (op.collection === 'inventory' || op.collection === 'shopping') {
              const old = previous ? JSON.parse(previous.data) : null;
              const checked = op.collection === 'shopping' && Object.hasOwn(op.data, 'isChecked') &&
                (!old || old.isChecked !== data.isChecked || op.data.lastModifiedAction === (data.isChecked ? 'Checked' : 'Unchecked'));
              data.addedByName = old?.addedByName || one('SELECT name FROM users WHERE id=?', previous?.userId || userId).name;
              data.lastModifiedByName = one('SELECT name FROM users WHERE id=?', userId).name;
              data.lastModifiedAction = checked && (old || data.isChecked) ? (data.isChecked ? 'Checked' : 'Unchecked') : previous ? 'Updated' : 'Added';
              data.lastModifiedAt = now();
            }
            validatePatch(op.collection, data, true);
            const reference = op.collection === 'shopping' ? data.sourceId : op.collection === 'logs' ? data.itemId : null;
            if (reference && !one("SELECT 1 FROM records WHERE householdId=? AND collection='inventory' AND id=?", hh, reference)) fail(400, 'INVALID_REFERENCE', 'Inventory reference must belong to this household.');
          }
          putRecord(hh, op.collection, op.id, data, (previous?.version || 0) + 1, op.deleted, previous?.userId || userId);
          if (!op.deleted && op.collection === 'household') run('UPDATE households SET name=?,description=? WHERE id=?', data.name, data.description, hh);
          run('INSERT INTO mutations VALUES(?,?,?)', hh, op.mutationId, userId); acknowledged.push(op.mutationId);
        }
        run('UPDATE members SET lastSeenAt=? WHERE householdId=? AND userId=?', now(), hh, userId);
        return { ...snapshot(hh), acknowledged };
      });
      send(200, result);
    } catch (error) { send(error.status || 500, { code: error.code || 'SERVER_ERROR', message: error.status ? error.message : 'Sharing server could not complete the request.' }); }
  });
  return { server, close: async () => { await new Promise(resolve => server.close(resolve)); db.close(); } };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const data = process.env.SHARING_DATA_DIR || resolve(dirname(fileURLToPath(import.meta.url)), 'data');
  const service = createSharingServer({ databasePath: resolve(data, 'households.sqlite') });
  service.server.listen(Number(process.env.SHARING_PORT || 8790), process.env.SHARING_HOST || '0.0.0.0', () => console.log('Household sharing server listening on port ' + (process.env.SHARING_PORT || 8790)));
  for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, async () => { await service.close(); process.exit(0); });
}
