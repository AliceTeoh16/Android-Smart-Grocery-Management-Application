import { DatabaseSync } from 'node:sqlite';
import { createHash } from 'node:crypto';
import { existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createTursoStorage, SHARING_TABLES, SHARING_COLUMNS, SHARING_SCHEMA } from '../sharing/storage.js';

const invalid = message => Object.assign(new Error(message), { safe: true });

/** Reads only the server schema. A phone's Room database is deliberately rejected before any upload. */
function readSnapshot(sourcePath) {
  if (!existsSync(sourcePath)) throw invalid('The sharing database snapshot was not found.');
  const source = new DatabaseSync(sourcePath, { readOnly: true });
  try {
    source.exec('BEGIN');
    for (const table of SHARING_TABLES) {
      const columns = source.prepare(`PRAGMA table_info(${table})`).all().map(column => column.name);
      if (JSON.stringify(columns) !== JSON.stringify(SHARING_COLUMNS[table])) {
        throw invalid('This is not the expected household server database. Phone Room databases must stay private.');
      }
    }
    if (source.prepare('PRAGMA quick_check').get().quick_check !== 'ok' || source.prepare('PRAGMA foreign_key_check').all().length) {
      throw invalid('The database snapshot failed its integrity check. Create a new consistent backup.');
    }
    const tables = {}, fingerprint = createHash('sha256'), counts = {};
    for (const table of SHARING_TABLES) {
      const columns = SHARING_COLUMNS[table];
      const rows = source.prepare(`SELECT ${columns.join(',')} FROM ${table} ORDER BY ${columns.join(',')}`).all();
      const values = rows.map(row => columns.map(column => row[column]));
      if (values.some(row => row.some(value => value !== null && !['number', 'string'].includes(typeof value)))) {
        throw invalid('The server snapshot contains unsupported values. No data was uploaded.');
      }
      fingerprint.update(JSON.stringify([table, values]));
      tables[table] = values; counts[table] = values.length;
    }
    return { tables, counts, snapshotHash: fingerprint.digest('hex') };
  } finally { source.close(); }
}

export async function importSharingSnapshot(sourcePath, target) {
  const { tables, counts, snapshotHash } = readSnapshot(sourcePath);
  return target.transaction(async () => {
    await target.exec(SHARING_SCHEMA + `CREATE TABLE IF NOT EXISTS deployment_imports(
      snapshotHash TEXT PRIMARY KEY,importedAt INTEGER NOT NULL);`);
    if (await target.one('SELECT 1 FROM deployment_imports WHERE snapshotHash=?', snapshotHash)) {
      return { alreadyImported: true, snapshotHash, counts };
    }
    const rows = await target.all(SHARING_TABLES.map(table => `SELECT '${table}' AS name,COUNT(*) AS n FROM ${table}`).join(' UNION ALL '));
    if (rows.some(row => row.n !== 0)) throw invalid('The hosted database already contains application data. Import was refused to avoid overwriting it.');
    let batch = [];
    for (const table of SHARING_TABLES) {
      const columns = SHARING_COLUMNS[table];
      const sql = `INSERT INTO ${table}(${columns.join(',')}) VALUES(${columns.map(() => '?').join(',')})`;
      for (const args of tables[table]) {
        batch.push({ sql, args });
        if (batch.length === 500) { await target.batch(batch); batch = []; }
      }
    }
    if (batch.length) await target.batch(batch);
    await target.run('INSERT INTO deployment_imports VALUES(?,?)', snapshotHash, Date.now());
    return { alreadyImported: false, snapshotHash, counts };
  });
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  let target;
  try {
    if (process.argv.length !== 3) throw invalid('Usage: node backend/deploy/import-turso.mjs SERVER_SNAPSHOT.sqlite');
    target = await createTursoStorage({ url: process.env.TURSO_DATABASE_URL, authToken: process.env.TURSO_AUTH_TOKEN });
    const result = await importSharingSnapshot(resolve(process.argv[2]), target);
    console.log(JSON.stringify({ status: result.alreadyImported ? 'already-imported' : 'imported', ...result }, null, 2));
  } catch (error) {
    console.error(error.safe ? error.message : 'Database import could not be confirmed. Keep setup mode enabled and retry the same snapshot. Credentials are not logged.');
    process.exitCode = 1;
  } finally { if (target) await target.close().catch(() => {}); }
}
