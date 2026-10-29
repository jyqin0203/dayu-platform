"""Add/drop exactly one approved experimental index on guarded isolated database 13307."""
from pathlib import Path
import argparse,json,subprocess,time
p=argparse.ArgumentParser();p.add_argument('action',choices=['add','drop']);p.add_argument('--label',required=True);a=p.parse_args()
out=Path(__file__).resolve().parents[1]/'results';root=Path('/home/ubuntu/dayu-test/performance/nc-search-20261007')
assert not (out/f'{a.label}-index-change.json').exists()
cmd=['mariadb','--defaults-file='+str(root/'config/client.cnf'),'--batch','--raw','--skip-column-names']
def sql(q):return subprocess.run(cmd,input=q,text=True,capture_output=True,check=True).stdout
assert sql('SELECT @@port,@@datadir;').strip()==f'13307\t{root}/db/'
name='idx_assets_type_mode_status_lead_time'
exists=sql("SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='dayu_perf' AND TABLE_NAME='data_assets' AND INDEX_NAME='"+name+"';").strip()
assert exists==('0' if a.action=='add' else '6')
def state():
 return {'checksums':sql('CHECKSUM TABLE dayu_perf.data_assets EXTENDED; CHECKSUM TABLE dayu_perf.data_asset_products EXTENDED;'), 'sizes':sql("SELECT TABLE_NAME,TABLE_ROWS,DATA_LENGTH,INDEX_LENGTH FROM information_schema.TABLES WHERE TABLE_SCHEMA='dayu_perf' AND TABLE_NAME IN ('data_assets','data_asset_products');"), 'indexPages':sql("SELECT index_name,stat_value FROM mysql.innodb_index_stats WHERE database_name='dayu_perf' AND table_name='data_assets' AND stat_name='size' ORDER BY index_name;")}
before=state();start=time.perf_counter()
statement=('CREATE INDEX '+name+' ON dayu_perf.data_assets(asset_type,data_mode,status,lead_minutes,valid_time,id)' if a.action=='add' else 'DROP INDEX '+name+' ON dayu_perf.data_assets')
sql(statement+';');elapsed=time.perf_counter()-start;after=state();assert before['checksums']==after['checksums']
(out/f'{a.label}-index-change.json').write_text(json.dumps({'action':a.action,'statement':statement,'elapsedSeconds':elapsed,'before':before,'after':after,'rowChecksumsUnchanged':True},indent=2));print(a.action,'candidate index; row checksums unchanged')
