package cn.edu.fudan.dayu.shared.kernel;

/**
 * 表示分页查询的请求参数。
 * page 从 1 开始，size 表示每页数量。
 */
public record PageRequest(int page, int size) {
    public PageRequest {
        if (page < 1) throw new IllegalArgumentException("page must be at least 1");
        if (size < 1 || size > 200) throw new IllegalArgumentException("size must be between 1 and 200");
    }
}
