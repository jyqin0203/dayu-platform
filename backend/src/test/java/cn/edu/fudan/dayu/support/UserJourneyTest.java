package cn.edu.fudan.dayu.support;

import static org.assertj.core.api.Assertions.assertThat;

import cn.edu.fudan.dayu.catalog.api.CatalogQueryService;
import cn.edu.fudan.dayu.discovery.api.DiscoveryQueryService;
import cn.edu.fudan.dayu.discovery.api.PreviewQuery;
import cn.edu.fudan.dayu.discovery.api.ScientificAssetQuery;
import cn.edu.fudan.dayu.download.api.ClientContext;
import cn.edu.fudan.dayu.download.api.DownloadAuthorizationService;
import cn.edu.fudan.dayu.download.api.DownloadCommand;
import cn.edu.fudan.dayu.identity.api.IdentityService;
import cn.edu.fudan.dayu.identity.api.RegisterCommand;
import cn.edu.fudan.dayu.shared.kernel.ActorContext;
import cn.edu.fudan.dayu.shared.kernel.AssetId;
import cn.edu.fudan.dayu.shared.kernel.DataMode;
import cn.edu.fudan.dayu.shared.kernel.PageRequest;
import cn.edu.fudan.dayu.shared.kernel.ProductCode;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 使用 skeleton Mock 验证普通用户主流程能够通过公开模块 API 串联。
 */
@SpringBootTest
@ActiveProfiles("skeleton")
class UserJourneyTest {
    @Autowired CatalogQueryService catalog;
    @Autowired DiscoveryQueryService discovery;
    @Autowired IdentityService identity;
    @Autowired DownloadAuthorizationService downloads;

    /** 验证产品浏览、WebP 查询、注册、NC 检索和下载授权主流程。 */
    @Test
    void userCanBrowseRegisterSearchAndRequestDownloadThroughModuleApis() {
        // 用户请求查询已发布的产品
        assertThat(catalog.listPublishedProducts()).extracting(p -> p.code().value())
                .contains("BT855", "PRECIP", "PLP");

        var frames = discovery.listPreviewFrames(new PreviewQuery(new ProductCode("BT855"), DataMode.REALTIME,
                Instant.parse("2026-09-26T00:00:00Z"), Instant.parse("2026-09-29T00:00:00Z"),
                null, null, 48));
        assertThat(frames).hasSize(1);

        var user = identity.register(new RegisterCommand("new-user@example.test", "not-a-real-password", "测试单位"));
        var result = discovery.searchScientificAssets(new ScientificAssetQuery(new ProductCode("PRECIP"),
                DataMode.FORECAST, Instant.parse("2026-09-02T06:00:00Z"),
                Instant.parse("2026-09-02T09:00:00Z"), Instant.parse("2026-09-02T06:00:00Z"),
                null, new PageRequest(1, 20)));
        assertThat(result.items()).hasSize(1);

        var grant = downloads.authorizeDownload(new DownloadCommand(new AssetId(92002),
                        "用于分析东亚区域降水预报误差，不用于商业用途"),
                new ActorContext(user.id(), user.organization(), user.role()),
                new ClientContext("127.0.0.1", "test"));
        assertThat(grant.expectedBytes()).isPositive();
        assertThat(grant.internalLocation()).startsWith("/internal-netcdf/");
    }
}
