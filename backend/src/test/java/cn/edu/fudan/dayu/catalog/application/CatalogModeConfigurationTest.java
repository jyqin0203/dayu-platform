package cn.edu.fudan.dayu.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.edu.fudan.dayu.catalog.api.ConfigureProductModeCommand;
import cn.edu.fudan.dayu.catalog.api.CreateProductCommand;
import cn.edu.fudan.dayu.catalog.api.ProductStatus;
import cn.edu.fudan.dayu.shared.kernel.ActorContext;
import cn.edu.fudan.dayu.shared.kernel.BusinessException;
import cn.edu.fudan.dayu.shared.kernel.DataMode;
import cn.edu.fudan.dayu.shared.kernel.ErrorCode;
import cn.edu.fudan.dayu.shared.kernel.ProductCode;
import cn.edu.fudan.dayu.shared.kernel.ProductId;
import cn.edu.fudan.dayu.shared.kernel.UserId;
import cn.edu.fudan.dayu.shared.kernel.UserRole;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 验证 Catalog 模式策略的创建、替换、权限和已发布产品不变量。
 */
class CatalogModeConfigurationTest {
    private static final ActorContext ADMIN =
            new ActorContext(new UserId(1), "测试单位", UserRole.ADMIN);
    private static final ActorContext USER =
            new ActorContext(new UserId(2), "测试单位", UserRole.USER);

    @Test
    void createsAndReplacesModePolicy() {
        MockCatalog catalog = new MockCatalog();
        ProductId productId = catalog.findProduct(new ProductCode("BT855")).orElseThrow().summary().id();

        var configured = catalog.configureProductMode(new ConfigureProductModeCommand(
                productId, DataMode.FORECAST, false, Duration.ofMinutes(180)), ADMIN);

        assertThat(configured.enabled()).isFalse();
        assertThat(configured.staleAfter()).isEqualTo(Duration.ofMinutes(180));
        assertThat(catalog.findProduct(new ProductCode("BT855")).orElseThrow().modePolicies())
                .filteredOn(policy -> policy.dataMode() == DataMode.FORECAST)
                .singleElement()
                .isEqualTo(configured);
    }

    @Test
    void rejectsModeChangesFromOrdinaryUsers() {
        MockCatalog catalog = new MockCatalog();

        assertThatThrownBy(() -> catalog.configureProductMode(new ConfigureProductModeCommand(
                new ProductId(1), DataMode.REALTIME, true, Duration.ofMinutes(90)), USER))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void publishedProductMustKeepAtLeastOneEnabledMode() {
        MockCatalog catalog = new MockCatalog();
        ProductId productId = new ProductId(1);

        catalog.configureProductMode(new ConfigureProductModeCommand(
                productId, DataMode.REALTIME, false, Duration.ofMinutes(90)), ADMIN);

        assertThatThrownBy(() -> catalog.configureProductMode(new ConfigureProductModeCommand(
                productId, DataMode.FORECAST, false, Duration.ofMinutes(360)), ADMIN))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void draftRequiresAnEnabledModeBeforeFirstPublish() {
        MockCatalog catalog = new MockCatalog();
        var draft = catalog.createProduct(new CreateProductCommand(
                new ProductCode("COT"), "云光学厚度", "Cloud Optical Thickness", "CPP", null,
                "说明", "description", "课题组", "自研算法", "来源", null,
                false, null, 20), ADMIN);

        assertThatThrownBy(() -> catalog.publishProduct(draft.summary().id(), ADMIN))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        catalog.configureProductMode(new ConfigureProductModeCommand(
                draft.summary().id(), DataMode.REALTIME, true, Duration.ofMinutes(90)), ADMIN);

        assertThat(catalog.publishProduct(draft.summary().id(), ADMIN).summary().status())
                .isEqualTo(ProductStatus.PUBLISHED);
    }

    @Test
    void staleThresholdMustBeWholeMinutesWithinSupportedRange() {
        assertThatThrownBy(() -> new ConfigureProductModeCommand(
                new ProductId(1), DataMode.REALTIME, true, Duration.ofMinutes(9)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConfigureProductModeCommand(
                new ProductId(1), DataMode.REALTIME, true, Duration.ofSeconds(601)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConfigureProductModeCommand(
                new ProductId(1), DataMode.REALTIME, true, Duration.ofDays(8)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
