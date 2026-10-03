package cn.edu.fudan.dayu.interfaces.rest;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import cn.edu.fudan.dayu.identity.api.IdentityService;
import cn.edu.fudan.dayu.interfaces.rest.v1.ApiErrorResponse;
import cn.edu.fudan.dayu.interfaces.rest.v1.TraceIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Session/CSRF安全链，同时保护skeleton和真实账号HTTP请求。
 */
@Configuration
public class SkeletonSecurityConfiguration {

    @Bean HttpSessionCsrfTokenRepository dayuCsrfTokenRepository() {
        var repository = new HttpSessionCsrfTokenRepository();
        repository.setParameterName("csrf_token");
        return repository;
    }

    /** 从服务器Session恢复身份，并在每次请求重新读取账号状态和权限。 */
    @Bean
    SecurityFilterChain dayuSecurityFilterChain(HttpSecurity http, IdentityService identity,
            HttpSessionCsrfTokenRepository tokens, ObjectMapper mapper) throws Exception {
        http.csrf(csrf -> csrf.csrfTokenRepository(tokens)
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()));
        http.formLogin(form -> form.disable()).httpBasic(basic -> basic.disable()).logout(logout -> logout.disable());
        http.requestCache(cache -> cache.disable());
        http.addFilterBefore(new LegacyJsonCsrfFilter(mapper), org.springframework.security.web.csrf.CsrfFilter.class);
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/downloads/**", "/api/v1/downloads", "/api/download.php").authenticated()
                .anyRequest().permitAll());
        http.exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, ex) -> error(mapper, request, response, 401, "UNAUTHENTICATED", "请先登录"))
                .accessDeniedHandler((request, response, ex) -> error(mapper, request, response, 403, "FORBIDDEN", "无权限或CSRF校验失败")));
        http.addFilterBefore(new OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                    FilterChain chain) throws ServletException, IOException {
                if (request.getRequestURI().equals("/api/v1/session") || request.getRequestURI().equals("/api/v1/users")
                        || request.getRequestURI().equals("/api/auth.php")) response.setHeader("Cache-Control", "no-store");
                var authentication = SecurityContextHolder.getContext().getAuthentication();
                if (authentication != null && authentication.isAuthenticated()
                        && authentication.getPrincipal() instanceof cn.edu.fudan.dayu.identity.api.AuthenticatedUser) {
                    java.util.Optional<cn.edu.fudan.dayu.identity.api.AuthenticatedUser> current;
                    try {
                        current = identity.getCurrentUser();
                    } catch (RuntimeException unavailable) {
                        SecurityContextHolder.clearContext();
                        error(mapper, request, response, 500, "INTERNAL_ERROR", "暂时无法验证身份，请稍后重试");
                        return;
                    }
                    if (current.isEmpty()) {
                        SecurityContextHolder.clearContext();
                        var session = request.getSession(false);
                        if (session != null) session.invalidate();
                    } else {
                        var user = current.get();
                        var context = SecurityContextHolder.createEmptyContext();
                        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null,
                                AuthorityUtils.createAuthorityList("ROLE_" + user.role())));
                        SecurityContextHolder.setContext(context);
                    }
                }
                chain.doFilter(request, response);
            }
        }, AuthorizationFilter.class);
        return http.build();
    }

    private static void error(ObjectMapper mapper, HttpServletRequest request, HttpServletResponse response,
            int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        Object trace = request.getAttribute(TraceIdFilter.ATTRIBUTE_NAME);
        Object body = request.getRequestURI().endsWith(".php") ? Map.of("ok", false, "message", message)
                : new ApiErrorResponse(code, message, trace == null ? UUID.randomUUID().toString() : trace.toString(), Map.of());
        mapper.writeValue(response.getOutputStream(), body);
    }
}
