// Generate a standalone static site for Nginx. Only an explicitly marked dist directory may be replaced.
import { cp, lstat, mkdir, readFile, readdir, realpath, rm, writeFile } from 'node:fs/promises';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { CESIUM_VERSION, cesiumDirectory } from './vendor.mjs';

const root=await realpath(fileURLToPath(new URL('./',import.meta.url)));
const output=join(root,'dist');
const marker=join(output,'.dayu-build.json');
if(dirname(output)!==root || relative(root,output)!=='dist') throw new Error('Unsafe build output path.');
const distribution=await cesiumDirectory();
let existing;
try { existing=await lstat(output); } catch(error) { if(error.code!=='ENOENT') throw error; }
if(existing) {
  if(!existing.isDirectory() || existing.isSymbolicLink() || await realpath(output)!==output)
    throw new Error('Refusing to replace an output link or non-directory.');
  if((await readdir(output)).length) {
    let previous;
    try { previous=JSON.parse(await readFile(marker,'utf8')); } catch {}
    if(previous?.generatedBy!=='dayu-platform-frontend') throw new Error('Unrecognized dist contents; not deleting user files.');
  }
  // Resolved, dedicated generated directory was validated above; never remove a repo/home/parent directory.
  await rm(output,{recursive:true});
}
await mkdir(output,{recursive:true});
await writeFile(marker,JSON.stringify({generatedBy:'dayu-platform-frontend',cesium:CESIUM_VERSION},null,2));
await cp(join(root,'public'),output,{recursive:true});
const vendor=join(output,'vendor','cesium',CESIUM_VERSION);
await cp(distribution,vendor,{recursive:true});
for(const name of ['LICENSE.md','ThirdParty.json','ThirdParty.extra.json'])
  await cp(join(root,'node_modules','cesium',name),join(vendor,name));
console.log(`Built static site: ${output}`);
console.log(`Cesium ${CESIUM_VERSION} browser assets and license notices included; no node_modules, secrets or backend data copied.`);
