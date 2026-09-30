package cn.edu.fudan.dayu.shared.kernel;

/**
 * 表示用户的唯一内部编号，避免与其他数字编号混淆。
 */
public record UserId(long value) {
    public UserId {
        if (value <= 0) throw new IllegalArgumentException("user id must be positive");
    }
}
