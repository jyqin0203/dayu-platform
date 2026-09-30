package cn.edu.fudan.dayu.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.edu.fudan.dayu.catalog.api.CatalogAdminService;
import cn.edu.fudan.dayu.catalog.api.CreateProductCommand;
import cn.edu.fudan.dayu.catalog.api.ProductStatus;
import cn.edu.fudan.dayu.download.api.DownloadAuditQuery;
import cn.edu.fudan.dayu.identity.api.ClientIdentity;
import cn.edu.fudan.dayu.identity.api.IdentityService;
import cn.edu.fudan.dayu.identity.api.LoginCommand;
import cn.edu.fudan.dayu.operations.api.OperationsQuery;
import cn.edu.fudan.dayu.operations.api.OperationsService;
import cn.edu.fudan.dayu.shared.kernel.ActorContext;
import cn.edu.fudan.dayu.shared.kernel.BusinessException;
import cn.edu.fudan.dayu.shared.kernel.ErrorCode;
import cn.edu.fudan.dayu.shared.kernel.PageRequest;
import cn.edu.fudan.dayu.shared.kernel.ProductCode;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 使用 skeleton Mock 验证管理员产品、扫描和审计主流程。
 */
@SpringBootTest
@ActiveProfiles("skeleton")
class AdminJourneyTest {
    @Autowired IdentityService identity;
    @Autowired CatalogAdminService catalogAdmin;
    @Autowired OperationsService operations;

    /** 验证管理员能够查看仪表盘、发布产品、触发扫描并查询审计。 */
    @Test
    void administratorCanPublishScanAndReadAuditThroughOrchestrationApis() {
        var admin = identity.login(new LoginCommand("admin@example.test", "admin-password"),
                new ClientIdentity("127.0.0.1", "test"));
        ActorContext actor = new ActorContext(admin.id(), admin.organization(), admin.role());

        var dashboard = operations.getDashboard(new OperationsQuery(
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-30T00:00:00Z"), null, null), actor);
        assertThat(dashboard.publishedProducts()).isEqualTo(3);

        var draft = catalogAdmin.createProduct(new CreateProductCommand(new ProductCode("COT"),
                "云光学厚度", "Cloud Optical Thickness", "CPP", null, "说明", "description",
                "课题组", "自研云产品算法", "基于FY-4B/AGRI观测数据生成", null,
                false, null, 20), actor);
        assertThat(draft.summary().status()).isEqualTo(ProductStatus.DRAFT);

        // 草稿不能直接停用，只能先发布。
        assertThatThrownBy(() -> catalogAdmin.disableProduct(draft.summary().id(), actor))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.CONFLICT));

        var published = catalogAdmin.publishProduct(draft.summary().id(), actor);
        assertThat(published.summary().status()).isEqualTo(ProductStatus.PUBLISHED);

        // 已发布产品不能重复发布；停用后也不能重复停用。
        assertThatThrownBy(() -> catalogAdmin.publishProduct(draft.summary().id(), actor))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(catalogAdmin.disableProduct(draft.summary().id(), actor).summary().status())
                .isEqualTo(ProductStatus.DISABLED);
        assertThatThrownBy(() -> catalogAdmin.disableProduct(draft.summary().id(), actor))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.CONFLICT));

        assertThat(operations.triggerIncrementalScan(actor).errors()).isEmpty();
        var audits = operations.searchDownloadAudits(new DownloadAuditQuery(null, null, null, null, null, null,
                new PageRequest(1, 20)), actor);
        assertThat(audits.items()).isNotEmpty();
    }
}
