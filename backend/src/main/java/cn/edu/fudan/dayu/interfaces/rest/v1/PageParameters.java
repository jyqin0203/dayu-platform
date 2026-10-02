package cn.edu.fudan.dayu.interfaces.rest.v1;

import cn.edu.fudan.dayu.shared.kernel.PageRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * /api/v1 统一的一基分页参数。
 * 未提供参数时使用第 1 页、每页 20 条；HTTP 层最多允许每页 100 条。
 */
public record PageParameters(
        @Min(value = 1, message = "page must be at least 1") Integer page,
        @Min(value = 1, message = "pageSize must be at least 1")
        @Max(value = 100, message = "pageSize must not exceed 100") Integer pageSize
) {
    /** 将可空 HTTP 参数转换为模块共享的非空分页值对象。 */
    public PageRequest toPageRequest() {
        return new PageRequest(page == null ? 1 : page, pageSize == null ? 20 : pageSize);
    }
}
