package cn.edu.fudan.dayu.interfaces.rest.v1.catalog;

import cn.edu.fudan.dayu.shared.kernel.DataMode;

/**
 * 普通用户可见的产品模式，只包含已启用模式及其数据过期阈值。
 */
public record PublicProductModeResponse(DataMode dataMode, long staleAfterMinutes) {}
