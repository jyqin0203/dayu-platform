package cn.edu.fudan.dayu.catalog.api;

import cn.edu.fudan.dayu.shared.kernel.ProductCode;

/** Catalog 写事务提交后供查询缓存失效的进程内事件。 */
public record CatalogChanged(ProductCode productCode) {}
