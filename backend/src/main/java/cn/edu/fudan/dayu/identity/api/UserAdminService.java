package cn.edu.fudan.dayu.identity.api;

import cn.edu.fudan.dayu.shared.kernel.ActorContext;

/**
 * Identity 模块提供给管理员的账号状态和角色管理接口。
 */
public interface UserAdminService {
    /** 修改目标用户的启用或禁用状态。 */
    UserSummary changeUserStatus(ChangeUserStatusCommand command, ActorContext actor);

    /** 修改目标用户的普通用户或管理员角色。 */
    UserSummary changeUserRole(ChangeUserRoleCommand command, ActorContext actor);
}
