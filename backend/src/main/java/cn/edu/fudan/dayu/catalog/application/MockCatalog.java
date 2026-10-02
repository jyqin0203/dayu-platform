package cn.edu.fudan.dayu.catalog.application;

import cn.edu.fudan.dayu.catalog.api.AssetFamilyProductMapping;
import cn.edu.fudan.dayu.catalog.api.CatalogAdminService;
import cn.edu.fudan.dayu.catalog.api.CatalogQueryService;
import cn.edu.fudan.dayu.catalog.api.ConfigureProductModeCommand;
import cn.edu.fudan.dayu.catalog.api.CreateProductCommand;
import cn.edu.fudan.dayu.catalog.api.ManagedProductQuery;
import cn.edu.fudan.dayu.catalog.api.ProductDetail;
import cn.edu.fudan.dayu.catalog.api.ProductModePolicy;
import cn.edu.fudan.dayu.catalog.api.ProductStatus;
import cn.edu.fudan.dayu.catalog.api.ProductSummary;
import cn.edu.fudan.dayu.catalog.api.UpdateProductCommand;
import cn.edu.fudan.dayu.shared.kernel.ActorContext;
import cn.edu.fudan.dayu.shared.kernel.DataMode;
import cn.edu.fudan.dayu.shared.kernel.ErrorCode;
import cn.edu.fudan.dayu.shared.kernel.BusinessException;
import cn.edu.fudan.dayu.shared.kernel.ProductCode;
import cn.edu.fudan.dayu.shared.kernel.ProductId;
import cn.edu.fudan.dayu.shared.kernel.UserRole;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Catalog 模块在 skeleton Profile 下使用的内存模拟实现。
 *
 * <p>它同时实现产品查询和管理员管理接口，只用于验证模块能否正确协作。
 * 数据仅保存在内存中，应用重启后会恢复为构造方法中的固定样例，
 * 不代表 Catalog 的生产实现已经完成。</p>
 */
@Service
@Profile("skeleton")
class MockCatalog implements CatalogQueryService, CatalogAdminService {
    /** 固定时间使测试结果不会随着当前系统时间变化。 */
    private static final Instant FIXED_NOW = Instant.parse("2026-09-29T02:00:00Z");

    /** 模拟数据库为新产品生成递增的内部编号。 */
    private final AtomicLong sequence = new AtomicLong(10);

    /** 使用产品编码作为键，在内存中保存产品详情。 */
    private final Map<ProductCode, ProductDetail> products = new LinkedHashMap<>();

    /** 初始化供空骨架测试使用的三个已发布产品。 */
    MockCatalog() {
        addPublished(1, "BT855", "8.55μm亮温", "8.55μm Brightness Temperature", "BT", "K", "课题组", "上游亮温产品");
        addPublished(2, "PRECIP", "降水强度", "Precipitation Rate", "PRECIP", "mm/h", "课题组", "RePPIC-Net");
        addPublished(3, "PLP", "雨雪相态", "Precipitation Phase", "PRECIP", null, "课题组", "RePPIC-Net");
    }

    private void addPublished(long id, String code, String zh, String en, String family, String unit,
                              String producer, String algorithm) {
        ProductCode productCode = new ProductCode(code);
        ProductSummary summary = new ProductSummary(new ProductId(id), productCode, zh, en, family, unit,
                producer, algorithm, "基于FY-4B/AGRI观测数据生成", null,
                ProductStatus.PUBLISHED, (int) id);
        products.put(productCode, new ProductDetail(summary, zh, en, false, null,
                List.of(new ProductModePolicy(productCode, DataMode.REALTIME, true, Duration.ofHours(2)),
                        new ProductModePolicy(productCode, DataMode.FORECAST, true, Duration.ofHours(6))),
                FIXED_NOW, FIXED_NOW, FIXED_NOW));
    }

    @Override
    public List<ProductSummary> listPublishedProducts() {
        return products.values().stream()
                // 产品列表只需要摘要，不返回完整详情。
                .map(ProductDetail::summary)
                // 普通用户只能看到已经发布的产品。
                .filter(p -> p.status() == ProductStatus.PUBLISHED)
                // 按产品配置的显示顺序返回。
                .sorted(Comparator.comparingInt(ProductSummary::sortOrder))
                .toList();
    }

