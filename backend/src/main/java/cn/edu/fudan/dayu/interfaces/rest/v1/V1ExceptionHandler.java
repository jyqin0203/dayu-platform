package cn.edu.fudan.dayu.interfaces.rest.v1;

import cn.edu.fudan.dayu.shared.kernel.BusinessException;
import cn.edu.fudan.dayu.shared.kernel.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 将 /api/v1 Controller 抛出的异常转换为稳定且脱敏的 HTTP 错误契约。
 */
@RestControllerAdvice(basePackages = "cn.edu.fudan.dayu.interfaces.rest.v1")
public class V1ExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(V1ExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiErrorResponse> handleBusiness(BusinessException error, HttpServletRequest request) {
        if (error.errorCode() == ErrorCode.RATE_LIMITED
                && error.details().get("retryAfterSeconds") instanceof Number retry
                && retry.longValue() > 0) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Cache-Control", "no-store")
                    .header("Retry-After", Long.toString(retry.longValue()))
                    .body(new ApiErrorResponse(error.errorCode().name(), error.safeMessage(),
                            TraceIdFilter.from(request), error.details()));
        }
        return response(statusOf(error.errorCode()), error.errorCode(), error.safeMessage(),
                error.details(), request);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    ResponseEntity<ApiErrorResponse> handleBinding(BindException error, HttpServletRequest request) {
        if (error.getBindingResult().getFieldErrors().stream()
                .anyMatch(org.springframework.validation.FieldError::isBindingFailure)) {
            return handleMalformed(error, request);
        }
        Map<String, String> fields = new LinkedHashMap<>();
        error.getBindingResult().getFieldErrors()
                .forEach(item -> fields.putIfAbsent(item.getField(), item.getDefaultMessage()));
        return response(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.VALIDATION_FAILED,
                "请求参数未通过校验", Map.of("fields", fields), request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiErrorResponse> handleConstraint(
            ConstraintViolationException error, HttpServletRequest request) {
        List<String> violations = error.getConstraintViolations().stream()
                .map(item -> item.getPropertyPath() + ": " + item.getMessage())
                .sorted()
                .toList();
        return response(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.VALIDATION_FAILED,
                "请求参数未通过校验", Map.of("violations", violations), request);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ApiErrorResponse> handleMethodValidation(
            HandlerMethodValidationException error, HttpServletRequest request) {
        // Returning an invalid server value is a server defect, not a bad client request.
        if (error.isForReturnValue()) return handleUnexpected(error, request);
        List<String> violations = error.getAllErrors().stream()
                .map(item -> item.getDefaultMessage() == null ? "invalid value" : item.getDefaultMessage())
                .toList();
        return response(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.VALIDATION_FAILED,
                "请求参数未通过校验", Map.of("violations", violations), request);
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<ApiErrorResponse> handleMalformed(Exception error, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, ErrorCode.MALFORMED_REQUEST,
                "请求格式或参数类型不正确", Map.of(), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> handleUnexpected(Exception error, HttpServletRequest request) {
        String traceId = TraceIdFilter.from(request);
        // Exception messages may contain SQL, credentials or rejected request values.
        log.error("Unhandled API error, traceId={}, type={}", traceId, error.getClass().getName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiErrorResponse(ErrorCode.INTERNAL_ERROR.name(),
                        "服务暂时不可用", traceId, Map.of()));
    }

    private static ResponseEntity<ApiErrorResponse> response(
            HttpStatus status, ErrorCode code, String message,
            Map<String, Object> details, HttpServletRequest request) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store")
                .body(new ApiErrorResponse(code.name(), message,
                        TraceIdFilter.from(request), details));
    }

    private static HttpStatus statusOf(ErrorCode code) {
        return switch (code) {
            case MALFORMED_REQUEST -> HttpStatus.BAD_REQUEST;
            case VALIDATION_FAILED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ASSET_GONE -> HttpStatus.GONE;
            case CONFLICT -> HttpStatus.CONFLICT;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case AI_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
