package cn.edu.fudan.dayu.identity.api;

import cn.edu.fudan.dayu.shared.kernel.UserId;

/**
 * 管理员启用或禁用目标用户时提交的命令。
 */
public record ChangeUserStatusCommand(UserId userId, UserStatus status) {}
