import {unpad} from './product-geometry.js';

// Compressed WebP survives decoded-pixel eviction; both caches are LRU and byte bounded.
const compressed=new Map(),decoded=new Map(),pending=new Map();
const MAX_BLOBS=96,MAX_BLOB_BYTES=48*1024*1024,MAX_IMAGES=10,MAX_IMAGE_BYTES=96*1024*1024;
const VERSION_TTL=3*60*1000;
const keyOf=(file,productId)=>productId+'|'+file;
function touch(map,key,value){map.delete(key);map.set(key,value);return value;}
function trim(map,maxCount,maxBytes){
  let bytes=[...map.values()].reduce((sum,entry)=>sum+entry.bytes,0);
  for(const [key,entry] of map){
    if(map.size<=maxCount&&bytes<=maxBytes)break;
    map.delete(key);bytes-=entry.bytes;if(map===compressed)decoded.delete(key);
  }
}
export function imageVersion(headers){
  const etag=headers.get('etag');
  if(etag)return 'etag:'+etag;
  const modified=headers.get('last-modified'),size=headers.get('content-length');
  return modified&&size?`modified:${modified}|size:${size}`:null;
}
function discard(key,entry){
  if(compressed.get(key)===entry){compressed.delete(key);decoded.delete(key);pending.get(key)?.controller.abort();}
}
async function validate(key,entry,signal){
  const response=await fetch('/'+entry.file,{method:'HEAD',cache:'no-store',signal});
  if(!response.ok)throw Error('图片版本检查暂时失败');
  if(signal?.aborted)throw new DOMException('已取消','AbortError');
  const version=imageVersion(response.headers);
  if(!version||version!==entry.version){discard(key,entry);return false;}
  entry.checkedAt=Date.now();return true;
}
/** Check only the decoded working set; compressed-only frames are checked on reuse after three minutes. */
export async function refreshFrameVersions(productId,files,signal){
  const changed=new Set(),allowed=new Set(files);
  for(const [key,entry] of [...compressed]){
    if(entry.productId!==productId)continue;
    if(!allowed.has(entry.file)){discard(key,entry);continue;}
    if(decoded.has(key)&&!await validate(key,entry,signal))changed.add(entry.file);
  }
  return changed;
}
export function cancelPendingFrames(){
  for(const entry of pending.values())entry.controller.abort();
  pending.clear();
}
export function clearFrameCache(){cancelPendingFrames();compressed.clear();decoded.clear();}
/** Returns only fresh decoded pixels, allowing immediate scrub preview without starting downloads. */
export function cachedFrame(file,productId){
  const key=keyOf(file,productId),entry=decoded.get(key);
  if(!entry||Date.now()-entry.source.checkedAt>=VERSION_TTL)return null;
  touch(decoded,key,entry);if(compressed.has(key))touch(compressed,key,entry.source);
  return entry.image;
}
export function prepareFrame(file,productId){
  const key=keyOf(file,productId),image=cachedFrame(file,productId);
  if(image)return Promise.resolve(image);
  if(pending.has(key))return pending.get(key).promise;
  const task={controller:new AbortController()};
  pending.set(key,task);
  task.promise=(async()=>{
    const signal=task.controller.signal;
    let source=compressed.get(key)||decoded.get(key)?.source;
    if(source&&Date.now()-source.checkedAt>=VERSION_TTL){
      // Remove stale cache only after validation; failure keeps the visible image intact.
      const response=await fetch('/'+file,{method:'HEAD',cache:'no-store',signal});
      if(!response.ok)throw Error('图片版本检查暂时失败');
      const version=imageVersion(response.headers);
      if(version&&version===source.version)source.checkedAt=Date.now();
      else {compressed.delete(key);decoded.delete(key);source=null;}
    }
    if(!source){
      const response=await fetch('/'+file,{signal,cache:'no-cache'});
      if(!response.ok)throw Error('图像加载失败，请重试');
      const blob=await response.blob();
      if(signal.aborted)throw new DOMException('已取消','AbortError');
      source={file,productId,blob,bytes:blob.size,version:imageVersion(response.headers),checkedAt:Date.now()};
    }
    if(signal.aborted)throw new DOMException('已取消','AbortError');
    touch(compressed,key,source);trim(compressed,MAX_BLOBS,MAX_BLOB_BYTES);
    const reused=decoded.get(key);
    if(reused?.source===source)return touch(decoded,key,reused).image;
    const url=URL.createObjectURL(source.blob);
    try{
      const image=new Image();image.src=url;await image.decode();
      if(signal.aborted)throw new DOMException('已取消','AbortError');
      const texture=unpad(image,productId);
      if(compressed.has(key))touch(decoded,key,{image:texture,source,bytes:texture.width*texture.height*4});
      trim(decoded,MAX_IMAGES,MAX_IMAGE_BYTES);
      return texture;
    }finally{URL.revokeObjectURL(url);}
  })().finally(()=>{if(pending.get(key)===task)pending.delete(key);});
  return task.promise;
}
export async function prefetchFrames(files,productId,isCurrent){
  for(const file of files){
    if(!isCurrent())return;
    try{await prepareFrame(file,productId);}catch{return;}
  }
}
