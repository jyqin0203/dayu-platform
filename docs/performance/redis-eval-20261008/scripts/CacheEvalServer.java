import cn.edu.fudan.dayu.DayuApplication;
import cn.edu.fudan.dayu.discovery.application.DiscoveryCachePort;
import cn.edu.fudan.dayu.assetindex.api.*;
import cn.edu.fudan.dayu.catalog.api.*;
import cn.edu.fudan.dayu.shared.kernel.*;
import org.springframework.boot.SpringApplication;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.sql.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Evaluation-only launcher: original app classes, JDBC/cache counters, localhost control port.
 * No production source modifications. Fixture actions guard the isolated DB directory and schema.
 */
public class CacheEvalServer {
 static final Path ROOT=Path.of("/tmp/dayu-redis-eval-20261008");
 static final LongAdder sql=new LongAdder(),selects=new LongAdder(),reads=new LongAdder(),hits=new LongAdder(),misses=new LongAdder();
 static Object call(Object target,Method m,Object[] a)throws Throwable{try{return m.invoke(target,a);}catch(InvocationTargetException e){throw e.getCause();}}
 static Connection counted(Connection c){return (Connection)Proxy.newProxyInstance(CacheEvalServer.class.getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
  Object out=call(c,m,a);
  if(out instanceof Statement statement){String query=a!=null&&a.length>0&&a[0] instanceof String?(String)a[0]:"";
   return Proxy.newProxyInstance(CacheEvalServer.class.getClassLoader(),new Class[]{out instanceof PreparedStatement?PreparedStatement.class:Statement.class},(s,method,args)->{
    if(method.getName().startsWith("execute")){sql.increment();String q=query.isEmpty()&&args!=null&&args.length>0&&args[0] instanceof String?(String)args[0]:query;if(q.stripLeading().toUpperCase(Locale.ROOT).startsWith("SELECT"))selects.increment();}
    return call(statement,method,args);
   });
  }return out;
 });}
 static void reset(){sql.reset();selects.reset();reads.reset();hits.reset();misses.reset();}
 static Map<String,Object> metrics(){return Map.of("sql",sql.sum(),"selects",selects.sum(),"cacheReads",reads.sum(),"cacheHits",hits.sum(),"cacheMisses",misses.sum());}
 public static void main(String[] args)throws Exception{
  String mode=args[0];if(!Set.of("off","on").contains(mode))throw new IllegalArgumentException();boolean enabled=mode.equals("on");
  var app=new SpringApplication(DayuApplication.class);
  app.addInitializers(ctx->ctx.getBeanFactory().addBeanPostProcessor(new BeanPostProcessor(){
   public Object postProcessAfterInitialization(Object bean,String name){
    if(bean instanceof DataSource dataSource)return new DelegatingDataSource(dataSource){
     public Connection getConnection()throws SQLException{return counted(super.getConnection());}
     public Connection getConnection(String u,String p)throws SQLException{return counted(super.getConnection(u,p));}
    };
    if(bean instanceof DiscoveryCachePort cache)return Proxy.newProxyInstance(CacheEvalServer.class.getClassLoader(),new Class[]{DiscoveryCachePort.class},(p,m,a)->{
     Object value=call(cache,m,a);
     if(enabled&&value instanceof DiscoveryCachePort.Read<?> r){reads.increment();if(r.value().isPresent())hits.increment();else misses.increment();}
     return value;
    });
    return bean;
   }
  }));
  var ctx=app.run("--spring.config.additional-location="+ROOT.resolve(mode+".properties").toUri());
  var jdbc=new JdbcTemplate(ctx.getBean(DataSource.class));
  guard(jdbc);
  var json=new ObjectMapper().findAndRegisterModules();
  var control=HttpServer.create(new InetSocketAddress("127.0.0.1",enabled?18583:18582),0);
  control.createContext("/",exchange->{
   Object result;int status=200;
   try{
    String path=exchange.getRequestURI().getPath();
    if(path.equals("/metrics"))result=metrics();
    else if(path.equals("/reset")){reset();result=Map.of("reset",true);}
    else if(enabled&&path.equals("/fixture/reset")){
     guard(jdbc);jdbc.update("DELETE FROM data_assets");
     // Only synthetic files created by this launcher live here. First run requires empty roots.
     try(var files=Files.walk(ROOT.resolve("fixtures"))){if(files.anyMatch(Files::isRegularFile))throw new IllegalStateException("Fixture directory already populated");}
     Instant cycle=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.HOURS).minusSeconds(7200);
     Files.writeString(ROOT.resolve("fixture-cycle"),cycle.toString());writeFixture(cycle,1);
     var scan=ctx.getBean(AssetIndexCommandService.class).runInitialFullScan();if(!scan.errors().isEmpty())throw new IllegalStateException("Fixture scan failed");
     result=Map.of("cycle",cycle.toString(),"created",scan.createdAssets());
    }else if(enabled&&path.startsWith("/fixture/add/")){
     guard(jdbc);int lead=Integer.parseInt(path.substring("/fixture/add/".length()));if(lead<2||lead>3)throw new IllegalArgumentException();
     Instant cycle=Instant.parse(Files.readString(ROOT.resolve("fixture-cycle")));writeFixture(cycle,lead);
     var scan=ctx.getBean(AssetIndexCommandService.class).runIncrementalScan(ScanTrigger.MANUAL);if(!scan.errors().isEmpty())throw new IllegalStateException("Fixture scan failed");result=Map.of("created",scan.createdAssets());
    }else if(enabled&&path.equals("/fixture/disable")){
     guard(jdbc);var catalog=ctx.getBean(CatalogQueryService.class);var id=catalog.findProduct(new ProductCode("BT855")).orElseThrow().summary().id();
     ctx.getBean(CatalogAdminService.class).disableProduct(id,new ActorContext(new UserId(1),"Synthetic",UserRole.ADMIN));result=Map.of("disabled",true);
    }else {status=404;result=Map.of("error","Unknown evaluation action");}
   }catch(Exception e){status=500;result=Map.of("error",e.getClass().getSimpleName());e.printStackTrace();}
   byte[] bytes=json.writeValueAsBytes(result);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,bytes.length);try(var body=exchange.getResponseBody()){body.write(bytes);}
  });
  control.start();Runtime.getRuntime().addShutdownHook(new Thread(()->control.stop(0)));
  Files.writeString(ROOT.resolve(mode+".pid"),Long.toString(ProcessHandle.current().pid()));
  System.out.println("ISOLATED_CACHE_EVAL_READY "+mode);
 }
 static void guard(JdbcTemplate jdbc){if(!jdbc.queryForObject("SELECT @@datadir",String.class).equals(ROOT.resolve("db")+"/")||!jdbc.queryForObject("SELECT DATABASE()",String.class).equals("dayu_cache_eval"))throw new IllegalStateException("Unsafe DB");}
 static void writeFixture(Instant cycle,int lead)throws Exception{
  var f=DateTimeFormatter.ofPattern("uuuuMMddHHmm").withZone(ZoneOffset.UTC);String stamp=f.format(cycle),valid=f.format(cycle.plusSeconds(lead*3600L));
  Path file=ROOT.resolve("fixtures/webp/forecast/"+stamp+"/BT855/FY4B_AGRI_BT855_"+stamp+"_"+valid+"_Dpi500.webp");Files.createDirectories(file.getParent());Files.writeString(file,"synthetic-cache-fixture");
 }
}
