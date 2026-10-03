import { readFile, realpath } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

export const CESIUM_VERSION='1.146.0';
export const CESIUM_PREFIX=`/vendor/cesium/${CESIUM_VERSION}/`;

/** Expose only the browser distribution, never the whole dependency tree. */
export async function cesiumDirectory() {
  let metadata;
  try { metadata=JSON.parse(await readFile(new URL('./node_modules/cesium/package.json',import.meta.url),'utf8')); }
  catch { throw new Error('Cesium is not installed. Run npm ci --ignore-scripts in frontend/.'); }
  if(metadata.version!==CESIUM_VERSION) throw new Error('Cesium version differs from the pinned local resource URL.');
  return realpath(fileURLToPath(new URL('./node_modules/cesium/Build/Cesium/',import.meta.url)));
}
