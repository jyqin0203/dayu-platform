import cn.edu.fudan.dayu.assetindex.application.*;
import cn.edu.fudan.dayu.assetindex.infrastructure.*;
import cn.edu.fudan.dayu.assetindex.api.*;
import cn.edu.fudan.dayu.assetindex.domain.*;
import cn.edu.fudan.dayu.catalog.application.*;
import cn.edu.fudan.dayu.catalog.infrastructure.*;
import cn.edu.fudan.dayu.shared.kernel.*;
import com.fasterxml.jackson.databind.*;
import com.zaxxer.hikari.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.jdbc.datasource.*;
import javax.sql.DataSource;
import java.sql.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.*;
import java.security.MessageDigest;

/** Real scanner/JDBC benchmark against a private socket DB, replaying frozen real file metadata.
 * Does not write source files; JSON reports contain aggregates only. DB resets are isolated by guards.
 */
public class ScanBenchmark {
 static final Path WORK=Path.of("/tmp/dayu-index-update-20261008");
 static final ObjectMapper JSON=new ObjectMapper();
 static final Map<String,Path> ROOTS=new LinkedHashMap<>();
 static {ROOTS.put("webp-preview",Path.of("/datapool/Data_pool/FDU-CRIAS/WebP/WebP_V2_Dpi500_4KM"));ROOTS.put("netcdf-data",Path.of("/datapool/Data_pool/FDU-CRIAS/netcdf"));}
 static class Metrics {long sql,sqlNs,commits,storeNs;Map<String,Long> kinds=new TreeMap<>();void reset(){sql=sqlNs=commits=storeNs=0;kinds.clear();}}
 static Object invoke(Object target,Method m,Object[] args) throws Throwable {try{return m.invoke(target,args);}catch(InvocationTargetException e){throw e.getCause();}}
 static Connection connection(Connection c,Metrics metric) {
  return (Connection)Proxy.newProxyInstance(ScanBenchmark.class.getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
   if(m.getName().equals("commit"))metric.commits++;
   Object out=invoke(c,m,a);
   if(out instanceof Statement st) {
    String sql=a!=null&&a.length>0&&a[0] instanceof String?(String)a[0]:"";
    Class<?> api=out instanceof CallableStatement?CallableStatement.class:out instanceof PreparedStatement?PreparedStatement.class:Statement.class;
    return Proxy.newProxyInstance(ScanBenchmark.class.getClassLoader(),new Class[]{api},(sp,sm,sa)->{
     if(!sm.getName().startsWith("execute"))return invoke(st,sm,sa);
     String query=sql.isEmpty()&&sa!=null&&sa.length>0&&sa[0] instanceof String?(String)sa[0]:sql;
     String kind=query.stripLeading().split("\\s+",2)[0].toUpperCase(Locale.ROOT);
     metric.sql++;metric.kinds.merge(kind,1L,Long::sum);long t=System.nanoTime();
     try{return invoke(st,sm,sa);}finally{metric.sqlNs+=System.nanoTime()-t;}
    });
   }return out;
  });
 }
 static void check(boolean b,String message){if(!b)throw new IllegalStateException(message);}
 static Map<String,List<FileInventory.FileEntry>> load() throws Exception {
  Path snapshot=WORK.resolve("frozen-files.jsonl");
  if(!Files.exists(snapshot)) {
   var lines=new ArrayList<String>();var inventory=new LocalFileInventory();
   for(var root:ROOTS.entrySet()) {
    boolean ok=inventory.visit(root.getValue(),f->{try{lines.add(JSON.writeValueAsString(Map.of("storage",root.getKey(),"path",f.relativePath(),"size",f.size(),"modified",f.modifiedAt().toString())));}catch(Exception e){throw new RuntimeException(e);}},e->{throw new IllegalStateException("Snapshot traversal failed");});check(ok,"Incomplete snapshot");
   }
   Files.write(snapshot,lines);
  }
  var out=new LinkedHashMap<String,List<FileInventory.FileEntry>>();ROOTS.keySet().forEach(k->out.put(k,new ArrayList<>()));
  for(String line:Files.readAllLines(snapshot)){var n=JSON.readTree(line);out.get(n.get("storage").asText()).add(new FileInventory.FileEntry(n.get("path").asText(),n.get("size").asLong(),Instant.parse(n.get("modified").asText())));}
  return out;
 }
 static String fingerprint(JdbcTemplate jdbc) throws Exception {
  var digest=MessageDigest.getInstance("SHA-256");
  // Ignore generated IDs/run timestamps, compare every business metadata field and product association.
  for(var row:jdbc.queryForList("SELECT storage_key,relative_path,file_name,asset_type,data_mode,cycle_time,valid_time,lead_minutes,file_size,checksum_sha256,dpi,file_modified_at,status,GROUP_CONCAT(p.product_id ORDER BY p.product_id) AS products FROM data_assets a LEFT JOIN data_asset_products p ON a.id=p.asset_id GROUP BY a.id ORDER BY BINARY storage_key,BINARY relative_path"))digest.update((row.toString()+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
  return HexFormat.of().formatHex(digest.digest());
 }
 public static void main(String[] args) throws Exception {
  String variant=args[0];check(Set.of("a","b").contains(variant),"Invalid variant");int round=Integer.parseInt(args[1]);
  String db="dayu_scan_"+variant;
  var all=load();var parser=new AssetFilenameParser();
  // Hold out one observed half-hour arrival group: realtime valid time / forecast cycle time.
  var arrivals=new TreeSet<Instant>();
  for(var f:all.get("webp-preview")){var p=parser.parse(f.relativePath(),AssetType.WEBP,500);check(!p.releaseControlled(),"Release-controlled files need frozen markers");arrivals.add(p.cycleTime()==null?p.validTime():p.cycleTime());}
  Instant newest=arrivals.last();Instant bucket=Instant.ofEpochSecond(Math.floorDiv(newest.getEpochSecond(),1800)*1800);
  var base=new LinkedHashMap<String,List<FileInventory.FileEntry>>();long held=0;
  for(var group:all.entrySet()) {
   var list=new ArrayList<FileInventory.FileEntry>();
   for(var f:group.getValue()) {
    var p=parser.parse(f.relativePath(),group.getKey().equals("webp-preview")?AssetType.WEBP:AssetType.NETCDF,500);
    Instant arrival=p.cycleTime()==null?p.validTime():p.cycleTime();
    boolean hold=group.getKey().equals("webp-preview")&&!arrival.isBefore(bucket)&&arrival.isBefore(bucket.plusSeconds(1800));
    if(hold)held++;else list.add(f);
   }base.put(group.getKey(),list);
  }
  HikariConfig config=new HikariConfig();config.setJdbcUrl("jdbc:mariadb://localhost/"+db+"?localSocket="+WORK.resolve("db.sock"));
  config.setUsername(db);config.setPassword(Files.readString(WORK.resolve("db-password")).strip());config.setMaximumPoolSize(2);config.setMinimumIdle(1);config.setConnectionInitSql("SET time_zone='+00:00'");
  try(var pool=new HikariDataSource(config)) {
   var metrics=new Metrics();DataSource ds=new AbstractDataSource(){public Connection getConnection() throws SQLException{return connection(pool.getConnection(),metrics);}public Connection getConnection(String u,String p)throws SQLException{throw new SQLException("Not supported");}};
   var jdbc=new JdbcTemplate(ds);check(jdbc.queryForObject("SELECT @@datadir",String.class).equals(WORK.resolve("db").toString()+"/"),"Unsafe DB directory");check(jdbc.queryForObject("SELECT DATABASE()",String.class).equals(db),"Unsafe schema");
   jdbc.update("DELETE FROM data_asset_products");jdbc.update("DELETE FROM data_assets");jdbc.update("DELETE FROM asset_scan_errors");jdbc.update("DELETE FROM asset_scan_runs");
   var named=new NamedParameterJdbcTemplate(ds);var real=new JdbcAssetIndexStore(named,new DataSourceTransactionManager(ds));
   AssetIndexStore store=(AssetIndexStore)Proxy.newProxyInstance(ScanBenchmark.class.getClassLoader(),new Class[]{AssetIndexStore.class},(p,m,a)->{long t=System.nanoTime();try{return invoke(real,m,a);}finally{metrics.storeNs+=System.nanoTime()-t;}});
   var catalog=new CatalogService(new JdbcCatalogRepository(named,JSON),(p)->{},e->{});
   var current=new java.util.concurrent.atomic.AtomicReference<Map<String,List<FileInventory.FileEntry>>>(base);
   FileInventory inventory=(root,consumer,errors)->{String key=ROOTS.entrySet().stream().filter(e->e.getValue().equals(root)).findFirst().orElseThrow().getKey();current.get().get(key).forEach(consumer);return true;};
   var settings=new IndexSettings();settings.setRoots(ROOTS);
   try(varCloser closer=new varCloser(new PersistentAssetScanner(store,inventory,catalog,settings,e->{}))) {
    var scanner=closer.scanner;var seed=scanner.runInitialFullScan();check(seed.errors().isEmpty(),"Seeding errors");
    long baseCount=base.values().stream().mapToLong(List::size).sum();check(seed.createdAssets()==baseCount,"Seed count");
    for(String phase:List.of("unchanged","new-half-hour")) {
     if(phase.equals("new-half-hour"))current.set(all);
     metrics.reset();long t=System.nanoTime();var result=scanner.runIncrementalScan(ScanTrigger.MANUAL);double ms=(System.nanoTime()-t)/1e6;
     check(result.errors().isEmpty(),"Scan errors");check(result.updatedAssets()==0&&result.removedWebpAssets()==0&&result.missingNetcdfAssets()==0,"Unexpected changes");
     check(result.createdAssets()==(phase.equals("unchanged")?0:held),"Inserted count");
     var out=new LinkedHashMap<String,Object>();out.put("variant",variant);out.put("round",round);out.put("phase",phase);out.put("wallMs",ms);out.put("storeMs",metrics.storeNs/1e6);out.put("jdbcExecuteMs",metrics.sqlNs/1e6);out.put("sqlExecutions",metrics.sql);out.put("sqlKinds",new TreeMap<>(metrics.kinds));out.put("commits",metrics.commits);out.put("scannedFiles",result.scannedFiles());out.put("created",result.createdAssets());out.put("heldOutHalfHour",bucket.toString());out.put("errors",result.errors().size());
     out.put("memory",Files.readAllLines(Path.of("/proc/self/status")).stream().filter(s->s.startsWith("VmRSS:")||s.startsWith("VmHWM:")).toList());out.put("heapUsed",java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
     out.put("assets",jdbc.queryForObject("SELECT COUNT(*) FROM data_assets",Long.class));out.put("links",jdbc.queryForObject("SELECT COUNT(*) FROM data_asset_products",Long.class));out.put("businessStateSha256",fingerprint(jdbc));
     long expected=phase.equals("unchanged")?baseCount:baseCount+held;check(((Long)out.get("assets"))==expected,"Asset count mismatch");
     Path target=WORK.resolve("result-"+variant+"-"+round+"-"+phase+".json");Files.writeString(target,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(out));System.out.println(JSON.writeValueAsString(out));
    }
   }
  }
 }
 static class varCloser implements AutoCloseable {final PersistentAssetScanner scanner;varCloser(PersistentAssetScanner s){scanner=s;}public void close(){scanner.close();}}
}
