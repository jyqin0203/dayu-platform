package cn.edu.fudan.dayu.assetindex.infrastructure;

import static org.assertj.core.api.Assertions.*;
import cn.edu.fudan.dayu.assetindex.api.AssetScanError;
import cn.edu.fudan.dayu.assetindex.application.FileInventory;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFileInventoryTest {
    @TempDir Path root;

    @Test void visitsOnlyPublishedExtensionsAndReportsMissingRootWithoutAbsolutePath() throws Exception {
        Files.createDirectories(root.resolve("realtime"));
        Files.writeString(root.resolve("realtime/test.nc"),"fixture");
        Files.writeString(root.resolve("realtime/test.nc.part"),"pending");
        Files.createDirectories(root.resolve("tmp"));
        Files.writeString(root.resolve("tmp/test.nc"),"pending");
        Files.createDirectories(root.resolve("empty"));
        var entries=new ArrayList<FileInventory.FileEntry>();
        var errors=new ArrayList<AssetScanError>();
        var inventory=new LocalFileInventory();
        assertThat(inventory.visit(root,entries::add,errors::add)).isTrue();
        assertThat(entries).extracting(FileInventory.FileEntry::relativePath).containsExactly("realtime/test.nc");
        assertThat(errors).isEmpty();
        assertThat(inventory.visit(root.resolve("missing"),entries::add,errors::add)).isFalse();
        assertThat(errors).singleElement().satisfies(e -> {
            assertThat(e.errorCode()).isEqualTo("UNREADABLE_ROOT");
            assertThat(e.relativePath()).isEmpty();
            assertThat(e.safeMessage()).doesNotContain(root.toString());
        });
    }
}
