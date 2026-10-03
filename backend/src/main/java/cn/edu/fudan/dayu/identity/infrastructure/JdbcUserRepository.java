package cn.edu.fudan.dayu.identity.infrastructure;

import cn.edu.fudan.dayu.identity.api.*;
import cn.edu.fudan.dayu.identity.application.UserRepository;
import cn.edu.fudan.dayu.shared.kernel.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** Parameterized SQL adapter for users and bounded-time login audit queries. */
@Repository
@Profile("!skeleton")
public class JdbcUserRepository implements UserRepository {
    private final JdbcTemplate jdbc;
    public JdbcUserRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public Optional<Account> byEmail(String email) {
        return jdbc.query("SELECT * FROM users WHERE email=?", (rs, n) -> account(rs), email).stream().findFirst();
    }
    @Override public Optional<Account> byId(UserId id) {
        return jdbc.query("SELECT * FROM users WHERE id=?", (rs, n) -> account(rs), id.value()).stream().findFirst();
    }
    @Override public UserSummary insert(String email, String hash, String organization) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                    "INSERT INTO users(email,password_hash,organization,role,status,created_at,updated_at) "
                    + "VALUES(?,?,?,'USER','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, email); statement.setString(2, hash); statement.setString(3, organization);
            return statement;
        }, keys);
        return new UserSummary(new UserId(keys.getKey().longValue()), email, organization, UserRole.USER, UserStatus.ACTIVE);
    }
    @Override public PageResult<UserSummary> search(UserQuery query) {
        var args = new ArrayList<Object>();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (query.email() != null && !query.email().isBlank()) {
            where.append(" AND LOCATE(?,email)>0"); args.add(query.email());
        }
        if (query.organization() != null && !query.organization().isBlank()) {
            where.append(" AND LOCATE(?,organization)>0"); args.add(query.organization());
        }
        if (query.role() != null) { where.append(" AND role=?"); args.add(query.role().name()); }
        if (query.status() != null) { where.append(" AND status=?"); args.add(query.status().name()); }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM users" + where, Long.class, args.toArray());
        args.add(query.pageRequest().size());
        args.add((long) (query.pageRequest().page() - 1) * query.pageRequest().size());
        var items = jdbc.query("SELECT * FROM users" + where + " ORDER BY id LIMIT ? OFFSET ?",
                (rs, n) -> summary(rs), args.toArray());
        return new PageResult<>(items, query.pageRequest().page(), query.pageRequest().size(), total);
    }
    @Override public List<UserSummary> lockActiveAdministrators() {
        return jdbc.query("SELECT * FROM users WHERE role='ADMIN' AND status='ACTIVE' ORDER BY id FOR UPDATE",
                (rs, n) -> summary(rs));
    }
    @Override public UserSummary lockUser(UserId id) {
        return jdbc.query("SELECT * FROM users WHERE id=? FOR UPDATE", (rs, n) -> summary(rs), id.value())
                .stream().findFirst().orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在"));
    }
    @Override public void update(UserSummary user) {
        jdbc.update("UPDATE users SET role=?,status=?,updated_at=UTC_TIMESTAMP(6) WHERE id=?",
                user.role().name(), user.status().name(), user.id().value());
    }
    @Override public long failuresForEmail(String email, Instant since) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM login_attempts WHERE email=? AND successful=FALSE AND attempted_at>=?",
                Long.class, email, utc(since));
    }
    @Override public long failuresForIp(String ip, Instant since) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM login_attempts WHERE ip_address=? AND successful=FALSE AND attempted_at>=?",
                Long.class, ip, utc(since));
    }
    @Override public void recordAttempt(String email, String ip, boolean successful, Instant at) {
        jdbc.update("INSERT INTO login_attempts(email,ip_address,successful,attempted_at) VALUES(?,?,?,?)",
                email, ip, successful, utc(at));
    }
    @Override public int purgeAttemptsBefore(Instant before) {
        return jdbc.update("DELETE FROM login_attempts WHERE attempted_at<? LIMIT 10000", utc(before));
    }
    /** DATETIME is timezone-less; encode UTC explicitly instead of relying on the Windows JVM timezone. */
    private static LocalDateTime utc(Instant instant) { return LocalDateTime.ofInstant(instant, ZoneOffset.UTC); }
    private static UserSummary summary(ResultSet rs) throws SQLException {
        return new UserSummary(new UserId(rs.getLong("id")), rs.getString("email"), rs.getString("organization"),
                UserRole.valueOf(rs.getString("role")), UserStatus.valueOf(rs.getString("status")));
    }
    private static Account account(ResultSet rs) throws SQLException { return new Account(summary(rs), rs.getString("password_hash")); }
}
