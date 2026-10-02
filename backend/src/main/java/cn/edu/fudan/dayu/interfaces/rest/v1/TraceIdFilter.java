package cn.edu.fudan.dayu.interfaces.rest.v1;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 为 API 请求建立安全 traceId，并同时写入响应头、请求属性和日志 MDC。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {
    public static final String HEADER_NAME = "X-Trace-Id";
    public static final String ATTRIBUTE_NAME = TraceIdFilter.class.getName() + ".traceId";
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().substring(request.getContextPath().length()).startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = acceptedOrGenerated(request.getHeader(HEADER_NAME));
        request.setAttribute(ATTRIBUTE_NAME, traceId);
        response.setHeader(HEADER_NAME, traceId);
        String previous = MDC.get("traceId");
        MDC.put("traceId", traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (previous == null) MDC.remove("traceId");
            else MDC.put("traceId", previous);
        }
    }

    static String from(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE_NAME);
        if (value instanceof String traceId) return traceId;
        String traceId = generated();
        request.setAttribute(ATTRIBUTE_NAME, traceId);
        return traceId;
    }

    private static String acceptedOrGenerated(String candidate) {
        return candidate != null && SAFE_TRACE_ID.matcher(candidate).matches() ? candidate : generated();
    }

    private static String generated() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
