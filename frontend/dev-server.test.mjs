import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { createDevServer } from './dev-server.mjs';
import { CESIUM_PREFIX } from './vendor.mjs';

async function listen(server) {
  await new Promise(resolve => server.listen(0,'127.0.0.1',resolve));
  return `http://127.0.0.1:${server.address().port}`;
}
async function close(server) {
  server.closeAllConnections();
  await new Promise(resolve => server.close(resolve));
}

test('original entry, base path, module and GeoJSON are served without rewriting', async () => {
  const server = await createDevServer();
  const base = await listen(server);
  try {
    const page = await fetch(base);
    assert.equal(page.status,200);
    assert.match(await page.text(), /<base href="\/flat-20260925\/">/);
    const module = await fetch(base+'/flat-20260925/app.js');
    assert.match(module.headers.get('content-type'),/javascript/);
    assert.match(await module.text(),/image\.decode\(\)/);
    const world = await fetch(base+'/flat-20260925/assets/world-boundaries.geojson');
    assert.equal(world.status,200);
    assert.equal((await world.json()).type,'FeatureCollection');
    assert.equal((await fetch(base+'/flat-20260925/style.css',{method:'HEAD'})).status,200);
  } finally { await close(server); }
});

test('proxy keeps paths, queries, CSRF, cookies and download bytes', async () => {
  const upstream = http.createServer((req,res) => {
    if (req.url === '/api/test-download') {
      res.writeHead(200, {'Content-Type':'application/octet-stream','Content-Disposition':'attachment; filename="test.bin"'});
      res.end(Buffer.from([0,1,2,127,128,254,255]));
      return;
    }
    const chunks=[];
    req.on('data',chunk => chunks.push(chunk));
    req.on('end',() => {
      res.writeHead(200, {'Content-Type':'application/json','Set-Cookie':'DAYUSESSID=test-only; Path=/; HttpOnly; SameSite=Lax'});
      res.end(JSON.stringify({url:req.url,csrf:req.headers['x-csrf-token'],cookie:req.headers.cookie,body:Buffer.concat(chunks).toString()}));
    });
  });
  const target = await listen(upstream);
  const server = await createDevServer({backendUrl:target});
  const base = await listen(server);
  try {
    const response = await fetch(base+'/api/auth.php?action=login',{method:'POST',
      headers:{'X-CSRF-Token':'fake-token','Cookie':'DAYUSESSID=fake-session','Content-Type':'application/json'},body:'{"test":true}'});
    assert.match(response.headers.get('set-cookie'),/HttpOnly/);
    assert.deepEqual(await response.json(),{url:'/api/auth.php?action=login',csrf:'fake-token',cookie:'DAYUSESSID=fake-session',body:'{"test":true}'});
    const download=await fetch(base+'/api/test-download');
    assert.match(download.headers.get('content-disposition'),/attachment/);
    assert.deepEqual(Buffer.from(await download.arrayBuffer()),Buffer.from([0,1,2,127,128,254,255]));
    for (const path of ['/WebP/frame.webp','/media/webp/frame.webp','/CPP_Colorbar/legend.webp','/colorbars/legend.png'])
      assert.equal((await (await fetch(base+path)).json()).url,path);
  } finally { await close(server); await close(upstream); }
});

test('does not expose project configuration, private paths or the classic page', async () => {
  const server = await createDevServer();
  const base = await listen(server);
  try {
    for (const path of ['/.env','/%2eenv','/backend/pom.xml','/netcdf/test.nc','/classic.html','/index_en.html'])
      assert.notEqual((await fetch(base+path)).status,200);
    const admin=await fetch(base+'/admin.html');
    assert.equal(admin.status,200);
    assert.match(await admin.text(),/id="admin-content" hidden/);
    const response=await fetch(base+'/flat-20260925/style.css',{method:'POST'});
    assert.equal(response.status,405);
  } finally { await close(server); }
});

test('refuses accidental production proxy targets and reports unavailable backend', async () => {
  await assert.rejects(createDevServer({backendUrl:'https://example.test'}),/loopback/);
  await assert.rejects(createDevServer({backendUrl:'http://user:secret@127.0.0.1:18080'}),/loopback/);
  const unused = http.createServer();
  const target = await listen(unused);
  await close(unused);
  const server=await createDevServer({backendUrl:target});
  const base=await listen(server);
  try { assert.equal((await fetch(base+'/api/products.php')).status,502); }
  finally { await close(server); }
});

test('local Cesium script, widgets and worker support assets resolve without a CDN', async () => {
  const server=await createDevServer();
  const base=await listen(server);
  try {
    const html=await (await fetch(base)).text();
    assert.ok(!html.includes('api.tianditu.gov.cn'));
    assert.ok(html.indexOf('/cesium-config.js') < html.indexOf(CESIUM_PREFIX+'Cesium.js'));
    const config=await (await fetch(base+'/cesium-config.js')).text();
    assert.ok(config.includes(CESIUM_PREFIX));
    for(const [file,mime] of [['Cesium.js','javascript'],['Widgets/widgets.css','text/css'],['Assets/Textures/NaturalEarthII/tilemapresource.xml','xml']]) {
      const result=await fetch(base+CESIUM_PREFIX+file,{method:'HEAD'});
      assert.equal(result.status,200,file);
      assert.ok(result.headers.get('content-type').includes(mime));
    }
    assert.equal((await fetch(base+'/node_modules/cesium/package.json')).status,404);
    assert.equal((await fetch(base+'/vendor/cesium/wrong-version/Cesium.js')).status,404);
  } finally { await close(server); }
});
