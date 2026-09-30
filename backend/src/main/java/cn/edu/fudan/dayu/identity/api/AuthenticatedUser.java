package cn.edu.fudan.dayu.identity.api;

import cn.edu.fudan.dayu.shared.kernel.UserId;
import cn.edu.fudan.dayu.shared.kernel.UserRole;

/**
 * 当前已认证用户可在模块间传递的公开身份信息。
 * 不包含密码、密码哈希或 Session 标识。
 */
public record AuthenticatedUser(UserId id, String email, String organization, UserRole role) {}
