package cn.edu.fudan.dayu.identity.api;

import cn.edu.fudan.dayu.shared.kernel.UserId;
import cn.edu.fudan.dayu.shared.kernel.UserRole;

/**
 * 管理员调整目标用户角色时提交的命令。
 */
public record ChangeUserRoleCommand(UserId userId, UserRole role) {}
