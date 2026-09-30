package cn.edu.fudan.dayu.copilot.api;

import cn.edu.fudan.dayu.shared.kernel.DataMode;
import cn.edu.fudan.dayu.shared.kernel.ProductCode;
import java.time.Instant;

/**
 * Copilot 从自然语言中解释并经 Java 校验后的结构化查询条件。
 */
public record InterpretedCriteria(
        ProductCode productCode, DataMode dataMode, Instant from, Instant to, String queryKind
) {}
