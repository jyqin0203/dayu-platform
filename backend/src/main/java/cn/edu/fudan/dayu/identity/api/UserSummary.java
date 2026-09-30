package cn.edu.fudan.dayu.identity.api;

import cn.edu.fudan.dayu.shared.kernel.UserId;
import cn.edu.fudan.dayu.shared.kernel.UserRole;

/**
 * 用户管理场景使用的公开账号摘要，不包含任何密码信息。
 */
public record UserSummary(
        UserId id, String email, String organization, UserRole role, UserStatus status
) {}
