package cn.edu.fudan.dayu.assetindex.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.edu.fudan.dayu.assetindex.api.*;
import cn.edu.fudan.dayu.catalog.api.CatalogQueryService;
import cn.edu.fudan.dayu.shared.kernel.*;
import org.junit.jupiter.api.Test;

class MockAssetTaskTest {
    @Test void taskIdsAndQueriesRemainDeterministicInSkeletonProfile() {
        var index=new MockAssetIndex(mock(CatalogQueryService.class));
        var actor=new ActorContext(new UserId(1),"Lab",UserRole.ADMIN);
        var first=index.submitScan(ScanTrigger.MANUAL,actor);
        var second=index.submitScan(ScanTrigger.MANUAL,actor);
        assertThat(first.status()).isEqualTo(ScanStatus.RUNNING);
        assertThat(second.scanRunId()).isGreaterThan(first.scanRunId());
        assertThat(index.findScanRun(first.scanRunId()).orElseThrow().status()).isEqualTo(ScanStatus.SUCCEEDED);
        assertThat(index.findScanRun(99999)).isEmpty();
        assertThat(index.searchScanRuns(new ScanRunQuery(null,null,null,ScanStatus.RUNNING,new PageRequest(1,20))).total()).isZero();
        assertThat(index.searchScanRuns(new ScanRunQuery(null,null,ScanTrigger.MANUAL,ScanStatus.SUCCEEDED,new PageRequest(2,1))).items())
                .extracting(ScanRunView::scanRunId).containsExactly(first.scanRunId());
        assertThat(index.findByStoragePath("unrelated-space","forecast/202609020600/FY4B_AGRI_REPPIC_PRECIP_2H_202609020600_202609020800.nc")).isEmpty();
    }
}
