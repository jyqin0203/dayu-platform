package cn.edu.fudan.dayu.shared.kernel;

import java.util.Map;
import java.util.Objects;

/**
 * 表示可预期的业务错误。
 *
 * <p>用于将错误类型、可安全展示给用户的提示，
 * 以及可选的脱敏补充信息一起传递给接入层。</p>
 */
public class BusinessException extends RuntimeException {
    /** 供程序和接入层识别错误类别的稳定代码。 */
    private final ErrorCode errorCode;

    /** 可选且必须脱敏的错误补充信息。 */
    private final Map<String, Object> details;

    /**
     * 创建不包含补充信息的业务异常。
     */
    public BusinessException(ErrorCode errorCode, String safeMessage) {
        this(errorCode, safeMessage, Map.of());
    }

    /**
     * 创建包含脱敏补充信息的业务异常。
     */
    public BusinessException(ErrorCode errorCode, String safeMessage, Map<String, Object> details) {
        super(safeMessage);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
        this.details = Map.copyOf(details);
    }

    /** @return 稳定业务错误代码。 */
    public ErrorCode errorCode() { return errorCode; }

    /** @return 可以安全展示给调用方的错误消息。 */
    public String safeMessage() { return getMessage(); }

    /** @return 不可修改的脱敏补充信息。 */
    public Map<String, Object> details() { return details; }
}
