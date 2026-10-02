package cn.edu.fudan.dayu.identity.api;

import cn.edu.fudan.dayu.shared.kernel.PageRequest;
import cn.edu.fudan.dayu.shared.kernel.UserRole;

/** 管理员用户列表的可选筛选和分页条件。 */
public record UserQuery(
        String email, String organization, UserRole role, UserStatus status, PageRequest pageRequest) {}
