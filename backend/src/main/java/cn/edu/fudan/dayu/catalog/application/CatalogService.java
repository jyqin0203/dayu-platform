package cn.edu.fudan.dayu.catalog.application;

import cn.edu.fudan.dayu.catalog.api.*;
import cn.edu.fudan.dayu.shared.kernel.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 真实 Catalog 用例。产品、模式及审计在同一数据库事务中修改。 */
@Service
@Profile("!skeleton")
@Transactional(readOnly = true)
public class CatalogService implements CatalogQueryService, CatalogAdminService {
    private final CatalogRepository repository;
    private final ColorbarVerifier colorbars;
    private final ApplicationEventPublisher events;

    public CatalogService(CatalogRepository repository, ColorbarVerifier colorbars, ApplicationEventPublisher events) {
        this.repository = repository;
        this.colorbars = colorbars;
        this.events = events;
    }

    @Override public List<ProductSummary> listPublishedProducts() {
        return listPublishedProductDetails().stream().map(ProductDetail::summary).toList();
    }
    @Override public List<ProductDetail> listPublishedProductDetails() {
        return repository.findAll(new ManagedProductQuery(null, ProductStatus.PUBLISHED, null));
    }
    @Override public Optional<ProductDetail> findProduct(ProductCode code) {
        validateCode(code);
        return repository.findByCode(code);
    }
    @Override public List<ProductSummary> listManagedProducts(ManagedProductQuery query) {
        return repository.findAll(query).stream().map(ProductDetail::summary).toList();
    }
    @Override public List<ProductDetail> listManagedProductDetails(ManagedProductQuery query) {
        return repository.findAll(query);
    }
    @Override public AssetFamilyProductMapping resolveProductsForAssetFamily(String familyCode) {
        if (familyCode == null || familyCode.isBlank()) throw invalid("产品族不能为空");
        return new AssetFamilyProductMapping(familyCode, repository.findAll(
                new ManagedProductQuery(familyCode, null, null)).stream()
                .map(p -> p.summary().code()).collect(Collectors.toUnmodifiableSet()));
    }

    @Override @Transactional
    public ProductDetail createProduct(CreateProductCommand c, ActorContext actor) {
        requireAdmin(actor);
        if (c == null) throw invalid("创建命令不能为空");
        validateCode(c.code());
        text(c.family(), 1, 64);
        validate(c.nameZh(), c.nameEn(), c.unit(), c.descriptionZh(), c.descriptionEn(), c.producer(),
                c.algorithmName(), c.sourceDescription(), c.officialSourceUrl(), c.colorbarPath());
        Instant now = now();
        ProductId id = repository.insert(c, actor.userId(), now);
        ProductDetail result = locked(id);
        repository.audit(null, result, "CREATE", actor.userId(), now);
        changed(result.summary().code());
        return result;
    }

    @Override @Transactional
    public ProductDetail updateProduct(UpdateProductCommand c, ActorContext actor) {
        requireAdmin(actor);
        if (c == null) throw invalid("修改命令不能为空");
        validate(c.nameZh(), c.nameEn(), c.unit(), c.descriptionZh(), c.descriptionEn(), c.producer(),
                c.algorithmName(), c.sourceDescription(), c.officialSourceUrl(), c.colorbarPath());
        ProductDetail old = locked(c.productId());
        ProductSummary s = old.summary();
        ProductSummary summary = new ProductSummary(s.id(), s.code(), c.nameZh(), c.nameEn(), s.family(),
                c.unit(), c.producer(), c.algorithmName(), c.sourceDescription(), c.officialSourceUrl(),
                s.status(), c.sortOrder());
        ProductDetail updated = new ProductDetail(summary, c.descriptionZh(), c.descriptionEn(),
                c.colorbarRequired(), c.colorbarPath(), old.modePolicies(), old.publishedAt(), old.createdAt(), now());
        if (s.status() == ProductStatus.PUBLISHED) validatePublication(updated);
        // 数据库对已停用产品也保留必要色标配置约束。
        if (s.status() == ProductStatus.DISABLED && c.colorbarRequired()
                && (c.colorbarPath() == null || c.colorbarPath().isBlank())) throw invalid("必须配置色标");
        save(old, updated, "UPDATE", actor);
        return updated;
    }

    @Override @Transactional
    public ProductModePolicy configureProductMode(ConfigureProductModeCommand c, ActorContext actor) {
        requireAdmin(actor);
        if (c == null) throw invalid("模式命令不能为空");
        ProductDetail old = locked(c.productId());
        if (old.summary().status() == ProductStatus.PUBLISHED && !c.enabled()
                && old.modePolicies().stream().noneMatch(p -> p.dataMode() != c.dataMode() && p.enabled()))
            throw new BusinessException(ErrorCode.CONFLICT, "已发布产品必须保留至少一种启用模式");
        ProductModePolicy mode = new ProductModePolicy(old.summary().code(), c.dataMode(), c.enabled(), c.staleAfter());
        Instant now = now();
        repository.saveMode(c.productId(), mode, now);
        ProductDetail fresh = locked(c.productId());
        ProductDetail updated = new ProductDetail(fresh.summary(), fresh.descriptionZh(), fresh.descriptionEn(),
                fresh.colorbarRequired(), fresh.colorbarPath(), fresh.modePolicies(), fresh.publishedAt(), fresh.createdAt(), now);
        save(old, updated, "UPDATE", actor);
        return mode;
    }

