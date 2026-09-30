package cn.edu.fudan.dayu.identity.api;

import java.util.Optional;

/**
 * Identity 模块对外公开的注册、登录、当前身份和退出能力。
 */
public interface IdentityService {
    /** 创建普通用户并建立登录状态。 */
    AuthenticatedUser register(RegisterCommand command);

    /** 验证账号凭据并建立登录状态。 */
    AuthenticatedUser login(LoginCommand command, ClientIdentity client);

    /** 返回当前请求对应的已认证用户；未登录时返回空 Optional。 */
    Optional<AuthenticatedUser> getCurrentUser();

    /** 销毁当前登录状态。 */
    void logout();
}
