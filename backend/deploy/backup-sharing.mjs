import { DatabaseSync } from 'node:sqlite';
import { existsSync, mkdirSync, chmodSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

/** VACUUM INTO produces a consistent standalone snapshot, including committed WAL contents. */
export function backupSharing(source, destination) {
  source = resolve(source); destination = resolve(destination);
  if (!existsSync(source)) throw new Error('The source sharing database does not exist.');
  if (existsSync(destination)) throw new Error('The destination already exists; choose a new backup filename.');
  mkdirSync(dirname(destination), { recursive: true });
  const database = new DatabaseSync(source, { readOnly: true });
  try {
    database.exec('PRAGMA busy_timeout=5000');
    database.exec(`VACUUM INTO '${destination.replaceAll("'", "''")}'`);
  } finally { database.close(); }
  const snapshot = new DatabaseSync(destination, { readOnly: true });
  try {
    if (snapshot.prepare('PRAGMA quick_check').get().quick_check !== 'ok') throw new Error('Backup integrity check failed.');
  } finally { snapshot.close(); }
  chmodSync(destination, 0o600);
  return destination;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    if (process.argv.length !== 4) throw new Error('Usage: node backend/deploy/backup-sharing.mjs SOURCE.sqlite DESTINATION.sqlite');
    console.log('Verified database snapshot: ' + backupSharing(process.argv[2], process.argv[3]));
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
