package cn.edu.fudan.dayu.catalog.api;

import cn.edu.fudan.dayu.shared.kernel.ProductCode;
import java.net.URI;

/**
 * 创建草稿产品时提交的资料。
 * 不包含产品 ID、状态和时间戳，这些字段由 Catalog 模块生成。
 */
public record CreateProductCommand(
        ProductCode code, String nameZh, String nameEn, String family, String unit,
        String descriptionZh, String descriptionEn, String producer, String algorithmName,
        String sourceDescription, URI officialSourceUrl, boolean colorbarRequired,
        String colorbarPath, int sortOrder
) {}
