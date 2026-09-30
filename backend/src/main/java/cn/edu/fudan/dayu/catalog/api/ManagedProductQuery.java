package cn.edu.fudan.dayu.catalog.api;

import cn.edu.fudan.dayu.shared.kernel.ProductCode;

/**
 * 管理员查询产品时使用的筛选条件。
 * family、status 和 code 均可为空，空值表示不使用对应条件筛选。
 */
public record ManagedProductQuery(String family, ProductStatus status, ProductCode code) {}
