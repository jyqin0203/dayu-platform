import cn.edu.fudan.dayu.assetindex.application.*;
import cn.edu.fudan.dayu.assetindex.infrastructure.*;
import cn.edu.fudan.dayu.assetindex.api.*;
import cn.edu.fudan.dayu.catalog.application.*;
import cn.edu.fudan.dayu.catalog.infrastructure.*;
import com.zaxxer.hikari.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.jdbc.datasource.*;
import javax.sql.DataSource;
import java.sql.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.security.MessageDigest;

/** Full live read-only traversal plus real writes to the isolated DB; never writes source files.
 * Measures three unchanged scans after one untimed priming scan; dataset fingerprints detect drift.
 */
public class LiveScanProfile {
 public static void main(String[] args) throws Exception {
  String v=args[0];ScanBenchmark.check(Set.of("a","b").contains(v),"Invalid variant");String db="dayu_scan_"+v;
  var config=new HikariConfig();config.setJdbcUrl("jdbc:mariadb://localhost/"+db+"?localSocket="+ScanBenchmark.WORK.resolve("db.sock"));
  config.setUsername(db);config.setPassword(Files.readString(ScanBenchmark.WORK.resolve("db-password")).strip());config.setMaximumPoolSize(2);config.setMinimumIdle(1);config.setConnectionInitSql("SET time_zone='+00:00'");
  try(var pool=new HikariDataSource(config)) {
   var metrics=new ScanBenchmark.Metrics();DataSource ds=new AbstractDataSource(){public Connection getConnection()throws SQLException{return ScanBenchmark.connection(pool.getConnection(),metrics);}public Connection getConnection(String u,String p)throws SQLException{throw new SQLException("Unsupported");}};
   var jdbc=new JdbcTemplate(ds);ScanBenchmark.check(jdbc.queryForObject("SELECT @@datadir",String.class).equals(ScanBenchmark.WORK.resolve("db")+"/"),"Unsafe directory");ScanBenchmark.check(jdbc.queryForObject("SELECT DATABASE()",String.class).equals(db),"Unsafe database");
   var named=new NamedParameterJdbcTemplate(ds);var real=new JdbcAssetIndexStore(named,new DataSourceTransactionManager(ds));
   var store=(AssetIndexStore)Proxy.newProxyInstance(LiveScanProfile.class.getClassLoader(),new Class[]{AssetIndexStore.class},(p,m,a)->{long t=System.nanoTime();try{return ScanBenchmark.invoke(real,m,a);}finally{metrics.storeNs+=System.nanoTime()-t;}});
   var catalog=new CatalogService(new JdbcCatalogRepository(named,ScanBenchmark.JSON),p->{},e->{});
   var local=new LocalFileInventory();long[] ioNs={0};var metadata=new TreeMap<String,String>();
   FileInventory inventory=new FileInventory(){
    public boolean visit(Path root,java.util.function.Consumer<FileEntry> consumer,java.util.function.Consumer<AssetScanError> errors) {
     long[] callback={0};long start=System.nanoTime();
     try{return local.visit(root,f->{long t=System.nanoTime();try {metadata.put(root.getFileName()+"/"+f.relativePath(),f.size()+"/"+f.modifiedAt());consumer.accept(f);}finally{callback[0]+=System.nanoTime()-t;}},errors);}
     finally{ioNs[0]+=System.nanoTime()-start-callback[0];}
    }
    public ReleaseSnapshot inspectReppicCycle(Path root,java.time.Instant cycle){return local.inspectReppicCycle(root,cycle);}
   };
   var settings=new IndexSettings();settings.setRoots(ScanBenchmark.ROOTS);
   var scanner=new PersistentAssetScanner(store,inventory,catalog,settings,e->{});
   try {
    String previous=null;
    for(int round=0;round<=3;round++) {
     metrics.reset();metadata.clear();ioNs[0]=0;long start=System.nanoTime();var result=scanner.runIncrementalScan(ScanTrigger.MANUAL);double ms=(System.nanoTime()-start)/1e6;
     var digest=MessageDigest.getInstance("SHA-256");metadata.forEach((k,val)->digest.update((k+"\t"+val+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)));String signature=HexFormat.of().formatHex(digest.digest());
     var out=new LinkedHashMap<String,Object>();out.put("variant",v);out.put("round",round);out.put("priming",round==0);out.put("wallMs",ms);out.put("inventoryExclusiveMs",ioNs[0]/1e6);out.put("storeMs",metrics.storeNs/1e6);out.put("jdbcExecuteMs",metrics.sqlNs/1e6);out.put("sqlExecutions",metrics.sql);out.put("commits",metrics.commits);out.put("scannedFiles",result.scannedFiles());out.put("created",result.createdAssets());out.put("updated",result.updatedAssets());out.put("removed",result.removedWebpAssets());out.put("missing",result.missingNetcdfAssets());out.put("errors",result.errors().size());out.put("sourceMetadataSha256",signature);out.put("stableSincePrevious",previous==null||previous.equals(signature));out.put("memory",Files.readAllLines(Path.of("/proc/self/status")).stream().filter(s->s.startsWith("VmRSS:")||s.startsWith("VmHWM:")).toList());out.put("businessStateSha256",ScanBenchmark.fingerprint(jdbc));out.put("assetRows",jdbc.queryForObject("SELECT COUNT(*) FROM data_assets",Long.class));out.put("linkRows",jdbc.queryForObject("SELECT COUNT(*) FROM data_asset_products",Long.class));
     Files.writeString(ScanBenchmark.WORK.resolve("live-"+v+"-"+round+".json"),ScanBenchmark.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(out));System.out.println(ScanBenchmark.JSON.writeValueAsString(out));previous=signature;
     ScanBenchmark.check(result.errors().isEmpty(),"Live scan failed");
    }
   } finally {scanner.close();}
  }
 }
}
