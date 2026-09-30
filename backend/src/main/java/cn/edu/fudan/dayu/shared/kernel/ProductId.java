package cn.edu.fudan.dayu.shared.kernel;

/**
 * 表示产品定义的唯一内部编号，避免与其他数字编号混淆。
 */
public record ProductId(long value) {
    public ProductId {
        if (value <= 0) throw new IllegalArgumentException("product id must be positive");
    }
}
