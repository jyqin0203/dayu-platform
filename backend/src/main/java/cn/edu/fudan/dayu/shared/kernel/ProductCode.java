package cn.edu.fudan.dayu.shared.kernel;

import java.util.Objects;

/**
 * 表示气象产品的稳定编码，例如 BT855、PRECIP。
 * 使用该类型代替普通 String，避免与其他字符串混淆。
 */
public record ProductCode(String value) {
    /** 校验产品编码既不能为 null，也不能为空白字符串。 */
    public ProductCode {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("product code must not be blank");
        }
    }
}