    @Override
    public List<ProductDetail> listPublishedProductDetails() {
        return products.values().stream()
                .filter(product -> product.summary().status() == ProductStatus.PUBLISHED)
                .sorted(Comparator.comparingInt(product -> product.summary().sortOrder()))
                .toList();
    }

    @Override
    public Optional<ProductDetail> findProduct(ProductCode code) {
        return Optional.ofNullable(products.get(code));
    }

    @Override
    public List<ProductSummary> listManagedProducts(ManagedProductQuery query) {
        return products.values().stream().map(ProductDetail::summary)
                .filter(p -> query == null || query.family() == null || query.family().equals(p.family()))
                .filter(p -> query == null || query.status() == null || query.status() == p.status())
                .filter(p -> query == null || query.code() == null || query.code().equals(p.code()))
                .toList();
    }

    @Override
    public AssetFamilyProductMapping resolveProductsForAssetFamily(String familyCode) {
        Set<ProductCode> mapped = products.values().stream().map(ProductDetail::summary)
                .filter(p -> p.family().equals(familyCode)).map(ProductSummary::code)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new AssetFamilyProductMapping(familyCode, mapped);
    }

    @Override
    public List<ProductDetail> listManagedProductDetails(ManagedProductQuery query) {
        return listManagedProducts(query).stream().map(p -> products.get(p.code()))
                .sorted(Comparator.comparingInt((ProductDetail p) -> p.summary().sortOrder())
                        .thenComparingLong(p -> p.summary().id().value())).toList();
    }

    @Override
    public ProductDetail createProduct(CreateProductCommand command, ActorContext actor) {
        // 所有管理操作都先检查当前操作人是否为管理员。
        requireAdmin(actor);

        // 产品编码是稳定且唯一的标识，不能重复创建。
        if (products.containsKey(command.code())) {
            throw new BusinessException(ErrorCode.CONFLICT, "产品编码已存在");
        }

        // 新产品获得一个模拟的递增 ID，并以草稿状态创建。
        ProductSummary summary = new ProductSummary(new ProductId(sequence.incrementAndGet()), command.code(),
                command.nameZh(), command.nameEn(), command.family(), command.unit(), command.producer(),
                command.algorithmName(), command.sourceDescription(), command.officialSourceUrl(),
                ProductStatus.DRAFT, command.sortOrder());

        // 新草稿还没有模式策略和发布时间，创建、更新时间使用固定测试时间。
        ProductDetail detail = new ProductDetail(summary, command.descriptionZh(), command.descriptionEn(),
                command.colorbarRequired(), command.colorbarPath(), List.of(), null, FIXED_NOW, FIXED_NOW);

        // 写入内存 Map，模拟把新产品保存到数据库。
        products.put(command.code(), detail);
        return detail;
    }

    @Override
    public ProductDetail updateProduct(UpdateProductCommand command, ActorContext actor) {
        requireAdmin(actor);

        // 先根据内部 ID 找到当前产品；不存在时由 byId 抛出业务异常。
        ProductDetail existing = byId(command.productId());
        ProductSummary old = existing.summary();

        // 只更新命令允许修改的资料，保留 ID、编码、产品族和生命周期状态。
        ProductSummary updated = new ProductSummary(old.id(), old.code(), command.nameZh(), command.nameEn(),
                old.family(), command.unit(), command.producer(), command.algorithmName(),
                command.sourceDescription(), command.officialSourceUrl(), old.status(), command.sortOrder());

        // 保留模式策略、发布时间和创建时间，只刷新可变资料与更新时间。
        ProductDetail result = new ProductDetail(updated, command.descriptionZh(), command.descriptionEn(),
                command.colorbarRequired(), command.colorbarPath(), existing.modePolicies(), existing.publishedAt(),
                existing.createdAt(), FIXED_NOW);

        // 使用相同产品编码替换内存中的旧详情。
        products.put(old.code(), result);
        return result;
    }

