package cn.edu.fudan.dayu.catalog.api;

import java.time.Instant;
import java.util.List;

/**
 * 产品的完整详情，用于单个产品查询和管理操作结果。
 * 在产品摘要之外包含详细说明、展示配置、模式策略和生命周期时间。
 */
public record ProductDetail(
        ProductSummary summary,
        String descriptionZh,
        String descriptionEn,
        boolean colorbarRequired,
        String colorbarPath,
        List<ProductModePolicy> modePolicies,
        Instant publishedAt,
        Instant createdAt,
        Instant updatedAt
) {
    public ProductDetail {
        modePolicies = List.copyOf(modePolicies);
    }
}
