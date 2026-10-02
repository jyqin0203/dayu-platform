package cn.edu.fudan.dayu.interfaces.rest.legacy;

import cn.edu.fudan.dayu.catalog.api.CatalogQueryService;
import cn.edu.fudan.dayu.catalog.api.ProductModePolicy;
import cn.edu.fudan.dayu.identity.api.ClientIdentity;
import cn.edu.fudan.dayu.identity.api.IdentityService;
import cn.edu.fudan.dayu.identity.api.LoginCommand;
import cn.edu.fudan.dayu.identity.api.RegisterCommand;
import cn.edu.fudan.dayu.interfaces.rest.SessionAuthentication;
import cn.edu.fudan.dayu.shared.kernel.BusinessException;
import cn.edu.fudan.dayu.shared.kernel.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 旧产品和认证 URL 的兼容适配器，以及冲突管理员 API 的退役响应。 */
@RestController
public class LegacyController {
    private final CatalogQueryService catalog;
    private final IdentityService identity;
    private final SessionAuthentication sessions;

    public LegacyController(CatalogQueryService catalog, IdentityService identity, SessionAuthentication sessions) {
        this.catalog = catalog;
        this.identity = identity;
        this.sessions = sessions;
    }

    @GetMapping("/api/products.php")
    public Map<String, Object> products() {
        List<Map<String, Object>> products = catalog.listPublishedProductDetails().stream()
                .flatMap(product -> product.modePolicies().stream().filter(ProductModePolicy::enabled)
                        .map(mode -> legacyProduct(product.summary().code().value(),
                                product.summary().nameZh(), product.summary().nameEn(),
                                product.summary().sortOrder(), product.colorbarRequired(), mode)))
                .toList();
        return Map.of("ok", true, "products", products);
    }

    @GetMapping(value = "/api/auth.php", params = {"action=status"})
    public ResponseEntity<Map<String, Object>> status(HttpServletRequest request, HttpServletResponse servletResponse) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("authenticated", identity.getCurrentUser().isPresent());
        response.put("user", identity.getCurrentUser().map(LegacyController::legacyUser).orElse(null));
        response.put("csrf_token", sessions.token(request, servletResponse));
        return noStore(response);
    }

    @PostMapping(value = "/api/auth.php", consumes = "application/json")
    public ResponseEntity<Map<String, Object>> auth(
            @RequestParam String action, @RequestBody LegacyAuthRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        var fields = new LinkedHashMap<String, String>();
        fields.put("email", body.email()); fields.put("password", body.password()); fields.put("organization", body.organization());
        return authenticate(action, fields, request, response);
    }

    private ResponseEntity<Map<String, Object>> authenticate(String action, Map<String, String> body,
            HttpServletRequest request, HttpServletResponse response) {
        if ("logout".equals(action)) {
            identity.logout();
            sessions.clear(request, response);
            return status(request, response);
        }
        if (!"register".equals(action) && !"login".equals(action))
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "未知认证操作");
        var user = "register".equals(action)
                ? identity.register(new RegisterCommand(
                        body.get("email"), body.get("password"), body.get("organization")))
                : identity.login(new LoginCommand(body.get("email"), body.get("password")),
                        new ClientIdentity(request.getRemoteAddr(), request.getHeader("User-Agent")));
        return noStore(Map.of("ok", true, "authenticated", true,
                "user", legacyUser(user), "csrf_token", sessions.establish(user, request, response)));
    }

    @PostMapping(value = "/api/auth.php", consumes = "application/x-www-form-urlencoded")
    public ResponseEntity<Map<String, Object>> authForm(@RequestParam Map<String, String> body,
            HttpServletRequest request, HttpServletResponse response) {
        return authenticate(body.get("action"), body, request, response);
    }

    /** Prevent MVC debug logs from revealing submitted credentials. */
    public record LegacyAuthRequest(String email, String password, String organization, String csrf_token) {
        @Override public String toString() { return "LegacyAuthRequest[redacted]"; }
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> authFailure(BusinessException exception) {
        int status = switch (exception.errorCode()) {
            case UNAUTHENTICATED -> 401;
            case FORBIDDEN -> 403;
            case CONFLICT -> 409;
            case RATE_LIMITED -> 429;
            case VALIDATION_FAILED -> 422;
            default -> 500;
        };
        var response = ResponseEntity.status(status).header("Cache-Control", "no-store");
        if (status == 429) response.header("Retry-After", String.valueOf(exception.details().getOrDefault("retryAfterSeconds", 900)));
        return response.body(Map.of("ok", false,
                "message", status == 500 ? "Internal server error" : exception.safeMessage()));
    }

    @GetMapping({"/api/admin.php", "/api/admin_products.php"})
    public ResponseEntity<Map<String, Object>> retiredAdminGet() {
        return retired();
    }

    @PostMapping({"/api/admin.php", "/api/admin_products.php"})
    public ResponseEntity<Map<String, Object>> retiredAdminPost() {
        return retired();
    }

    private static Map<String, Object> legacyProduct(
            String code, String zh, String en, int sortOrder,
            boolean colorbarRequired, ProductModePolicy mode) {
        boolean forecast = mode.dataMode().name().equals("FORECAST");
        return Map.of(
                "product_id", forecast ? "FCST_" + code : code,
                "path_id", code,
                "category", forecast ? "forecast" : "realtime",
                "name_zh", zh, "name_en", en,
                "title_zh", (forecast ? "预报 - " : "实况 - ") + zh,
                "title_en", (forecast ? "Forecast - " : "Realtime - ") + en,
                "sort_order", sortOrder, "colorbar_required", colorbarRequired,
                "status", "active");
    }

    private static Map<String, Object> legacyUser(cn.edu.fudan.dayu.identity.api.AuthenticatedUser user) {
        return Map.of("id", user.id().value(), "email", user.email(),
                "organization", user.organization(),
                "role", user.role().name().toLowerCase(Locale.ROOT));
    }

    private static ResponseEntity<Map<String, Object>> noStore(Map<String, Object> body) {
        Map<String, Object> redacted = new LinkedHashMap<>(body) {
            @Override public String toString() { return "LegacyAuthenticationResponse[redacted]"; }
        };
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(redacted);
    }

    private static ResponseEntity<Map<String, Object>> retired() {
        return ResponseEntity.status(HttpStatus.GONE).body(Map.of(
                "ok", false,
                "message", "This legacy administrator API has been retired. Use the new administrator interface."));
    }
}
