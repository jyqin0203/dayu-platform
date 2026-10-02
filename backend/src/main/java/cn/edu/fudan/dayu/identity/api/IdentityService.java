package cn.edu.fudan.dayu.identity.api;

import java.util.Optional;

/**
 * Identity 模块对外公开的注册、登录、当前身份和退出能力。
 */
public interface IdentityService {
    /** 创建普通用户；真实实现提交事务后返回，由HTTP接入层建立Session。 */
    AuthenticatedUser register(RegisterCommand command);

    /** 验证账号凭据；由HTTP接入层建立Session，服务不保存全局当前用户。 */
    AuthenticatedUser login(LoginCommand command, ClientIdentity client);

    /** 返回当前请求对应的已认证用户；未登录时返回空 Optional。 */
    Optional<AuthenticatedUser> getCurrentUser();

    /** 销毁当前登录状态。 */
    void logout();
}
