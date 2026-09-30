package cn.edu.fudan.dayu.catalog.api;

import cn.edu.fudan.dayu.shared.kernel.ProductCode;
import cn.edu.fudan.dayu.shared.kernel.ProductId;
import java.net.URI;

/**
 * 产品列表使用的摘要信息。
 * 它比 ProductDetail 更精简，不包含详细说明、模式策略和时间戳。
 */
public record ProductSummary(
        ProductId id,
        ProductCode code,
        String nameZh,
        String nameEn,
        String family,
        String unit,
        String producer,
        String algorithmName,
        String sourceDescription,
        URI officialSourceUrl,
        ProductStatus status,
        int sortOrder
) {}
