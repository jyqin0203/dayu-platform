// Optional maintenance tool: pass the path to an installed Playwright ESM entrypoint.
// The generated PNG is a Natural Earth basemap, not meteorological/scientific data.
import {readFile,writeFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {pathToFileURL} from 'node:url';
import {resolve} from 'node:path';
const {chromium}=await import(process.argv[2]?pathToFileURL(resolve(process.argv[2])).href:'playwright');
const assets=new URL('./public/flat-20260925/assets/',import.meta.url);
const source=await readFile(new URL('world-boundaries.geojson',assets));
const browser=await chromium.launch({headless:true,args:['--no-sandbox']});
let png;
try{
  const page=await browser.newPage();
  const data=await page.evaluate(geojson=>{
    const canvas=document.createElement('canvas');canvas.width=4096;canvas.height=2048;
    const ctx=canvas.getContext('2d');ctx.fillStyle='#102332';ctx.fillRect(0,0,canvas.width,canvas.height);
    const x=lon=>(lon+180)/360*canvas.width,y=lat=>(90-lat)/180*canvas.height;
    for(const feature of geojson.features){
      ctx.beginPath();
      for(const ring of feature.geometry.coordinates){
        ring.forEach(([lon,lat],i)=>{if(i===0)ctx.moveTo(x(lon),y(lat));else ctx.lineTo(x(lon),y(lat));});ctx.closePath();
      }
      ctx.fillStyle='#2b414d';ctx.fill('evenodd');
    }
    return canvas.toDataURL('image/png').split(',')[1];
  },JSON.parse(source));
  png=Buffer.from(data,'base64');
}finally{await browser.close();}
const hash=buffer=>createHash('sha256').update(buffer).digest('hex');
const name=`world-basemap.${hash(png).slice(0,16)}.png`;
await writeFile(new URL(name,assets),png);
await writeFile(new URL('./public/flat-20260925/basemap-resource.js',import.meta.url),
  `// Generated from world-boundaries.geojson SHA256 ${hash(source)}; Natural Earth 1:50m.\nexport const BASEMAP_URL='assets/${name}';\n`);
console.log(`Generated ${name} (${png.length} bytes), preserving the original canvas drawing rules.`);
