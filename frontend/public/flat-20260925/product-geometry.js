// Source: Re_FD_SmaAt_V1.3/Draw_FD.py and Trans_Pre_NC.py, inspected 2026-09-11.
const cpp=new Set(['RGB','FRGB','CLP','CTH','CBH','COT','CER','CWP']);
const padded=new Set(['CLP','CTH','COT','CER','CWP']);
export function geometryFor(id){
  if(id.startsWith('GLOBAL_'))return {bounds:[-180,-70.02,180,70.02],padding:0,verified:true};
  // Sample NetCDF coordinates are pixel centers, not outer edges (2401 at 0.05 deg).
  if(id==='SAMPLE_PRECIP')return {bounds:[59.975,-60.025,180.025,60.025],padding:0,verified:true};
  if(id.includes('PRECIP'))return {bounds:[60,-60,180,60],padding:0,verified:true};
  if(cpp.has(id))return {bounds:[23.98,-81.02,186.02,81.02],padding:padded.has(id)?50:0,verified:true};
  return {bounds:[23.6479215644,-81.3520784356,186.3520784356,81.3520784356],padding:0,verified:false};
}
export function containsPoint(bounds,lon,lat){if(lon<bounds[0])lon+=360;return lon>=bounds[0]&&lon<=bounds[2]&&lat>=bounds[1]&&lat<=bounds[3];}
export function unpad(image,id){
  const {padding}=geometryFor(id);
  if(!padding)return image;
  // Only apply the known Dpi500 Matplotlib exports, never infer margins from cloud extent.
  if((id==='CLP'&&image.width!==1948)||(id!=='CLP'&&image.width!==1255))throw Error('图像尺寸改变，需重新核对定位参数');
  const canvas=document.createElement('canvas');canvas.width=image.width-padding*2;canvas.height=image.height-padding*2;
  const ctx=canvas.getContext('2d');ctx.drawImage(image,padding,padding,canvas.width,canvas.height,0,0,canvas.width,canvas.height);return canvas;
}
