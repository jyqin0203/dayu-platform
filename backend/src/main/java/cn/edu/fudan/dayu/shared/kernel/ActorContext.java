package cn.edu.fudan.dayu.shared.kernel;

import java.util.Objects;

/**
 * 表示已通过认证的当前操作人。
 * 保存其用户编号、所属单位和角色，供需要权限判断的业务使用。
 */
public record ActorContext(UserId userId, String organization, UserRole role) {
    public ActorContext {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(organization, "organization");
        Objects.requireNonNull(role, "role");
    }
}
