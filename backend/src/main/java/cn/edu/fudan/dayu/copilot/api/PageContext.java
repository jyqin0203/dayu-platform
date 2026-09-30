package cn.edu.fudan.dayu.copilot.api;

import cn.edu.fudan.dayu.shared.kernel.ProductCode;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 用户提问时前端页面上已选择的产品、可见时间和显示时区。
 */
public record PageContext(ProductCode selectedProduct, Instant visibleTime, ZoneId displayZone) {}
