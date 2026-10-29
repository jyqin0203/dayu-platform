"""Read-only consistent metadata snapshot; never exports accounts or scientific file contents."""
import argparse,subprocess,json,hashlib,os,datetime,collections
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--source-client',required=True);p.add_argument('--private-dir',required=True);p.add_argument('--results-dir',required=True);a=p.parse_args()
os.umask(0o077);private=Path(a.private_dir);out=Path(a.results_dir)
assert not (private/'snapshot/metadata.jsonl').exists(), 'Do not overwrite a frozen snapshot'
cmd=['mariadb','--defaults-file='+a.source_client,'--batch','--raw','--skip-column-names']
def sql(q):
 r=subprocess.run(cmd,input=q,text=True,capture_output=True)
 if r.returncode:raise SystemExit('Snapshot SQL failed; details withheld')
 return r.stdout
assert sql('SELECT DATABASE(),@@port;').strip()=='dayu_test\t13306'
tables=['products','product_mode_policies','data_assets','data_asset_products','flyway_schema_history'];cols={};queries=[]
for t in tables:
 cols[t]=sql("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='"+t+"' ORDER BY ORDINAL_POSITION;").splitlines()
 fields=','.join("'%s',%s"%(c, 'NULL' if t=='products' and c in ['created_by','updated_by'] else '`'+c+'`') for c in cols[t])
 queries.append("SELECT JSON_OBJECT('table','"+t+"','row',JSON_OBJECT("+fields+")) FROM `"+t+'`')
raw=sql("SET SESSION time_zone='+00:00'; START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;"+';'.join(queries)+'; COMMIT;')
(private/'snapshot/metadata.jsonl').write_text(raw);(private/'snapshot/columns.json').write_text(json.dumps(cols))
schema=subprocess.run(['mariadb-dump','--defaults-file='+a.source_client,'--no-data','--skip-lock-tables','dayu_test'],capture_output=True,text=True,check=True).stdout
(private/'snapshot/schema.sql').write_text(schema)
data={t:[] for t in tables}
for line in raw.splitlines():v=json.loads(line);data[v['table']].append(v['row'])
roots={'webp-preview':Path('/datapool/Data_pool/FDU-CRIAS/WebP/WebP_V2_Dpi500_4KM'),'netcdf-data':Path('/datapool/Data_pool/FDU-CRIAS/netcdf')}
groups={};physical={};seen=set()
for v in data['data_assets']:
 k=v['asset_type']+'/'+v['data_mode']+'/'+v['status'];g=groups.setdefault(k,{'indexRows':0,'indexedBytes':0,'existingFiles':0,'existingBytes':0,'missingPaths':0,'sizeMismatch':0})
 g['indexRows']+=1;g['indexedBytes']+=v['file_size']
 assert v['storage_key'] in roots
 rel=Path(v['relative_path']);assert not rel.is_absolute() and '..' not in rel.parts
 path=roots[v['storage_key']]/rel
 try:
  stat=path.stat();g['existingFiles']+=1;g['existingBytes']+=stat.st_size;g['sizeMismatch']+=stat.st_size!=v['file_size'];physical[(stat.st_dev,stat.st_ino)]=stat.st_size
 except FileNotFoundError:g['missingPaths']+=1
 seen.add((v['storage_key'],v['relative_path']))
nc=[v for v in data['data_assets'] if v['asset_type']=='NETCDF']
result={'capturedUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'snapshotSha256':hashlib.sha256(raw.encode()).hexdigest(),'tableRows':{t:len(v) for t,v in data.items()},'groups':groups,'uniqueIndexedPaths':len(seen),'uniqueExistingPhysicalFiles':len(physical),'uniqueExistingPhysicalBytes':sum(physical.values()),'netcdfTimeMin':min(v['valid_time'] for v in nc),'netcdfTimeMax':max(v['valid_time'] for v in nc),'syntheticAssetRows':0,'classificationBasis':'All indexed paths use previously approved real-data roots; exact-path stat only, no recursive discovery or scientific contents read. No synthetic records added. Tests from temporary test databases excluded.','capacityMeaning':'Bytes of indexed physical files, not database storage and not all files present on server; each inode counted once; stat occurs after DB snapshot so rolling WebP retention can race.'}
(out/'inventory.json').write_text(json.dumps(result,indent=2));print(json.dumps(result,indent=2))