    @Override
    public ProductModePolicy configureProductMode(ConfigureProductModeCommand command, ActorContext actor) {
        requireAdmin(actor);
        ProductDetail existing = byId(command.productId());

        boolean anotherModeRemainsEnabled = existing.modePolicies().stream()
                .anyMatch(policy -> policy.dataMode() != command.dataMode() && policy.enabled());
        if (existing.summary().status() == ProductStatus.PUBLISHED
                && !command.enabled() && !anotherModeRemainsEnabled) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "已发布产品必须至少保留一种启用的数据模式");
        }

        ProductModePolicy configured = new ProductModePolicy(
                existing.summary().code(), command.dataMode(), command.enabled(), command.staleAfter());
        List<ProductModePolicy> policies = java.util.stream.Stream.concat(
                        existing.modePolicies().stream()
                                .filter(policy -> policy.dataMode() != command.dataMode()),
                        java.util.stream.Stream.of(configured))
                .sorted(Comparator.comparing(ProductModePolicy::dataMode))
                .toList();

        ProductDetail updated = new ProductDetail(existing.summary(), existing.descriptionZh(),
                existing.descriptionEn(), existing.colorbarRequired(), existing.colorbarPath(),
                policies, existing.publishedAt(), existing.createdAt(), FIXED_NOW);
        products.put(existing.summary().code(), updated);
        return configured;
    }

    @Override
    public ProductDetail publishProduct(ProductId productId, ActorContext actor) {
        requireAdmin(actor);
        ProductDetail product = byId(productId);
        if (product.modePolicies().stream().noneMatch(ProductModePolicy::enabled)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "发布产品前必须至少启用一种数据模式");
        }
        return changeStatus(productId, actor, Set.of(ProductStatus.DRAFT, ProductStatus.DISABLED),
                ProductStatus.PUBLISHED);
    }

    @Override
    public ProductDetail disableProduct(ProductId productId, ActorContext actor) {
        return changeStatus(productId, actor, Set.of(ProductStatus.PUBLISHED), ProductStatus.DISABLED);
    }

    /**
     * 按已确认的生命周期改变产品状态，同时保留产品的其他资料。
     * 草稿可以首次发布，已停用产品可以重新发布，只有已发布产品可以停用。
     */
    private ProductDetail changeStatus(ProductId id, ActorContext actor,
                                       Set<ProductStatus> allowedCurrentStatuses, ProductStatus targetStatus) {
        requireAdmin(actor);
        ProductDetail existing = byId(id);
        ProductSummary old = existing.summary();
        if (!allowedCurrentStatuses.contains(old.status())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "产品状态不允许从" + old.status() + "变更为" + targetStatus);
        }

        // 创建状态已更新的新摘要，其他摘要字段保持不变。
        ProductSummary updated = new ProductSummary(old.id(), old.code(), old.nameZh(), old.nameEn(), old.family(),
                old.unit(), old.producer(), old.algorithmName(), old.sourceDescription(), old.officialSourceUrl(),
                targetStatus, old.sortOrder());

        // 首次发布时记录发布时间；重新发布和停用都保留第一次的发布时间。
        ProductDetail result = new ProductDetail(updated, existing.descriptionZh(), existing.descriptionEn(),
                existing.colorbarRequired(), existing.colorbarPath(), existing.modePolicies(),
                targetStatus == ProductStatus.PUBLISHED && existing.publishedAt() == null
                        ? FIXED_NOW : existing.publishedAt(),
                existing.createdAt(), FIXED_NOW);
        products.put(old.code(), result);
        return result;
    }

    /**
     * 根据内部产品 ID 查找详情，找不到时抛出安全的业务错误。
     */
    private ProductDetail byId(ProductId id) {
        return products.values().stream().filter(p -> p.summary().id().equals(id)).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "产品不存在"));
    }

    /**
     * 确保管理操作只能由管理员执行。
     */
    private static void requireAdmin(ActorContext actor) {
        if (actor.role() != UserRole.ADMIN) throw new BusinessException(ErrorCode.FORBIDDEN, "需要管理员权限");
    }
}
