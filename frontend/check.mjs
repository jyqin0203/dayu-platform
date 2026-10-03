// Parse every first-party browser module without executing DOM code.
import {readdir} from 'node:fs/promises';
import {join} from 'node:path';
import {execFileSync} from 'node:child_process';
async function check(directory){let count=0;for(const item of await readdir(directory,{withFileTypes:true})){const path=join(directory,item.name);if(item.isDirectory())count+=await check(path);else if(item.name.endsWith('.js')){execFileSync(process.execPath,['--check',path],{stdio:'pipe'});count++;}}return count;}
console.log(`Checked ${await check('public')} browser scripts.`);
