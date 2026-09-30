package cn.edu.fudan.dayu.catalog.api;

import cn.edu.fudan.dayu.shared.kernel.ProductId;
import java.net.URI;

/**
 * 修改已有产品资料时提交的命令。
 * 只包含允许修改的字段，不允许借此改变产品编码、产品族或生命周期状态。
 */
public record UpdateProductCommand(
        ProductId productId, String nameZh, String nameEn, String unit,
        String descriptionZh, String descriptionEn, String producer, String algorithmName,
        String sourceDescription, URI officialSourceUrl, boolean colorbarRequired,
        String colorbarPath, int sortOrder
) {}
