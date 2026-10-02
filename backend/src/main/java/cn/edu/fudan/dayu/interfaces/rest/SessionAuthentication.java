package cn.edu.fudan.dayu.interfaces.rest;

import cn.edu.fudan.dayu.identity.api.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.stereotype.Component;

/** Establishes authentication after persistence committed, scoped to one browser Session. */
@Component
public class SessionAuthentication {
    private final HttpSessionSecurityContextRepository contexts = new HttpSessionSecurityContextRepository();
    private final HttpSessionCsrfTokenRepository tokens;
    public SessionAuthentication(HttpSessionCsrfTokenRepository tokens) { this.tokens = tokens; }

    public String establish(AuthenticatedUser user, HttpServletRequest request, HttpServletResponse response) {
        try {
            request.getSession(true);
            request.changeSessionId();
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null,
                    AuthorityUtils.createAuthorityList("ROLE_" + user.role().name())));
            SecurityContextHolder.setContext(context);
            contexts.saveContext(context, request, response);
            return rotateToken(request, response);
        } catch (RuntimeException ex) {
            clear(request, response);
            throw new cn.edu.fudan.dayu.shared.kernel.BusinessException(
                    cn.edu.fudan.dayu.shared.kernel.ErrorCode.INTERNAL_ERROR, "认证会话建立失败，请尝试登录；已注册的账号无需再次注册");
        }
    }

    /** Invalidates old Session/token and expires cookie; Legacy may create a new anonymous Session. */
    public void clear(HttpServletRequest request, HttpServletResponse response) {
        SecurityContextHolder.clearContext();
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        response.addHeader("Set-Cookie", ResponseCookie.from("DAYUSESSID", "").path("/")
                .httpOnly(true).secure(request.isSecure()).sameSite("Lax").maxAge(0).build().toString());
        response.setHeader("Cache-Control", "no-store");
    }
    public String token(HttpServletRequest request, HttpServletResponse response) {
        CsrfToken token = tokens.loadToken(request);
        return token == null ? rotateToken(request, response) : token.getToken();
    }
    private String rotateToken(HttpServletRequest request, HttpServletResponse response) {
        CsrfToken token = tokens.generateToken(request);
        tokens.saveToken(token, request, response);
        request.setAttribute(CsrfToken.class.getName(), token);
        request.setAttribute("_csrf", token);
        return token.getToken();
    }
}
