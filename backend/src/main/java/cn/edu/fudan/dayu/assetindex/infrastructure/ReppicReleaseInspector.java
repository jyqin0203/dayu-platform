package cn.edu.fudan.dayu.assetindex.infrastructure;

import cn.edu.fudan.dayu.assetindex.application.FileInventory.FileEntry;
import cn.edu.fudan.dayu.assetindex.application.FileInventory.ReleaseSnapshot;
import cn.edu.fudan.dayu.assetindex.application.FileInventory.ReleaseStatus;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Production palette-v2 publication check: at most 16 KiB of marker JSON and three
 * lead directories' file metadata. Never reads NC arrays, image bodies or archives.
 * Every cycle uses the same rules; no historical date bypass is allowed.
 */
final class ReppicReleaseInspector {
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("uuuuMMddHHmm").withZone(ZoneOffset.UTC);
    private static final ObjectReader JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final int MAX_MARKER=16384;

    ReleaseSnapshot inspect(Path configured,Instant cycle) {
        Path root=configured.toAbsolutePath().normalize();
        try {
            if (!safeDirectory(root)) return state(ReleaseStatus.UNREADABLE);
        } catch (IOException | SecurityException inaccessible) { return state(ReleaseStatus.UNREADABLE); }
        try {
            String stamp=TIME.format(cycle);
            if (!stamp.matches("[0-9]{12}")) return state(ReleaseStatus.NOT_READY);
            Path directory=root.resolve("forecast").resolve(stamp);
            if (!safeDirectory(directory)) return state(ReleaseStatus.NOT_READY);
            Path marker=directory.resolve(".reppic-complete.json");
            var before=Files.readAttributes(marker,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if (!before.isRegularFile() || before.isSymbolicLink() || before.size()>MAX_MARKER || before.size()==0
                    || !marker.toRealPath().startsWith(root)) return state(ReleaseStatus.NOT_READY);
            byte[] bytes;
            try(var channel=Files.newByteChannel(marker,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS));
                var input=Channels.newInputStream(channel)) {
                bytes=input.readNBytes(MAX_MARKER+1);
            }
            if (bytes.length>MAX_MARKER) return state(ReleaseStatus.NOT_READY);
            JsonNode data;
            try { data=JSON.readTree(bytes); }
            catch (JsonProcessingException malformed) { return state(ReleaseStatus.NOT_READY); }
            if (data==null || !data.isObject() || !data.path("schema").asText().equals("fducrias-reppic-complete/v1")
                    || !data.path("complete").isBoolean() || !data.path("complete").booleanValue()
                    || !data.path("cycle12").asText().equals(stamp)
                    || !data.path("archive_sha256").asText().matches("[0-9a-f]{64}")) return state(ReleaseStatus.NOT_READY);
            List<FileEntry> files=new ArrayList<>();
            for(int hours=1;hours<=3;hours++) {
                String alias="PRECIP_"+hours+"H";
                Path lead=directory.resolve(alias);
                if (!safeDirectory(lead)) return state(ReleaseStatus.NOT_READY);
                String prefix="FY4B_AGRI_REPPIC_"+alias+"_"+stamp+"_";
                String expected=prefix+TIME.format(cycle.plusSeconds(hours*3600L))+"_palettev2_Dpi500.webp";
                FileEntry selected=null;
                int count=0;
                try(var entries=Files.newDirectoryStream(lead,prefix+"????????????_palettev2_Dpi500.webp")) {
                    for(Path file:entries) {
                        var attributes=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                        if (attributes.isSymbolicLink() || !file.toRealPath().startsWith(root)) return state(ReleaseStatus.NOT_READY);
                        if (!attributes.isRegularFile() || attributes.size()==0) continue;
                        if (++count>1 || !file.getFileName().toString().equals(expected)) return state(ReleaseStatus.NOT_READY);
                        if (!Files.isReadable(file)) return state(ReleaseStatus.UNREADABLE);
                        selected=new FileEntry(root.relativize(file).toString().replace('\\','/'),attributes.size(),attributes.lastModifiedTime().toInstant());
                    }
                }
                if (selected==null) return state(ReleaseStatus.NOT_READY);
                files.add(selected);
            }
            var after=Files.readAttributes(marker,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if (!after.isRegularFile() || after.size()!=before.size() || !after.lastModifiedTime().equals(before.lastModifiedTime())
                    || !Objects.equals(after.fileKey(),before.fileKey())) return state(ReleaseStatus.NOT_READY);
            return new ReleaseSnapshot(ReleaseStatus.READY,files);
        } catch (NoSuchFileException absentOrChanged) {
            try { return state(safeDirectory(root) ? ReleaseStatus.NOT_READY : ReleaseStatus.UNREADABLE); }
            catch (IOException | SecurityException rootUnavailable) { return state(ReleaseStatus.UNREADABLE); }
        }
        catch (IOException | SecurityException | DirectoryIteratorException inaccessible) { return state(ReleaseStatus.UNREADABLE); }
    }
    private static boolean safeDirectory(Path path) throws IOException {
        var attributes=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink()) return false;
        if (!Files.isReadable(path)) throw new java.nio.file.AccessDeniedException("Publication directory unavailable");
        return path.toRealPath().equals(path.toAbsolutePath().normalize());
    }
    private static ReleaseSnapshot state(ReleaseStatus status) { return new ReleaseSnapshot(status,List.of()); }
}
