package cn.edu.fudan.dayu.assetindex.infrastructure;

import static org.assertj.core.api.Assertions.*;
import cn.edu.fudan.dayu.assetindex.application.FileInventory.ReleaseStatus;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ReppicReleaseInspectorTest {
    @TempDir Path root;
    private final Instant cycle=Instant.parse("2026-09-02T12:00:00Z");
    private final ReppicReleaseInspector inspector=new ReppicReleaseInspector();
    private Path marker;
    private final String validMarker="{\"schema\":\"fducrias-reppic-complete/v1\",\"complete\":true,\"cycle12\":\"202609021200\",\"archive_sha256\":\""+"a".repeat(64)+"\"}";
    private Path image(int hours) {
        return root.resolve("forecast/202609021200/PRECIP_"+hours+"H/FY4B_AGRI_REPPIC_PRECIP_"+hours+"H_202609021200_20260902"+(12+hours)+"00_palettev2_Dpi500.webp");
    }
    @BeforeEach void files() throws Exception {
        for(int h=1;h<=3;h++) { Files.createDirectories(image(h).getParent()); Files.writeString(image(h),"synthetic"); }
        marker=root.resolve("forecast/202609021200/.reppic-complete.json");
        Files.writeString(marker,validMarker);
    }
    @Test void returnsThreeMetadataEntriesOnlyForACompleteRelease() {
        var result=inspector.inspect(root,cycle);
        assertThat(result.status()).isEqualTo(ReleaseStatus.READY);
        assertThat(result.files()).hasSize(3).allSatisfy(file -> assertThat(file.size()).isPositive());
    }
    @Test void formerHistoricalExceptionAlsoRequiresMarker() throws Exception {
        Files.delete(marker);
        assertThat(inspector.inspect(root,cycle).status()).isEqualTo(ReleaseStatus.NOT_READY);
    }
    @Test void rejectsFalseWrongCycleMalformedDuplicateTrailingAndOversizedMarkers() throws Exception {
        for(String markerText:List.of(validMarker.replace("true","false"),validMarker.replace("202609021200","202609021300"),
                validMarker.replace("complete\":true","complete\":\"true\""),validMarker.replace("a".repeat(64),"not-a-hash"),
                validMarker.replace("/v1","/v2"),"{",validMarker+" {}",validMarker.replace("\"complete\":true","\"complete\":false,\"complete\":true")," ".repeat(16385))) {
            Files.writeString(marker,markerText);
            assertThat(inspector.inspect(root,cycle).status()).isEqualTo(ReleaseStatus.NOT_READY);
        }
    }
    @Test void rejectsMissingAndZeroLengthLead() throws Exception {
        Files.writeString(image(3),"");
        assertThat(inspector.inspect(root,cycle).status()).isEqualTo(ReleaseStatus.NOT_READY);
        Files.delete(image(3));
        assertThat(inspector.inspect(root,cycle).status()).isEqualTo(ReleaseStatus.NOT_READY);
    }
    @Test void rejectsDuplicateOrIncorrectValidTime() throws Exception {
        Path wrong=image(1).resolveSibling(image(1).getFileName().toString().replace("202609021300","202609021330"));
        Files.writeString(wrong,"duplicate");
        assertThat(inspector.inspect(root,cycle).status()).isEqualTo(ReleaseStatus.NOT_READY);
        Files.delete(image(1));
        assertThat(inspector.inspect(root,cycle).status()).isEqualTo(ReleaseStatus.NOT_READY);
    }
    @Test void unavailableRootIsNotMistakenForAnUnpublishedBatch() {
        assertThat(inspector.inspect(root.resolve("offline"),cycle).status()).isEqualTo(ReleaseStatus.UNREADABLE);
    }
    @Test void rejectsLinkedLeadDirectoryWithoutFollowingIt() throws Exception {
        Path directory=image(3).getParent();
        Path moved=root.resolve("outside-lead");
        Files.move(directory,moved);
        if (System.getProperty("os.name").startsWith("Windows")) {
            var command=new ProcessBuilder("cmd","/c","mklink","/J",directory.toString(),moved.toString())
                    .redirectErrorStream(true).start();
            assertThat(command.waitFor(10,TimeUnit.SECONDS)).isTrue();
            assertThat(command.exitValue()).isZero();
        } else Files.createSymbolicLink(directory,moved);
        assertThat(inspector.inspect(root,cycle).status()).isEqualTo(ReleaseStatus.NOT_READY);
    }
}
