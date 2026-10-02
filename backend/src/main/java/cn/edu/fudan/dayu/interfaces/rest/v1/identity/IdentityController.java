package cn.edu.fudan.dayu.interfaces.rest.v1.identity;

import cn.edu.fudan.dayu.identity.api.AuthenticatedUser;
import cn.edu.fudan.dayu.identity.api.ClientIdentity;
import cn.edu.fudan.dayu.identity.api.IdentityService;
import cn.edu.fudan.dayu.identity.api.LoginCommand;
import cn.edu.fudan.dayu.identity.api.RegisterCommand;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
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

    public IdentityController(IdentityService identity) {
        this.identity = identity;
    }

    @GetMapping("/session")
    public ResponseEntity<SessionResponse> session(CsrfToken csrfToken) {
        return noStore(new SessionResponse(identity.getCurrentUser().isPresent(),
                identity.getCurrentUser().map(IdentityController::user).orElse(null),
                csrfToken.getToken()), HttpStatus.OK);
    }

    @PostMapping("/session")
    public ResponseEntity<SessionResponse> login(
            @Valid @RequestBody LoginRequest body, HttpServletRequest request, CsrfToken csrfToken) {
        AuthenticatedUser authenticated = identity.login(
                new LoginCommand(body.email().trim().toLowerCase(), body.password()),
                new ClientIdentity(request.getRemoteAddr(), request.getHeader("User-Agent")));
        rotateSessionId(request);
        return noStore(new SessionResponse(true, user(authenticated), csrfToken.getToken()), HttpStatus.OK);
    }

    @PostMapping("/users")
    public ResponseEntity<SessionResponse> register(
            @Valid @RequestBody RegisterRequest body, HttpServletRequest request, CsrfToken csrfToken) {
        AuthenticatedUser authenticated = identity.register(new RegisterCommand(
                body.email().trim().toLowerCase(), body.password(), body.organization().trim()));
        rotateSessionId(request);
        return noStore(new SessionResponse(true, user(authenticated), csrfToken.getToken()), HttpStatus.CREATED);
    }

    @DeleteMapping("/session")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        identity.logout();
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        return ResponseEntity.noContent().build();
    }

    private static void rotateSessionId(HttpServletRequest request) {
        if (request.getSession(false) != null) request.changeSessionId();
    }

    private static UserResponse user(AuthenticatedUser user) {
        return new UserResponse(user.id().value(), user.email(), user.organization(), user.role().name());
    }

    private static <T> ResponseEntity<T> noStore(T body, HttpStatus status) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(body);
    }

    public record LoginRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 128) String password) {}
    public record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 10, max = 128) String password,
            @NotBlank @Size(min = 2, max = 255) String organization) {}
    public record UserResponse(long userId, String email, String organization, String role) {}
    public record SessionResponse(boolean authenticated, UserResponse user, String csrfToken) {}
}
