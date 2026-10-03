package cn.edu.fudan.dayu.interfaces.rest.v1;

import cn.edu.fudan.dayu.identity.api.IdentityService;
import cn.edu.fudan.dayu.shared.kernel.ActorContext;
import cn.edu.fudan.dayu.shared.kernel.BusinessException;
import cn.edu.fudan.dayu.shared.kernel.ErrorCode;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** 将当前 Identity 身份转换成业务模块使用的可信操作人上下文。 */
@Component
public class CurrentActorProvider {
    private final IdentityService identity;

    public CurrentActorProvider(IdentityService identity) {
        this.identity = identity;
    }

    public Optional<ActorContext> optional() {
        var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof cn.edu.fudan.dayu.identity.api.AuthenticatedUser))
            return Optional.empty();
        return identity.getCurrentUser().map(user ->
                new ActorContext(user.id(), user.organization(), user.role()));
    }

    public ActorContext required() {
        return optional().orElseThrow(() ->
                new BusinessException(ErrorCode.UNAUTHENTICATED, "请先登录"));
    }
}
