package cn.edu.fudan.dayu.interfaces.rest.legacy;

import cn.edu.fudan.dayu.shared.kernel.BusinessException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 将 Legacy Controller 失败稳定映射为旧版错误形状，且不把系统异常伪装成空结果。 */
@RestControllerAdvice(assignableTypes = {LegacyController.class, LegacyDataController.class})
public class LegacyExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(LegacyExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> business(BusinessException exception) {
        int status = switch (exception.errorCode()) {
            case MALFORMED_REQUEST -> 400;
            case VALIDATION_FAILED -> 422;
            case UNAUTHENTICATED -> 401;
            case FORBIDDEN -> 403;
            case NOT_FOUND -> 404;
            case ASSET_GONE -> 410;
            case CONFLICT -> 409;
            case RATE_LIMITED -> 429;
            case AI_UNAVAILABLE -> 503;
            case INTERNAL_ERROR -> 500;
        };
        if (status == 500) LOGGER.error("Legacy request failed with an internal business error", exception);
        return ResponseEntity.status(status).body(Map.of("ok", false, "message", exception.getMessage()));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, Object>> malformed(Exception exception) {
        return ResponseEntity.badRequest().body(Map.of("ok", false, "message", "Malformed request"));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> unexpected(RuntimeException exception) {
        LOGGER.error("Unexpected Legacy request failure", exception);
        return ResponseEntity.internalServerError()
                .body(Map.of("ok", false, "message", "Internal server error"));
    }
}
