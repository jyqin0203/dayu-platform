package cn.edu.fudan.dayu.identity.application;

import cn.edu.fudan.dayu.identity.api.*;
import cn.edu.fudan.dayu.shared.kernel.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Identity persistence boundary; hashes never leave the module. */
public interface UserRepository {
    record Account(UserSummary user, String passwordHash) {
        @Override public String toString() { return "Account[redacted]"; }
    }
    Optional<Account> byEmail(String email);
    Optional<Account> byId(UserId id);
    UserSummary insert(String email, String hash, String organization);
    PageResult<UserSummary> search(UserQuery query);
    /** Locks all active administrators in stable ID order before role/status changes. */
    List<UserSummary> lockActiveAdministrators();
    UserSummary lockUser(UserId id);
    void update(UserSummary user);
    long failuresForEmail(String email, Instant since);
    long failuresForIp(String ip, Instant since);
    void recordAttempt(String email, String ip, boolean successful, Instant at);
    int purgeAttemptsBefore(Instant before);
}
