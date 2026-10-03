package cn.edu.fudan.dayu.interfaces.rest.v1;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * /api/v1 统一错误响应。
 *
 * @param code 稳定的机器可读错误代码
 * @param message 可以安全展示给用户的消息
 * @param traceId 用于关联服务端日志的请求标识
 * @param details 可选且必须脱敏的结构化详情
 */
public record ApiErrorResponse(
        String code,
        String message,
        String traceId,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Object> details
) {
    public ApiErrorResponse {
        details = details == null ? Map.of() : Map.copyOf(details);
    }
}
