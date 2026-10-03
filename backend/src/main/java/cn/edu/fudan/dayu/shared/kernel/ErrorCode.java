package cn.edu.fudan.dayu.shared.kernel;

/**
 * 表示系统可识别的业务错误类型。
 * 接入层可据此转换为合适的 HTTP 错误响应。
 */
public enum ErrorCode {
    MALFORMED_REQUEST, VALIDATION_FAILED, UNAUTHENTICATED, FORBIDDEN, NOT_FOUND, ASSET_GONE,
    CONFLICT, RATE_LIMITED, AI_UNAVAILABLE, INTERNAL_ERROR
}
