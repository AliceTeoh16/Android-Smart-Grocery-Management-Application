import { spawn } from 'node:child_process';
import http from 'node:http';
import { mkdirSync } from 'node:fs';
import { isAbsolute, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const backendRoot = fileURLToPath(new URL('../', import.meta.url));

export function configuration(environment) {
  const port = Number(environment.PORT || 10000);
  if (!Number.isInteger(port) || port < 1 || port > 65535 || [8787, 8790].includes(port)) {
    throw new Error('PORT must be a valid public gateway port different from 8787 and 8790.');
  }
  if (!environment.SHARING_DATA_DIR || !isAbsolute(environment.SHARING_DATA_DIR)) {
    throw new Error('Set SHARING_DATA_DIR to an absolute directory on your persistent disk.');
  }
  return { port, importing: environment.SHARING_IMPORT_MODE === 'true' };
}

export function importServer() {
  return http.createServer((request, response) => {
    const health = request.method === 'GET' && request.url === '/health';
    response.writeHead(health ? 200 : 503, { 'content-type': 'application/json', 'cache-control': 'no-store' });
    response.end(JSON.stringify(health
      ? { ok: true, service: 'shelfwise-import', mode: 'database-import' }
      : { code: 'MAINTENANCE', message: 'ShelfWise is being set up. Please try again later.' }));
  });
}

/** Hosting supervisor only: the existing AI and household servers run as separate, unchanged processes. */
export function launchServices({ environment = process.env, spawnChild = spawn, finish = code => process.exit(code),
  shutdownMs = 10_000 } = {}) {
  const { port, importing } = configuration(environment);
  if (importing) throw new Error('Import mode must use the maintenance server, without opening the database.');
  if (!environment.GEMINI_API_KEY) throw new Error('Set the existing GEMINI_API_KEY in the hosting environment.');
  const children = [];
  let stopping = false, finished = false, exitCode = 0, timer;
  const complete = () => {
    if (!finished && stopping && children.every(child => child.exited)) {
      finished = true; clearTimeout(timer); finish(exitCode);
    }
  };
  const stop = (code = 0) => {
    if (stopping) return;
    stopping = true; exitCode = code;
    timer = setTimeout(() => {
      for (const child of children) if (!child.exited) child.process.kill('SIGKILL');
    }, shutdownMs);
    timer.unref();
    for (const child of children) if (!child.exited) child.process.kill('SIGTERM');
    complete();
  };
  const services = [
    { name: 'ai', script: 'server.js', directory: backendRoot, extra: { PORT: '8787' } },
    { name: 'sharing', script: 'server.js', directory: resolve(backendRoot, 'sharing'),
      extra: { SHARING_PORT: '8790', SHARING_HOST: '127.0.0.1' } },
    { name: 'gateway', script: 'server.js', directory: resolve(backendRoot, 'gateway'), extra: { PORT: String(port) } }
  ];
  try {
    for (const service of services) {
      const child = { process: spawnChild(process.execPath, [service.script], {
        cwd: service.directory, env: { ...environment, ...service.extra }, stdio: 'inherit'
      }), exited: false };
      children.push(child);
      child.process.once('error', () => {
        child.exited = true;
        console.error(`${service.name} could not start.`);
        stop(1); complete();
      });
      child.process.once('exit', () => {
        child.exited = true;
        if (!stopping) {
          console.error(`${service.name} stopped unexpectedly; restarting the service group is required.`);
          stop(1);
        }
        complete();
      });
    }
  } catch {
    console.error('Could not start the service group.'); stop(1);
  }
  return { children, stop };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const config = configuration(process.env);
    mkdirSync(process.env.SHARING_DATA_DIR, { recursive: true });
    if (config.importing) {
      const server = importServer();
      server.listen(config.port, '0.0.0.0', () => console.log('Database import mode: services are paused.'));
      for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, () => server.close(() => process.exit(0)));
    } else {
      const group = launchServices();
      for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, () => group.stop());
    }
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
