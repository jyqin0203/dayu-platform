package cn.edu.fudan.dayu.shared.kernel;

import java.util.List;

/**
 * 表示分页查询的一页结果。
 * items 是当前页数据，total 是全部数据的总数。
 */
public record PageResult<T>(List<T> items, int page, int size, long total) {
    public PageResult {
        items = List.copyOf(items);
    }
}