    @Override @Transactional
    public ProductDetail publishProduct(ProductId id, ActorContext actor) {
        requireAdmin(actor);
        ProductDetail old = locked(id);
        if (old.summary().status() == ProductStatus.PUBLISHED) throw conflict();
        validatePublication(old);
        return transition(old, ProductStatus.PUBLISHED,
                old.summary().status() == ProductStatus.DRAFT ? "PUBLISH" : "REPUBLISH", actor);
    }
    @Override @Transactional
    public ProductDetail disableProduct(ProductId id, ActorContext actor) {
        requireAdmin(actor);
        ProductDetail old = locked(id);
        if (old.summary().status() != ProductStatus.PUBLISHED) throw conflict();
        return transition(old, ProductStatus.DISABLED, "DISABLE", actor);
    }

    private ProductDetail transition(ProductDetail old, ProductStatus status, String action, ActorContext actor) {
        ProductSummary s = old.summary();
        Instant now = now();
        ProductSummary summary = new ProductSummary(s.id(), s.code(), s.nameZh(), s.nameEn(), s.family(), s.unit(),
                s.producer(), s.algorithmName(), s.sourceDescription(), s.officialSourceUrl(), status, s.sortOrder());
        ProductDetail updated = new ProductDetail(summary, old.descriptionZh(), old.descriptionEn(),
                old.colorbarRequired(), old.colorbarPath(), old.modePolicies(),
                old.publishedAt() == null ? now : old.publishedAt(), old.createdAt(), now);
        save(old, updated, action, actor);
        return updated;
    }
    private void save(ProductDetail before, ProductDetail after, String action, ActorContext actor) {
        repository.update(after, actor.userId());
        repository.audit(before, after, action, actor.userId(), after.updatedAt());
        changed(after.summary().code());
    }
    private void changed(ProductCode code) { events.publishEvent(new CatalogChanged(code)); }
    private ProductDetail locked(ProductId id) {
        if (id == null) throw invalid("产品编号不能为空");
        return repository.lock(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "产品不存在"));
    }
    private static void validateCode(ProductCode code) {
        if (code == null || !code.value().matches("^[A-Z][A-Z0-9_]{1,63}$")) throw invalid("产品编码不合法");
    }
    private void validatePublication(ProductDetail p) {
        if (p.modePolicies().stream().noneMatch(ProductModePolicy::enabled)) throw invalid("发布前必须启用一种模式");
        if (p.colorbarRequired()) colorbars.verify(p.colorbarPath());
    }
    private static void requireAdmin(ActorContext actor) {
        if (actor == null) throw new BusinessException(ErrorCode.UNAUTHENTICATED, "请先登录");
        if (actor.role() != UserRole.ADMIN) throw new BusinessException(ErrorCode.FORBIDDEN, "需要管理员权限");
    }
    private static void validate(String zh, String en, String unit, String dz, String de, String producer,
                                 String algorithm, String source, java.net.URI url, String path) {
        text(zh, 2, 255); text(en, 2, 255); text(producer, 1, 255);
        requiredText(dz); requiredText(de); requiredText(source);
        optional(unit, 64); optional(algorithm, 255); optional(path, 512);
        if (url != null && (!url.isAbsolute() || !Set.of("http", "https").contains(url.getScheme())
                || url.getUserInfo() != null || url.toString().length() > 1024)) throw invalid("来源链接必须是 HTTP(S) 地址");
        if (path != null && !path.isBlank()) {
            if (path.startsWith("/") || path.contains("\\") || path.contains(":") || path.contains("%")
                    || path.chars().anyMatch(Character::isISOControl)
                    || Arrays.stream(path.split("/", -1)).anyMatch(s -> s.isEmpty() || s.equals(".") || s.equals("..")))
                throw invalid("色标必须使用安全的相对路径");
        }
    }
    private static void text(String s, int min, int max) {
        if (s == null || s.isBlank() || s.length() < min || s.length() > max) throw invalid("产品字段长度不合法");
    }
    private static void requiredText(String s) {
        if (s == null || s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65535) throw invalid("产品说明不合法");
    }
    private static void optional(String s, int max) { if (s != null && s.length() > max) throw invalid("产品字段过长"); }
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    private static BusinessException invalid(String msg) { return new BusinessException(ErrorCode.VALIDATION_FAILED, msg); }
    private static BusinessException conflict() { return new BusinessException(ErrorCode.CONFLICT, "产品状态不允许该操作"); }
}
