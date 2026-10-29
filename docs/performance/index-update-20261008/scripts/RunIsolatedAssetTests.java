import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Runs the repository's actual test methods/assertions against an existing private MariaDB.
 * Replaces only Testcontainers provisioning and @TempDir injection; never contacts production.
 * Reports separately from Maven/JUnit-engine tests. Retains fixtures for review.
 */
public class RunIsolatedAssetTests {
 public static void main(String[] args) throws Exception {
  Path work=Path.of("/tmp/dayu-index-update-20261008");
  var ds=new DriverManagerDataSource("jdbc:mariadb://localhost/dayu_scan_correctness?localSocket="+work.resolve("db.sock"),
      "dayu_scan_correctness",Files.readString(work.resolve("db-password")).strip());
  var jdbc=new JdbcTemplate(ds);
  if(!jdbc.queryForObject("SELECT @@datadir",String.class).equals(work.resolve("db")+"/")
     ||!jdbc.queryForObject("SELECT DATABASE()",String.class).equals("dayu_scan_correctness"))throw new IllegalStateException("Unsafe test DB");
  Flyway.configure().dataSource(ds).load().migrate();
  Class<?> type=Class.forName("cn.edu.fudan.dayu.assetindex.infrastructure.AssetIndexIntegrationTest");
  for(String name:List.of("ds","sql")){Field f=type.getDeclaredField(name);f.setAccessible(true);f.set(null,name.equals("ds")?ds:jdbc);}
  var results=new ArrayList<Map<String,Object>>();
  for(Method test:Arrays.stream(type.getDeclaredMethods()).filter(m->m.isAnnotationPresent(Test.class)).sorted(Comparator.comparing(Method::getName)).toList()) {
   var ctor=type.getDeclaredConstructor();ctor.setAccessible(true);Object instance=ctor.newInstance();
   Field temp=type.getDeclaredField("temp");temp.setAccessible(true);temp.set(instance,Files.createTempDirectory(work,"correctness-"));
   long start=System.nanoTime();String status="PASS";
   try {
    for(Method m:type.getDeclaredMethods())if(m.isAnnotationPresent(BeforeEach.class)){m.setAccessible(true);m.invoke(instance);}
    test.setAccessible(true);test.invoke(instance);
   } catch(InvocationTargetException failure){status="FAIL";failure.getCause().printStackTrace();}
   finally {for(Method m:type.getDeclaredMethods())if(m.isAnnotationPresent(AfterEach.class)){m.setAccessible(true);m.invoke(instance);}}
   results.add(Map.of("test",test.getName(),"status",status,"ms",(System.nanoTime()-start)/1e6));
   System.out.println(status+" "+test.getName());
  }
  Files.writeString(work.resolve("correctness-results.json"),new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(results));
  if(results.stream().anyMatch(r->r.get("status").equals("FAIL")))throw new IllegalStateException("Integration assertions failed");
 }
}
