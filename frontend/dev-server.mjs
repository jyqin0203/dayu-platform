// Local development only. Serves the production UI with local Cesium and proxies to a loopback backend.
import http from 'node:http';
import { createReadStream } from 'node:fs';
import { realpath, stat } from 'node:fs/promises';
import { extname, isAbsolute, relative, resolve, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { CESIUM_PREFIX, cesiumDirectory } from './vendor.mjs';

const PUBLIC = fileURLToPath(new URL('./public/', import.meta.url));
const PROXIED = ['/api/', '/media/webp/', '/WebP/', '/CPP_Colorbar/', '/colorbars/'];
const TYPES = { '.html':'text/html; charset=utf-8', '.js':'application/javascript; charset=utf-8',
  '.css':'text/css; charset=utf-8', '.json':'application/json; charset=utf-8',
  '.geojson':'application/geo+json; charset=utf-8', '.ico':'image/x-icon',
  '.png':'image/png', '.jpg':'image/jpeg', '.jpeg':'image/jpeg', '.svg':'image/svg+xml',
  '.gif':'image/gif', '.webp':'image/webp', '.wasm':'application/wasm', '.ktx2':'image/ktx2',
  '.txt':'text/plain; charset=utf-8', '.xml':'application/xml; charset=utf-8' };

function safeHeaders(headers) {
  const result = { ...headers };
  const connection = String(result.connection || '').split(',').map(name => name.trim().toLowerCase());
  for (const name of [...connection, 'connection', 'keep-alive', 'proxy-authenticate',
    'proxy-authorization', 'te', 'trailer', 'transfer-encoding', 'upgrade']) delete result[name];
  return result;
}
function fail(response, status, message) {
  if (response.headersSent) { response.destroy(); return; }
  response.writeHead(status, { 'Content-Type':'application/json; charset=utf-8',
    'Cache-Control':'no-store', 'X-Content-Type-Options':'nosniff' });
  response.end(JSON.stringify({ ok:false, message }));
}

/** Input: public directory + loopback backend URL. Output: an unbound server, without side effects on backend data. */
export async function createDevServer({ publicRoot=PUBLIC, backendUrl='http://127.0.0.1:18080' } = {}) {
  const backend = new URL(backendUrl);
  if (backend.protocol !== 'http:' || !['127.0.0.1','localhost','[::1]'].includes(backend.hostname)
      || backend.username || backend.password || backend.pathname !== '/' || backend.search || backend.hash)
    throw new Error('Backend must be a loopback HTTP origin without credentials or a path.');
  const root = await realpath(publicRoot);
  const vendorRoot = await cesiumDirectory();

  return http.createServer(async (request, response) => {
    let path;
    try {
      if (!request.url?.startsWith('/') || request.url.startsWith('//')) throw new Error();
      path = decodeURIComponent(request.url.split('?')[0]);
      if (path.includes('\\') || /[\x00-\x1f\x7f]/.test(path)
          || path.split('/').some(part => part.startsWith('.'))) throw new Error();
    } catch { fail(response,400,'Invalid resource path.'); return; }

    if (PROXIED.some(prefix => path === prefix.slice(0,-1) || path.startsWith(prefix))) {
      const headers = safeHeaders(request.headers);
      headers.host = backend.host;
      const upstream = http.request(backend, { method:request.method, path:request.url, headers }, incoming => {
        response.writeHead(incoming.statusCode || 502, safeHeaders(incoming.headers));
        incoming.on('error', () => response.destroy());
        incoming.pipe(response);
      });
      // Stream requests/responses, including future scientific downloads; never log cookies or request bodies.
      upstream.setTimeout(30000, () => upstream.destroy(new Error('Backend timeout')));
      upstream.on('error', () => fail(response,502,'Local backend unavailable. Start the Java backend first.'));
      request.on('aborted', () => upstream.destroy());
      response.on('close', () => { if (!response.writableFinished) upstream.destroy(); });
      request.pipe(upstream);
      return;
    }

    if (!['GET','HEAD'].includes(request.method)) { fail(response,405,'Method not allowed.'); return; }
    const isVendor=path.startsWith(CESIUM_PREFIX);
    const selectedRoot=isVendor ? vendorRoot : root;
    const filePath = isVendor ? path.slice(CESIUM_PREFIX.length) : path === '/' ? 'index.html' : path.slice(1);
    const type = TYPES[extname(filePath).toLowerCase()];
    if (!type) { fail(response,404,'Resource not found.'); return; }
    try {
      const file = await realpath(resolve(selectedRoot,filePath));
      const inside = relative(selectedRoot,file);
      if (inside === '..' || inside.startsWith('..'+sep) || isAbsolute(inside)) {
        fail(response,404,'Resource not found.'); return;
      }
      const info = await stat(file);
      if (!info.isFile()) { fail(response,404,'Resource not found.'); return; }
      response.writeHead(200, { 'Content-Type':type, 'Content-Length':info.size,
        'Cache-Control':'no-store', 'X-Content-Type-Options':'nosniff' });
      if (request.method === 'HEAD') response.end();
      else createReadStream(file).on('error', () => response.destroy()).pipe(response);
    } catch { fail(response,404,'Resource not found.'); }
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const port = Number(process.env.DAYU_FRONTEND_PORT || 15173);
  if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('Invalid frontend port.');
  const backendUrl = process.env.DAYU_BACKEND_URL || 'http://127.0.0.1:18080';
  const server = await createDevServer({ backendUrl });
  server.on('error', error => { console.error(`Frontend server failed: ${error.code || 'startup error'}`); process.exitCode=1; });
  server.listen(port,'127.0.0.1', () => console.log(`Frontend: http://127.0.0.1:${port} | Backend proxy: ${backendUrl}`));
  for (const signal of ['SIGINT','SIGTERM']) process.on(signal, () => server.close(() => process.exit(0)));
}
