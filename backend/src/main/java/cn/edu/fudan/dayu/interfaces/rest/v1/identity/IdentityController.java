package cn.edu.fudan.dayu.interfaces.rest.v1.identity;

import cn.edu.fudan.dayu.identity.api.AuthenticatedUser;
import cn.edu.fudan.dayu.identity.api.ClientIdentity;
import cn.edu.fudan.dayu.identity.api.IdentityService;
import cn.edu.fudan.dayu.identity.api.LoginCommand;
import cn.edu.fudan.dayu.identity.api.RegisterCommand;
import cn.edu.fudan.dayu.interfaces.rest.SessionAuthentication;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Session、注册、登录和退出的 HTTP 适配器。 */
@RestController
@RequestMapping("/api/v1")
public class IdentityController {
    private final IdentityService identity;
    private final SessionAuthentication sessions;

    public IdentityController(IdentityService identity, SessionAuthentication sessions) {
        this.identity = identity;
        this.sessions = sessions;
    }

    @GetMapping("/session")
    public ResponseEntity<SessionResponse> session(HttpServletRequest request, HttpServletResponse response) {
        return noStore(new SessionResponse(identity.getCurrentUser().isPresent(),
                identity.getCurrentUser().map(IdentityController::user).orElse(null),
                sessions.token(request, response)), HttpStatus.OK);
    }

    @PostMapping("/session")
    public ResponseEntity<SessionResponse> login(
            @Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        AuthenticatedUser authenticated = identity.login(
                new LoginCommand(body.email(), body.password()),
                new ClientIdentity(request.getRemoteAddr(), request.getHeader("User-Agent")));
        return noStore(new SessionResponse(true, user(authenticated), sessions.establish(authenticated, request, response)), HttpStatus.OK);
    }

    @PostMapping("/users")
    public ResponseEntity<SessionResponse> register(
            @Valid @RequestBody RegisterRequest body, HttpServletRequest request, HttpServletResponse response) {
        AuthenticatedUser authenticated = identity.register(new RegisterCommand(
                body.email(), body.password(), body.organization()));
        return noStore(new SessionResponse(true, user(authenticated), sessions.establish(authenticated, request, response)), HttpStatus.CREATED);
    }

    @DeleteMapping("/session")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        identity.logout();
        sessions.clear(request, response);
        return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
    }

    private static UserResponse user(AuthenticatedUser user) {
        return new UserResponse(user.id().value(), user.email(), user.organization(), user.role().name());
    }

    private static <T> ResponseEntity<T> noStore(T body, HttpStatus status) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(body);
    }

    public record LoginRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 128) String password) {
        public LoginRequest { email = normalizeEmail(email); }
        @Override public String toString() { return "LoginRequest[redacted]"; }
    }
    public record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 10, max = 128) String password,
            @NotBlank @Size(min = 2, max = 255) String organization) {
        public RegisterRequest {
            email = normalizeEmail(email);
            organization = organization == null ? null : organization.trim();
        }
        @Override public String toString() { return "RegisterRequest[redacted]"; }
    }
    private static String normalizeEmail(String email) { return email == null ? null : email.trim().toLowerCase(Locale.ROOT); }
    public record UserResponse(long userId, String email, String organization, String role) {}
    public record SessionResponse(boolean authenticated, UserResponse user, String csrfToken) {
        @Override public String toString() { return "SessionResponse[authenticated=" + authenticated + ", redacted]"; }
    }
}
