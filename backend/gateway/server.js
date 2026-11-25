import http from 'node:http';
import https from 'node:https';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createReadStream } from 'node:fs';
import { stat } from 'node:fs/promises';

const hopHeaders = new Set(['connection', 'keep-alive', 'proxy-authenticate', 'proxy-authorization',
  'te', 'trailer', 'transfer-encoding', 'upgrade']);
function forwardedHeaders(headers) {
  const excluded = new Set([...hopHeaders, ...String(headers.connection || '').toLowerCase().split(',').map(s => s.trim())]);
  return Object.fromEntries(Object.entries(headers).filter(([key]) => !excluded.has(key.toLowerCase())));
}
function json(response, status, body) {
  response.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store' });
  response.end(JSON.stringify(body));
}

/** Only transports requests. Existing auth, sync, AI bodies and responses remain in their own services. */
export function createGateway({ aiOrigin = 'http://127.0.0.1:8787', sharingOrigin = 'http://127.0.0.1:8790',
  timeoutMs = 120_000, apkPath = fileURLToPath(new URL('../../build/phone-demo/ShelfWise-phone-debug.apk', import.meta.url)) } = {}) {
  const ai = new URL(aiOrigin), sharing = new URL(sharingOrigin);
  return http.createServer(async (request, response) => {
    const path = new URL(request.url, 'http://gateway').pathname;
    if (path === '/download/shelfwise.apk' && ['GET', 'HEAD'].includes(request.method)) {
      try {
        const file = await stat(apkPath);
        if (!file.isFile()) throw new Error('Not an APK file');
        response.writeHead(200, { 'content-type': 'application/vnd.android.package-archive',
          'content-length': file.size, 'content-disposition': 'attachment; filename="ShelfWise.apk"',
          'cache-control': 'no-store', 'x-content-type-options': 'nosniff' });
        if (request.method === 'HEAD') response.end();
        else {
          const stream = createReadStream(apkPath);
          stream.on('error', () => response.destroy());
          response.on('close', () => stream.destroy());
          stream.pipe(response);
        }
      } catch { json(response, 503, { message: 'The app download is being prepared. Please try again shortly.' }); }
      return;
    }
    if (request.method === 'GET' && path === '/health') {
      try {
        const [aiHealth, sharingHealth] = await Promise.all([ai, sharing].map(async origin => {
          const result = await fetch(new URL('/health', origin), { signal: AbortSignal.timeout(3000) });
          if (!result.ok) throw new Error('Unhealthy service');
          return result.json();
        }));
        // Preserve AI health capabilities used by the Android client.
        json(response, 200, { ...aiHealth, ok: aiHealth.ok === true && sharingHealth.service === 'household-sharing',
          service: 'shelfwise-gateway', sharing: sharingHealth.service });
      } catch {
        json(response, 503, { ok: false, service: 'shelfwise-gateway', message: 'A service is temporarily unavailable.' });
      }
      return;
    }
    const origin = path.startsWith('/v1/') || path === '/health/sharing' ? sharing : ai;
    const targetPath = path === '/health/sharing' || path === '/health/ai' ? '/health' : request.url;
    const transport = origin.protocol === 'https:' ? https : http;
    // Keep the original path; never resolve untrusted paths as a new upstream origin.
    const upstream = transport.request({ protocol: origin.protocol, hostname: origin.hostname, port: origin.port,
      method: request.method, path: targetPath, headers: { ...forwardedHeaders(request.headers), host: origin.host } }, incoming => {
      response.writeHead(incoming.statusCode, forwardedHeaders(incoming.headers));
      incoming.on('error', () => response.destroy());
      incoming.pipe(response);
    });
    const deadline = setTimeout(() => upstream.destroy(new Error('Gateway timeout')), timeoutMs);
    upstream.on('close', () => clearTimeout(deadline));
    upstream.on('error', () => {
      if (!response.headersSent && !response.destroyed) json(response, 503,
        { code: 'SERVICE_UNAVAILABLE', message: 'Cannot connect right now. Please try again shortly.' });
      else response.destroy();
    });
    request.on('aborted', () => upstream.destroy());
    response.on('close', () => { if (!response.writableFinished) upstream.destroy(); });
    request.pipe(upstream);
  });
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const port = Number(process.env.PORT || 8788);
  const server = createGateway(process.env.APK_PATH ? { apkPath: resolve(process.env.APK_PATH) } : {});
  server.listen(port, '0.0.0.0', () => console.log(`ShelfWise gateway listening on port ${port}`));
  for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, () => server.close(() => process.exit(0)));
}
