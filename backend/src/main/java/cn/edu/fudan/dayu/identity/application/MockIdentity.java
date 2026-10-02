package cn.edu.fudan.dayu.identity.application;

import cn.edu.fudan.dayu.identity.api.AuthenticatedUser;
import cn.edu.fudan.dayu.identity.api.ChangeUserRoleCommand;
import cn.edu.fudan.dayu.identity.api.ChangeUserStatusCommand;
import cn.edu.fudan.dayu.identity.api.ClientIdentity;
import cn.edu.fudan.dayu.identity.api.IdentityService;
import cn.edu.fudan.dayu.identity.api.LoginCommand;
import cn.edu.fudan.dayu.identity.api.RegisterCommand;
import cn.edu.fudan.dayu.identity.api.UserAdminService;
import cn.edu.fudan.dayu.identity.api.UserStatus;
import cn.edu.fudan.dayu.identity.api.UserSummary;
import cn.edu.fudan.dayu.identity.api.UserQuery;
import cn.edu.fudan.dayu.shared.kernel.ActorContext;
import cn.edu.fudan.dayu.shared.kernel.BusinessException;
import cn.edu.fudan.dayu.shared.kernel.ErrorCode;
import cn.edu.fudan.dayu.shared.kernel.UserId;
import cn.edu.fudan.dayu.shared.kernel.UserRole;
import cn.edu.fudan.dayu.shared.kernel.PageResult;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Identity 在 skeleton Profile 下使用的内存模拟实现。
 *
 * <p>账号和当前用户只保存在内存中，测试密码也只是固定样例。
 * 该实现不代表生产密码哈希、Session 和登录限流已经完成。</p>
 */
@Service
@Profile("skeleton")
class MockIdentity implements IdentityService, UserAdminService {
    /** 内存模拟账号，同时保存公开摘要和仅供骨架测试的固定密码。 */
    private record MockAccount(UserSummary summary, String password) {}

    private final AtomicLong sequence = new AtomicLong(100);
    private final Map<String, MockAccount> accounts = new LinkedHashMap<>();
    // Only non-HTTP direct-call skeleton stories use this thread-local identity.
    private final ThreadLocal<AuthenticatedUser> directUser = new ThreadLocal<>();

    MockIdentity() {
        addAccount(1, "user@example.test", "user-password", "复旦大学", UserRole.USER);
        addAccount(2, "admin@example.test", "admin-password", "复旦大学", UserRole.ADMIN);
    }

    private void addAccount(long id, String email, String password, String organization, UserRole role) {
        accounts.put(email, new MockAccount(
                new UserSummary(new UserId(id), email, organization, role, UserStatus.ACTIVE), password));
    }

    @Override
    public AuthenticatedUser register(RegisterCommand command) {
        if (accounts.containsKey(command.email())) {
            throw new BusinessException(ErrorCode.CONFLICT, "邮箱已注册");
        }
        UserSummary user = new UserSummary(new UserId(sequence.incrementAndGet()), command.email(),
                command.organization(), UserRole.USER, UserStatus.ACTIVE);
        accounts.put(command.email(), new MockAccount(user, command.password()));
        AuthenticatedUser userView = authenticated(user);
        if (org.springframework.web.context.request.RequestContextHolder.getRequestAttributes() == null) directUser.set(userView);
        return userView;
    }

    @Override
    public AuthenticatedUser login(LoginCommand command, ClientIdentity client) {
        MockAccount account = accounts.get(command.email());
        if (account == null || !account.password().equals(command.password())) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED, "邮箱或密码错误");
        }
        if (account.summary().status() == UserStatus.DISABLED) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED, "邮箱或密码错误");
        }
        AuthenticatedUser userView = authenticated(account.summary());
        if (org.springframework.web.context.request.RequestContextHolder.getRequestAttributes() == null) directUser.set(userView);
        return userView;
    }

    @Override
    public Optional<AuthenticatedUser> getCurrentUser() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser principal) {
            return accounts.values().stream().map(MockAccount::summary)
                    .filter(user -> user.id().equals(principal.id()) && user.status() == UserStatus.ACTIVE)
                    .findFirst().map(MockIdentity::authenticated);
        }
        if (auth != null || org.springframework.web.context.request.RequestContextHolder.getRequestAttributes() != null) return Optional.empty();
        return Optional.ofNullable(directUser.get());
    }

    @Override
    public void logout() {
        directUser.remove();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Override
    public PageResult<UserSummary> searchUsers(UserQuery query, ActorContext actor) {
        requireAdmin(actor);
        var found = accounts.values().stream().map(MockAccount::summary)
                .filter(user -> query.email() == null || user.email().contains(query.email()))
                .filter(user -> query.organization() == null
                        || user.organization().contains(query.organization()))
                .filter(user -> query.role() == null || user.role() == query.role())
                .filter(user -> query.status() == null || user.status() == query.status())
                .toList();
        return new PageResult<>(found, query.pageRequest().page(), query.pageRequest().size(), found.size());
    }

    @Override
    public UserSummary changeUserStatus(ChangeUserStatusCommand command, ActorContext actor) {
        requireAdmin(actor);
        MockAccount account = byId(command.userId());
        UserSummary old = account.summary();
        UserSummary updated = new UserSummary(old.id(), old.email(), old.organization(), old.role(), command.status());
        accounts.put(old.email(), new MockAccount(updated, account.password()));
        return updated;
    }

    @Override
    public UserSummary changeUserRole(ChangeUserRoleCommand command, ActorContext actor) {
        requireAdmin(actor);
        MockAccount account = byId(command.userId());
        UserSummary old = account.summary();
        UserSummary updated = new UserSummary(old.id(), old.email(), old.organization(), command.role(), old.status());
        accounts.put(old.email(), new MockAccount(updated, account.password()));
        return updated;
    }

    private MockAccount byId(UserId id) {
        return accounts.values().stream().filter(a -> a.summary().id().equals(id)).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在"));
    }

    private static AuthenticatedUser authenticated(UserSummary user) {
        return new AuthenticatedUser(user.id(), user.email(), user.organization(), user.role());
    }

    private static void requireAdmin(ActorContext actor) {
        if (actor.role() != UserRole.ADMIN) throw new BusinessException(ErrorCode.FORBIDDEN, "需要管理员权限");
    }
}
