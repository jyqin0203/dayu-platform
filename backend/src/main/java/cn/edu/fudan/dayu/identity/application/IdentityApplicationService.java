package cn.edu.fudan.dayu.identity.application;

import cn.edu.fudan.dayu.identity.api.*;
import cn.edu.fudan.dayu.shared.kernel.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real accounts and login auditing. HTTP creates the Session only after these calls return. */
@Service
@Profile("!skeleton")
public class IdentityApplicationService implements IdentityService, UserAdminService {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final TransactionTemplate transactions;
    private final Duration window;
    private final int emailLimit;
    private final int ipLimit;
    private final String dummyHash;
    private final Object[] loginLocks = java.util.stream.IntStream.range(0, 256).mapToObj(i -> new Object()).toArray();

    public IdentityApplicationService(UserRepository users, PasswordEncoder passwords,
            PlatformTransactionManager manager,
            @Value("${dayu.identity.login-window:15m}") Duration window,
            @Value("${dayu.identity.email-failure-limit:5}") int emailLimit,
            @Value("${dayu.identity.ip-failure-limit:30}") int ipLimit) {
        if (window.isNegative() || window.isZero() || emailLimit < 1 || ipLimit < 1)
            throw new IllegalArgumentException("Invalid identity rate limits");
        this.users = users;
        this.passwords = passwords;
        this.transactions = new TransactionTemplate(manager);
        this.window = window;
        this.emailLimit = emailLimit;
        this.ipLimit = ipLimit;
        this.dummyHash = passwords.encode("dummy-account-timing-value");
    }

    /** Unique constraint handles concurrent duplicate registration; commit precedes returning. */
    @Override public AuthenticatedUser register(RegisterCommand command) {
        String email = email(command.email());
        String organization = command.organization() == null ? "" : command.organization().trim();
        if (organization.length() < 2 || organization.length() > 255)
            throw failure(ErrorCode.VALIDATION_FAILED, "单位长度必须为2至255字符");
        String raw = command.password();
        if (raw == null || raw.length() < 10 || raw.length() > 128)
            throw failure(ErrorCode.VALIDATION_FAILED, "密码长度必须为10至128字符");
        // Standard PBKDF2 supports the full 128-character password contract without BCrypt truncation.
        String hash = passwords.encode(raw);
        try {
            UserSummary user = transactions.execute(status -> users.insert(email, hash, organization));
            return authenticated(user);
        } catch (DuplicateKeyException ex) {
            throw failure(ErrorCode.CONFLICT, "邮箱已注册");
        }
    }

    /** Failures are committed independently of the exception returned to the client. */
    @Override public AuthenticatedUser login(LoginCommand command, ClientIdentity client) {
        String email = email(command.email());
        String raw = command.password();
        if (raw == null || raw.isBlank() || raw.length() > 128)
            throw failure(ErrorCode.VALIDATION_FAILED, "密码格式不正确");
        String ip = client == null || client.ipAddress() == null ? "" : client.ipAddress();
        if (ip.length() > 45) ip = "";
        // Bounded single-instance locks close the count/check/write race without a global login lock.
        int emailLock = Math.floorMod(email.hashCode(), loginLocks.length);
        int ipLock = Math.floorMod(ip.hashCode(), loginLocks.length);
        synchronized (loginLocks[Math.min(emailLock, ipLock)]) {
            synchronized (loginLocks[Math.max(emailLock, ipLock)]) {
                return authenticateAndAudit(email, raw, ip);
            }
        }
    }
    private AuthenticatedUser authenticateAndAudit(String email, String raw, String ip) {
        Instant now = Instant.now();
        Instant since = now.minus(window);
        if (users.failuresForEmail(email, since) >= emailLimit || users.failuresForIp(ip, since) >= ipLimit)
            throw new BusinessException(ErrorCode.RATE_LIMITED, "登录尝试过于频繁",
                    java.util.Map.of("retryAfterSeconds", window.toSeconds()));
        var account = users.byEmail(email);
        boolean matches = passwords.matches(raw, account.map(UserRepository.Account::passwordHash).orElse(dummyHash));
        boolean successful = matches && account.isPresent() && account.get().user().status() == UserStatus.ACTIVE;
        users.recordAttempt(email, ip, successful, now);
        if (!successful) throw failure(ErrorCode.UNAUTHENTICATED, "邮箱或密码错误");
        return authenticated(account.get().user());
    }

    /** Re-read account state so disable/demotion takes effect on the very next request. */
    @Override public Optional<AuthenticatedUser> getCurrentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof AuthenticatedUser principal))
            return Optional.empty();
        return users.byId(principal.id()).map(UserRepository.Account::user)
                .filter(user -> user.status() == UserStatus.ACTIVE).map(IdentityApplicationService::authenticated);
    }
    @Override public void logout() { SecurityContextHolder.clearContext(); }

    @Override public PageResult<UserSummary> searchUsers(UserQuery query, ActorContext actor) {
        requireAdmin(actor);
        return users.search(query);
    }
    @Override public UserSummary changeUserStatus(ChangeUserStatusCommand command, ActorContext actor) {
        if (command.status() == null) throw failure(ErrorCode.VALIDATION_FAILED, "账号状态必填");
        return change(command.userId(), null, command.status(), actor);
    }
    @Override public UserSummary changeUserRole(ChangeUserRoleCommand command, ActorContext actor) {
        if (command.role() == null) throw failure(ErrorCode.VALIDATION_FAILED, "账号角色必填");
        return change(command.userId(), command.role(), null, actor);
    }
    private UserSummary change(UserId id, UserRole role, UserStatus status, ActorContext actor) {
        return transactions.execute(tx -> {
            // Serializes competing last-admin changes, including changes from separate requests.
            var admins = users.lockActiveAdministrators();
            requireAdmin(actor);
            var old = users.lockUser(id);
            UserRole nextRole = role == null ? old.role() : role;
            UserStatus nextStatus = status == null ? old.status() : status;
            if (actor.userId().equals(id) && nextStatus == UserStatus.DISABLED)
                throw failure(ErrorCode.CONFLICT, "不能禁用当前管理员账号");
            if (old.role() == UserRole.ADMIN && old.status() == UserStatus.ACTIVE && admins.size() <= 1
                    && (nextRole != UserRole.ADMIN || nextStatus != UserStatus.ACTIVE))
                throw failure(ErrorCode.CONFLICT, "必须保留至少一个有效管理员");
            var changed = new UserSummary(old.id(), old.email(), old.organization(), nextRole, nextStatus);
            users.update(changed);
            return changed;
        });
    }
    private void requireAdmin(ActorContext actor) {
        if (actor == null) throw failure(ErrorCode.UNAUTHENTICATED, "请先登录");
        var account = users.byId(actor.userId()).map(UserRepository.Account::user);
        if (actor.role() != UserRole.ADMIN || account.isEmpty() || account.get().role() != UserRole.ADMIN
                || account.get().status() != UserStatus.ACTIVE)
            throw failure(ErrorCode.FORBIDDEN, "需要管理员权限");
    }
    private static String email(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 254 || !normalized.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
            throw failure(ErrorCode.VALIDATION_FAILED, "邮箱格式不正确");
        return normalized;
    }
    private static AuthenticatedUser authenticated(UserSummary user) {
        return new AuthenticatedUser(user.id(), user.email(), user.organization(), user.role());
    }
    private static BusinessException failure(ErrorCode code, String message) { return new BusinessException(code, message); }
}
