import cn.edu.fudan.dayu.assetindex.infrastructure.LocalFileInventory;
import cn.edu.fudan.dayu.assetindex.domain.AssetFilenameParser;
import cn.edu.fudan.dayu.shared.kernel.AssetType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/** Read-only filesystem stage probe; deliberately does not represent a DB scan benchmark. */
public class ReadOnlyScanProfile {
    public static void main(String[] args) throws Exception {
        if(args.length!=2) throw new IllegalArgumentException("WEBP_ROOT NC_ROOT required");
        var inventory=new LocalFileInventory();
        var parser=new AssetFilenameParser();
        var json=new ObjectMapper();
        for(int round=1;round<=3;round++) for(int kind=0;kind<2;kind++) {
            var type=kind==0?AssetType.WEBP:AssetType.NETCDF;
            var root=Path.of(args[kind]);
            long[] c=new long[7]; // files,bytes,callbackNs,parseNs,parseErrors,visitErrors,parsed
            var cycles=new TreeSet<Instant>();
            var metadata=new TreeMap<String,String>();
            long start=System.nanoTime();
            boolean complete=inventory.visit(root,f->{
                long callback=System.nanoTime();
                if(f.relativePath().endsWith(type==AssetType.WEBP?".webp":".nc")) {
                    c[0]++;c[1]+=f.size();
                    long p=System.nanoTime();
                    try {var asset=parser.parse(f.relativePath(),type,500);c[6]++;
                        if(asset.releaseControlled()) cycles.add(asset.cycleTime());
                    } catch(RuntimeException e){c[4]++;}
                    c[3]+=System.nanoTime()-p;
                    metadata.put(f.relativePath(),f.size()+"\t"+f.modifiedAt());
                }
                c[2]+=System.nanoTime()-callback;
            },e->c[5]++);
            long visit=System.nanoTime()-start;
            var releases=new TreeMap<String,Integer>();
            start=System.nanoTime();
            for(var cycle:cycles) releases.merge(inventory.inspectReppicCycle(root,cycle).status().name(),1,Integer::sum);
            long release=System.nanoTime()-start;
            var digest=MessageDigest.getInstance("SHA-256");
            metadata.forEach((k,v)->digest.update((k+"\t"+v+"\n").getBytes(StandardCharsets.UTF_8)));
            var out=new LinkedHashMap<String,Object>();
            out.put("at",Instant.now().toString());out.put("round",round);out.put("type",type.name());
            out.put("files",c[0]);out.put("logicalBytes",c[1]);out.put("parsed",c[6]);
            out.put("parseErrors",c[4]);out.put("visitErrors",c[5]);out.put("complete",complete);
            out.put("inventoryExclusiveMs",(visit-c[2])/1e6);out.put("parseMs",c[3]/1e6);
            out.put("releaseMs",release/1e6);out.put("visitWallMs",visit/1e6);
            out.put("releaseCycles",releases);out.put("metadataSha256",HexFormat.of().formatHex(digest.digest()));
            out.put("processMemory",Files.readAllLines(Path.of("/proc/self/status")).stream()
                .filter(s->s.startsWith("VmRSS:")||s.startsWith("VmHWM:")).toList());
            out.put("heapUsedBytes",java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
            System.out.println(json.writeValueAsString(out));
        }
    }
}
